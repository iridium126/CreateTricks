package com.iridium126.createmanaindustry.content.fluids.fueltank;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

import com.iridium126.createmanaindustry.content.fluids.fueltank.FuelTankConnectivity.Basin;
import com.iridium126.createmanaindustry.content.fluids.fueltank.FuelTankConnectivity.BasinData;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.mojang.math.Axis;
import com.simibubi.create.foundation.blockEntity.renderer.SafeBlockEntityRenderer;

import net.createmod.catnip.animation.LerpedFloat;
import net.createmod.catnip.platform.NeoForgeCatnipServices;
import net.createmod.catnip.render.FluidRenderHelper;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.blockentity.BlockEntityRendererProvider;
import net.minecraft.client.renderer.texture.TextureAtlas;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.Mth;
import net.minecraft.world.phys.Vec3;

import net.neoforged.neoforge.client.extensions.common.IClientFluidTypeExtensions;
import net.neoforged.neoforge.fluids.FluidStack;
import net.neoforged.neoforge.fluids.FluidType;

/**
 * Renders the fuel tank's liquid as a few merged boxes per basin instead of one
 * box per cell. Each basin's per-level cell rectangles are greedy-merged once
 * (cached in {@link BasinData#renderCache}) and stacked vertically; only the
 * surface box's height is animated per frame. Only the controller renders.
 */
public class FuelTankRenderer extends SafeBlockEntityRenderer<FuelTankBlockEntity> {

	private static final float HULL = 1 / 16f + 1 / 128f;
	// Gap below the 1-unit top ring / above the 1-unit bottom plate (the model's
	// lid and base are 1 unit thick since the unified model; the old 1/4 gap was
	// tuned to the previous 4-unit slabs).
	private static final float CAP = 1 / 16f + 1 / 128f;
	private static final float PUDDLE = 1 / 16f;

	public FuelTankRenderer(BlockEntityRendererProvider.Context context) {
	}

	@Override
	protected void renderSafe(FuelTankBlockEntity be, float partialTicks, PoseStack ms, MultiBufferSource buffer,
		int light, int overlay) {
		if (!be.isController())
			return;
		BasinData data = be.basins;
		if (data == null) {
			renderFallbackLevel(be, partialTicks, buffer, ms, light);
			return;
		}

		FluidStack fluid = be.getTankInventory().getFluid();
		if (fluid.isEmpty())
			return;

		RenderCache cache = (RenderCache) data.renderCache;
		if (cache == null || cache.controller != be.getBlockPos()) {
			cache = new RenderCache();
			cache.controller = be.getBlockPos();
			data.renderCache = cache;
			buildLevelRects(data, cache);
		}

		BlockPos c = be.getBlockPos();
		for (int i = 0; i < data.basins.size(); i++) {
			Basin basin = data.basins.get(i);
			float surface = chasedSurface(data, i, partialTicks);
			if (surface <= basin.minY)
				continue;

			// full levels below the surface level, stacked into boxes; when the basin is
			// exactly full (surface == maxY + 1) the top level is rendered by the surface
			// box below so its top face sits at the lid underside, not on the tank's lid.
			int surfaceLevel = Math.min(Mth.floor(surface), basin.maxY);
			CachedStack stack = cache.stacked.computeIfAbsent(i, k -> new CachedStack());
			if (stack.surfaceLevel != surfaceLevel) {
				// Rebuild the stacked boxes and their face flags. The box set is a pure
				// integer function of (rects, surfaceLevel) — unchanged while the surface
				// floats within a level — and every layer the boxes span lies below the
				// surface, so their face visibility depends only on the static cell map
				// (the liquid condition surfaces > y always holds there). Both stay valid
				// for the whole epoch; only the surface box is recomputed per frame.
				stack.boxes = stackFullLevels(cache, basin, surfaceLevel);
				stack.faces = new boolean[stack.boxes.size()][4];
				for (int bi = 0; bi < stack.boxes.size(); bi++) {
					Box box = stack.boxes.get(bi);
					int by = Mth.floor(box.y1), yTop = Mth.ceil(box.y2);
					int bx = Mth.floor(box.x1), bz = Mth.floor(box.z1);
					stack.faces[bi][0] = isFaceHidden(data, i, bx - 1, by, yTop, bz);
					stack.faces[bi][1] = isFaceHidden(data, i, Mth.floor(box.x2), by, yTop, bz);
					stack.faces[bi][2] = isFaceHidden(data, i, bx, by, yTop, bz - 1);
					stack.faces[bi][3] = isFaceHidden(data, i, bx, by, yTop, Mth.floor(box.z2));
				}
				stack.surfaceLevel = surfaceLevel;
			}
			for (int bi = 0; bi < stack.boxes.size(); bi++)
				renderBox(c, fluid, stack.boxes.get(bi), stack.faces[bi], buffer, ms, light, data, i);

			// the surface box (partial top); lid/base insets are applied per box
			Map<Integer, List<Rect>> rectsByLevel = cache.levelRects.get(i);
			if (rectsByLevel == null)
				continue;
			List<Rect> surfRects = rectsByLevel.get(surfaceLevel);
			if (surfRects != null) {
				for (Rect r : surfRects) {
					float yMax = Math.min(surface, surfaceLevel + 1);
					if (yMax <= surfaceLevel)
						continue;
					renderBox(c, fluid, new Box(r.x1, surfaceLevel, r.z1, r.x2 + 1, yMax, r.z2 + 1), buffer, ms,
						light, data, i);
				}
			}
		}
	}

	/**
	 * Fallback when no basin geometry is available (fresh placement before the first
	 * basin sync, or a cross-chunk group still loading). Renders a single column of
	 * liquid whose height is the fill fraction times the tank height, so the surface
	 * is one uniform level. Computed per frame, no geometry to cache.
	 */
	private void renderFallbackLevel(FuelTankBlockEntity be, float partialTicks, MultiBufferSource buffer,
		PoseStack ms, int light) {
		FluidStack fluid = be.getTankInventory().getFluid();
		if (fluid.isEmpty())
			return;
		LerpedFloat level = be.getFluidLevel();
		float fill = level != null ? level.getValue(partialTicks) : be.getFillState();
		if (fill <= 0)
			return;
		int height = be.getFallbackHeight();
		float yMax = fill * height;
		if (yMax <= 0)
			return;

		BlockPos c = be.getBlockPos();
		BlockPos min = be.getFallbackMin();
		BlockPos max = be.getFallbackMax();
		float xMin, zMin, xMax, zMax, yMin;
		if (min != null && max != null) {
			// Saved footprint (cross-chunk group still loading): span the whole horizontal
			// extent so a large tank shows a group-wide level, not a thin column. The
			// bottom-most cell has no tank below it, so it always carries a closed base.
			xMin = (min.getX() - c.getX()) + HULL;
			xMax = (max.getX() - c.getX() + 1) - HULL;
			zMin = (min.getZ() - c.getZ()) + HULL;
			zMax = (max.getZ() - c.getZ() + 1) - HULL;
			yMin = (min.getY() - c.getY()) + CAP;
		} else {
			// No footprint — single-column fallback at the controller.
			xMin = HULL;
			xMax = 1 - HULL;
			zMin = HULL;
			zMax = 1 - HULL;
			yMin = 0;
			// Inset when no same-group tank sits below the controller (a closed bottom);
			// equivalent to the removed BOTTOM blockstate flag.
			if (!FuelTankConnectivity.isSameGroup(be.getLevel(), c, c.below()))
				yMin = CAP;
		}
		// Lid inset: the column's top cell is the group's topmost cell, which always
		// has a closed lid above it — stop the surface just below it like the main
		// path's CAP gap (a full tank's fallback surface must not touch the lid).
		yMax = Math.min(yMax, height - CAP);
		if (yMax - yMin <= 0)
			return;

		NeoForgeCatnipServices.FLUID_RENDERER.renderFluidBox(fluid, xMin, yMin, zMin, xMax, yMax, zMax, buffer, ms, light,
			false, true);
	}

	private float chasedSurface(BasinData data, int basin, float partialTicks) {
		if (data.chasers != null && basin < data.chasers.length && data.chasers[basin] != null)
			return data.chasers[basin].getValue(partialTicks);
		return data.surfaces[basin];
	}

	private List<Box> stackFullLevels(RenderCache cache, Basin basin, int surfaceLevel) {
		Map<Integer, List<Rect>> rectsByLevel = cache.levelRects.get(basin.id);
		if (rectsByLevel == null)
			return List.of();

		List<Box> boxes = new ArrayList<>();
		Map<Rect, Box> open = new HashMap<>();
		for (Map.Entry<Integer, List<Rect>> e : rectsByLevel.entrySet()) {
			int y = e.getKey();
			if (y >= surfaceLevel)
				break;
			List<Rect> rects = e.getValue();
			Set<Rect> processed = new HashSet<>();
			for (Rect r : rects) {
				Box box = open.get(r);
				if (box != null && box.y2 == y) {
					box.y2 = y + 1;
				} else {
					box = new Box(r.x1, y, r.z1, r.x2 + 1, y + 1, r.z2 + 1);
					open.put(r, box);
					boxes.add(box);
				}
				processed.add(r);
			}
			open.entrySet()
				.removeIf(en -> !processed.contains(en.getKey()));
		}
		return boxes;
	}

	// ---- per-box rendering ---------------------------------------------------

	/** Per-frame box rendering: face visibility recomputed (the box reaches the surface). */
	private void renderBox(BlockPos c, FluidStack fluid, Box box, MultiBufferSource buffer,
		PoseStack ms, int light, BasinData data, int basinId) {
		renderBox(c, fluid, box, computeFaces(data, basinId, box), buffer, ms, light, data, basinId);
	}

	/** Face visibility for a box whose height reaches the (fractional) surface. */
	private boolean[] computeFaces(BasinData data, int basinId, Box box) {
		int bx = Mth.floor(box.x1), by = Mth.floor(box.y1), bz = Mth.floor(box.z1);
		// ceil so the partial surface box (box.y2 fractional) still checks its own level;
		// floor would give an empty range there and cull the surface's sides entirely.
		int yTop = Mth.ceil(box.y2);
		return new boolean[] {
			isFaceHidden(data, basinId, bx - 1, by, yTop, bz),
			isFaceHidden(data, basinId, Mth.floor(box.x2), by, yTop, bz),
			isFaceHidden(data, basinId, bx, by, yTop, bz - 1),
			isFaceHidden(data, basinId, bx, by, yTop, Mth.floor(box.z2))
		};
	}

	private void renderBox(BlockPos c, FluidStack fluid, Box box, boolean[] faces, MultiBufferSource buffer,
		PoseStack ms, int light, BasinData data, int basinId) {
		boolean saddle = data.basins.get(basinId).ridge;
		float yMin = box.y1;
		float yMax = box.y2;
		if (yMax - yMin <= 0)
			return;

		// base inset (floor gap) for boxes sitting on a tank's closed bottom; the
		// cell is a bottom cell when no same-group cell sits below it (basin-map
		// derived — no world read, so contraption-local coordinates work too)
		BlockPos baseCell = new BlockPos(Mth.floor(box.x1), Mth.floor(box.y1), Mth.floor(box.z1));
		if (!data.basinByCell.containsKey(baseCell.below()))
			yMin = Math.max(yMin, box.y1 + CAP);

		// lid inset: stop just below a closed lid so the surface never touches it
		// (the geometry is grouped by occlusion, so this box's cells share one lid state)
		int topCell = Mth.floor(box.y2 - 0.001f);
		BlockPos lidCell = new BlockPos(Mth.floor(box.x1), topCell, Mth.floor(box.z1));
		if (!data.basinByCell.containsKey(lidCell.above()))
			yMax = Math.min(yMax, topCell + 1 - CAP);

		// a little liquid hidden by the floor gap still shows a thin puddle (Create-like).
		// Not on saddle basins: the saddle and the legs it absorbed rise as one flat
		// surface, and forcing a minimum layer there makes it stick above the level.
		if (!saddle && yMax - yMin < PUDDLE && yMax > box.y1)
			yMax = yMin + PUDDLE;
		if (yMax - yMin <= 0)
			return;

		// Vertical faces against same-basin liquid are hidden inside the liquid body and
		// the tank wall between the cells is already culled, so they sit flush and are not
		// rendered. All other faces get the normal hull inset.
		boolean west = faces[0];
		boolean east = faces[1];
		boolean north = faces[2];
		boolean south = faces[3];

		float xMinL = box.x1 + (west ? 0 : HULL) - c.getX();
		float xMaxL = box.x2 - (east ? 0 : HULL) - c.getX();
		float zMinL = box.z1 + (north ? 0 : HULL) - c.getZ();
		float zMaxL = box.z2 - (south ? 0 : HULL) - c.getZ();

		renderFluidFaces(fluid, xMinL, yMin - c.getY(), zMinL, xMaxL, yMax - c.getY(), zMaxL, buffer, ms, light,
			west, east, north, south);
	}

	/**
	 * Whether every cell between {@code yFrom} (inclusive) and {@code yTo} (exclusive)
	 * at {@code (x, z)} belongs to the same basin with liquid below the surface: then a
	 * box face spanning that range meets liquid along its whole height and is internal
	 * (hidden). If any single layer looks out of the group / into a different basin /
	 * above the surface, the face must render there.
	 */
	private boolean isFaceHidden(BasinData data, int basinId, int x, int yFrom, int yTo, int z) {
		for (int yy = yFrom; yy < yTo; yy++) {
			Integer nb = data.basinByCell.get(new BlockPos(x, yy, z));
			if (nb == null || nb != basinId || data.surfaces[basinId] <= yy)
				return false;
		}
		return true;
	}

	/**
	 * Renders a fluid box's faces individually so culled (internal) vertical faces can be
	 * skipped. Mirrors {@link net.createmod.catnip.render.FluidRenderHelper#renderFluidBox}
	 * but with per-face control.
	 */
	private void renderFluidFaces(FluidStack fluid, float xMin, float yMin, float zMin, float xMax, float yMax,
		float zMax, MultiBufferSource buffer, PoseStack ms, int light, boolean cullWest, boolean cullEast,
		boolean cullNorth, boolean cullSouth) {
		FluidType type = fluid.getFluid().getFluidType();
		IClientFluidTypeExtensions ext = IClientFluidTypeExtensions.of(fluid.getFluid());
		ResourceLocation still = ext.getStillTexture(fluid);
		if (still == null)
			return;
		TextureAtlasSprite texture = Minecraft.getInstance()
			.getTextureAtlas(TextureAtlas.LOCATION_BLOCKS)
			.apply(still);
		int color = ext.getTintColor(fluid);
		int blockLightIn = (light >> 4) & 0xF;
		int luminosity = Math.max(blockLightIn, type.getLightLevel());
		light = (light & 0xF00000) | luminosity << 4;
		VertexConsumer builder = FluidRenderHelper.getFluidBuilder(buffer);

		ms.pushPose();
		if (type.isLighterThanAir()) {
			Vec3 center = new Vec3(xMin + (xMax - xMin) / 2, yMin + (yMax - yMin) / 2, zMin + (zMax - zMin) / 2);
			ms.translate(center.x, center.y, center.z);
			ms.mulPose(Axis.XP.rotationDegrees(180));
			ms.translate(-center.x, -center.y, -center.z);
		}

		if (!cullWest)
			FluidRenderHelper.renderStillTiledFace(Direction.WEST, zMin, yMin, zMax, yMax, xMin, builder, ms, light,
				color, texture);
		if (!cullEast)
			FluidRenderHelper.renderStillTiledFace(Direction.EAST, zMin, yMin, zMax, yMax, xMax, builder, ms, light,
				color, texture);
		if (!cullNorth)
			FluidRenderHelper.renderStillTiledFace(Direction.NORTH, xMin, yMin, xMax, yMax, zMin, builder, ms, light,
				color, texture);
		if (!cullSouth)
			FluidRenderHelper.renderStillTiledFace(Direction.SOUTH, xMin, yMin, xMax, yMax, zMax, builder, ms, light,
				color, texture);
		FluidRenderHelper.renderStillTiledFace(Direction.UP, xMin, zMin, xMax, zMax, yMax, builder, ms, light, color,
			texture);

		ms.popPose();
	}

	// ---- cached geometry -----------------------------------------------------

	/** Per-basin level rectangles; rebuilt only when the basin data changes. */
	public static final class RenderCache {
		public BlockPos controller;
		public final Map<Integer, Map<Integer, List<Rect>>> levelRects = new HashMap<>();
		/** Per-basin stacked boxes and their face flags; see {@link CachedStack}. */
		public final Map<Integer, CachedStack> stacked = new HashMap<>();
	}

	/**
	 * The stacked (fully submerged) boxes of one basin and their face-visibility
	 * flags, rebuilt only when the basin's {@code surfaceLevel} (the floor of the
	 * chased surface) changes. Both are constant while the surface floats within a
	 * level: the boxes are an integer function of the level rects, and every layer
	 * they span lies below the surface, so the face flags depend only on the static
	 * cell map.
	 */
	public static final class CachedStack {
		int surfaceLevel = Integer.MIN_VALUE;
		List<Box> boxes = List.of();
		boolean[][] faces = new boolean[0][];
	}

	private void buildLevelRects(BasinData data, RenderCache cache) {
		cache.levelRects.clear();
		for (Basin basin : data.basins) {
			// TreeMap keeps levels ascending, which stackFullLevels relies on when it
			// breaks on the first level at or above the surface.
			Map<Integer, List<Rect>> map = new TreeMap<>();
			for (Map.Entry<Integer, List<BlockPos>> e : basin.cellsByLevel().entrySet()) {
				// Group cells by vertical occlusion so every rect sits under a uniform
				// lid/base; renderBox can then cap the whole box without leaving gaps
				// where neighbouring cells differ (e.g. an inverted-U crossbar).
				Map<Integer, List<BlockPos>> byOcclusion = new HashMap<>();
				for (BlockPos p : e.getValue())
					byOcclusion.computeIfAbsent(occlusionKey(data, p), k -> new ArrayList<>()).add(p);
				for (Map.Entry<Integer, List<BlockPos>> group : byOcclusion.entrySet())
					map.computeIfAbsent(e.getKey(), k -> new ArrayList<>())
						.addAll(mergeLevel(group.getValue(), group.getKey()));
			}
			cache.levelRects.put(basin.id, map);
		}
	}

	/**
	 * {@code (top ? 1 : 0) | (bottom ? 2 : 0)} for the cell, derived from the basin
	 * map instead of the world: a cell is a top/bottom cell exactly when no
	 * same-group cell sits above/below it, which is what the blockstate flags
	 * encode. Never reads {@code be.getLevel()}, so it also holds for contraption
	 * block entities whose basin coordinates are contraption-local.
	 */
	private static int occlusionKey(BasinData data, BlockPos p) {
		int key = 0;
		if (!data.basinByCell.containsKey(p.above()))
			key |= 1; // TOP
		if (!data.basinByCell.containsKey(p.below()))
			key |= 2; // BOTTOM
		return key;
	}

	/**
	 * Greedy merge of same-level cells into minimal axis-aligned rectangles. The
	 * occlusion key is carried on each rect so {@code stackFullLevels} never stacks
	 * cells that sit under a different lid/base: two same-coordinate rects with a
	 * different occlusion would otherwise merge into one box whose faces get culled
	 * from the wrong neighbour level (a base cell buried inside the tank would hide
	 * the pillar cell above it).
	 */
	private static List<Rect> mergeLevel(List<BlockPos> cells, int occlusion) {
		Set<Long> present = new HashSet<>();
		for (BlockPos p : cells)
			present.add(pack(p.getX(), p.getZ()));

		List<Rect> rects = new ArrayList<>();
		Set<Long> visited = new HashSet<>();
		for (BlockPos p : cells) {
			long k = pack(p.getX(), p.getZ());
			if (visited.contains(k))
				continue;
			int x1 = p.getX(), z1 = p.getZ();
			int x2 = x1, z2 = z1;
			while (isColumnFull(present, x2 + 1, z1, z2))
				x2++;
			while (isRowFull(present, x1, x2, z2 + 1))
				z2++;
			for (int x = x1; x <= x2; x++)
				for (int z = z1; z <= z2; z++)
					visited.add(pack(x, z));
			rects.add(new Rect(x1, z1, x2, z2, occlusion));
		}
		return rects;
	}

	private static boolean isColumnFull(Set<Long> present, int x, int z1, int z2) {
		for (int z = z1; z <= z2; z++)
			if (!present.contains(pack(x, z)))
				return false;
		return true;
	}

	private static boolean isRowFull(Set<Long> present, int x1, int x2, int z) {
		for (int x = x1; x <= x2; x++)
			if (!present.contains(pack(x, z)))
				return false;
		return true;
	}

	private static long pack(int x, int z) {
		return ((long) x << 32) | (z & 0xFFFFFFFFL);
	}

	// ---- geometry records ----------------------------------------------------

	static final class Rect {
		final int x1, z1, x2, z2;
		final int occlusion;

		Rect(int x1, int z1, int x2, int z2, int occlusion) {
			this.x1 = x1;
			this.z1 = z1;
			this.x2 = x2;
			this.z2 = z2;
			this.occlusion = occlusion;
		}

		@Override
		public boolean equals(Object o) {
			return o instanceof Rect r && r.x1 == x1 && r.z1 == z1 && r.x2 == x2 && r.z2 == z2
				&& r.occlusion == occlusion;
		}

		@Override
		public int hashCode() {
			return ((((x1 * 31 + z1) * 31 + x2) * 31 + z2) * 31 + occlusion);
		}
	}

	static final class Box {
		final float x1, y1, z1, x2, z2;
		float y2; // may extend as boxes are stacked

		Box(float x1, float y1, float z1, float x2, float y2, float z2) {
			this.x1 = x1;
			this.y1 = y1;
			this.z1 = z1;
			this.x2 = x2;
			this.y2 = y2;
			this.z2 = z2;
		}
	}

	@Override
	public boolean shouldRenderOffScreen(FuelTankBlockEntity be) {
		return be.isController();
	}
}
