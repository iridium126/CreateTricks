package com.iridium126.createmanaindustry.content.fluids.fueltank;

import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Predicate;

import com.simibubi.create.AllBlocks;
import com.simibubi.create.content.decoration.copycat.CopycatBlock;
import com.simibubi.create.content.decoration.copycat.CopycatModel;
import com.simibubi.create.content.redstone.RoseQuartzLampBlock;
import com.simibubi.create.foundation.model.BakedModelWrapperWithData;

import net.createmod.catnip.data.Iterate;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.block.model.BakedQuad;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import net.minecraft.client.resources.model.BakedModel;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.BlockAndTintGetter;
import net.minecraft.world.level.ColorResolver;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.lighting.LevelLightEngine;
import net.minecraft.world.level.material.FluidState;
import net.minecraft.world.level.material.Fluids;
import net.neoforged.neoforge.client.model.data.ModelData;
import net.neoforged.neoforge.client.model.data.ModelData.Builder;
import net.neoforged.neoforge.client.model.data.ModelProperty;

/**
 * Delegate baked model that culls the faces shared with an adjacent fuel tank of
 * the same group (so the interior walls between connected tanks are not
 * rendered) and, per cell, skins the shell with a copycat material.
 * <p>
 * <b>Culling</b>: all six faces are culled by the same rule — including
 * top/bottom, which the unified four-variant model relies on instead of the
 * old per-axis blockstate variants (mirrors Create's {@code FluidTankModel}
 * without the connected-texture sprite shifting).
 * <p>
 * <b>Copycat shell</b>: when the cell's block entity carries a custom material
 * (mirrors {@code CopycatBlockEntity}, default = Create's {@code CopycatBase}),
 * each shell quad is replaced by the material's own face quads clipped to the
 * shell quad's region. Clipping keeps the material's real geometry positions,
 * per-tile UVs, sprites and tint indices, so:
 * <ul>
 * <li>connected-texture materials render their full tile pattern (edges,
 * corners, connection tiles) at the correct positions — the shell's outward
 * faces show the same CT variant the material would against its neighbours;
 * <li>multi-quad faces (grass' dirt base + tinted grass overlay) compose
 * correctly, both clipped to the shell;
 * <li>window panes and the open regions of the shell are never covered —
 * clipped rectangles that would land in a pane/open void simply don't
 * intersect any shell quad, so the interior and fluid stay visible.
 * </ul>
 * The material quads are derived per cell from the material's own model
 * through a {@link MaterialConnectivityGetter} that presents the material's
 * state at connecting neighbours (same-material tanks) and air elsewhere — the
 * canonical trick for making block-equality CT logic connect across the tank
 * structure. The quad lists are cached per (material, connectivity mask)
 * because the variants only depend on the set of connecting neighbours.
 * Window panes always keep the tank's glass texture; tinted material quads
 * keep their tint and the tank registers Create's {@code CopycatBlock
 * wrappedColor} block color so the renderer colours them by the material.
 */
public class FuelTankModel extends BakedModelWrapperWithData {

	protected static final ModelProperty<CullData> CULL_PROPERTY = new ModelProperty<>();
	/** Per-cell material face quad lists, computed by {@link #gatherModelData}. */
	protected static final ModelProperty<MaterialFaces> FACES_PROPERTY = new ModelProperty<>();

	private static final ResourceLocation SIDE_WINDOW_TEX = ResourceLocation.fromNamespaceAndPath("createmanaindustry",
		"block/molten_salt_fuel_tank/window_side");
	private static final ResourceLocation TOP_WINDOW_TEX = ResourceLocation.fromNamespaceAndPath("createmanaindustry",
		"block/molten_salt_fuel_tank/window_top");

	private static final float EPS = 1e-4f;

	private static final Map<FacesCacheKey, MaterialFaces> FACES_CACHE = new ConcurrentHashMap<>();

	private record FacesCacheKey(BlockState material, int connectivityMask) {}

	public FuelTankModel(BakedModel originalModel) {
		super(originalModel);
	}

	@Override
	protected ModelData.Builder gatherModelData(Builder builder, BlockAndTintGetter world, BlockPos pos,
		BlockState state, ModelData blockEntityData) {
		CullData cullData = new CullData();
		for (Direction d : Iterate.directions)
			cullData.setCulled(d, FuelTankConnectivity.isSameGroup(world, pos, pos.relative(d)));
		builder.with(CULL_PROPERTY, cullData);

		BlockState material = blockEntityData.get(CopycatModel.MATERIAL_PROPERTY);
		if (material == null || AllBlocks.COPYCAT_BASE.has(material))
			return builder;
		// Per-cell material faces: CT variants depend on the set of connecting
		// neighbours, so compute from this cell's world (cached by the
		// connectivity mask — identical masks yield identical results).
		builder.with(FACES_PROPERTY,
			facesFor(displayMaterial(world, pos, material), connectivityMask(world, pos, state), world, pos, state));
		return builder;
	}

	/**
	 * The state the shell is skinned with: a rose quartz lamp material mirrors
	 * the cell's own brightness, so POWERING mirrors the shared {@code LIT}
	 * blockstate verdict and the shell shows the powered texture exactly where
	 * the tank emits full light ({@link FuelTankBlock#getLightEmission} reads
	 * the same flag). The derived state is part of the {@link #FACES_CACHE}
	 * key, so lit/unlit faces cache separately; light changes rebuild the
	 * section mesh and re-derive the state.
	 */
	private static BlockState displayMaterial(BlockAndTintGetter world, BlockPos pos, BlockState material) {
		if (material.is(AllBlocks.ROSE_QUARTZ_LAMP.get()))
			return material.setValue(RoseQuartzLampBlock.POWERING,
				world.getBlockState(pos).getOptionalValue(FuelTankBlock.LIT).orElse(false));
		return material;
	}

	@Override
	public List<BakedQuad> getQuads(BlockState state, Direction side, RandomSource rand, ModelData extraData,
		RenderType renderType) {
		if (side != null)
			return Collections.emptyList();

		MaterialFaces faces = extraData.get(FACES_PROPERTY);
		List<BakedQuad> quads = new ArrayList<>();
		for (Direction d : Iterate.directions) {
			if (extraData.has(CULL_PROPERTY) && extraData.get(CULL_PROPERTY)
				.isCulled(d))
				continue;
			for (BakedQuad quad : super.getQuads(state, d, rand, extraData, renderType))
				quads.addAll(faces != null ? skinned(quad, faces) : List.of(quad));
		}
		for (BakedQuad quad : super.getQuads(state, null, rand, extraData, renderType))
			quads.addAll(faces != null ? skinned(quad, faces) : List.of(quad));
		return quads;
	}

	@Override
	public TextureAtlasSprite getParticleIcon(ModelData data) {
		MaterialFaces faces = data.get(FACES_PROPERTY);
		if (faces != null && faces.particle != null)
			return faces.particle;
		return super.getParticleIcon(data);
	}

	// ---- connectivity -------------------------------------------------------

	/** Bitmask of faces whose neighbour connects to this cell (material CT + cache key). */
	private static int connectivityMask(BlockAndTintGetter world, BlockPos pos, BlockState state) {
		int mask = 0;
		if (state.getBlock() instanceof CopycatBlock copycatBlock)
			for (Direction d : Iterate.directions)
				if (copycatBlock.canConnectTexturesToward(world, pos, pos.relative(d), state))
					mask |= 1 << d.get3DDataValue();
		return mask;
	}

	private static boolean connectsTo(BlockAndTintGetter world, BlockPos pos, BlockState state, BlockPos target) {
		return state.getBlock() instanceof CopycatBlock copycatBlock
			&& copycatBlock.canConnectTexturesToward(world, pos, target, state);
	}

	// ---- material face gathering ---------------------------------------------

	private static MaterialFaces facesFor(BlockState material, int mask, BlockAndTintGetter world, BlockPos pos,
		BlockState state) {
		return FACES_CACHE.computeIfAbsent(new FacesCacheKey(material, mask),
			key -> computeFaces(key.material(), key.connectivityMask(), world, pos, state));
	}

	private static MaterialFaces computeFaces(BlockState material, int mask, BlockAndTintGetter world, BlockPos pos,
		BlockState state) {
		BakedModel model = Minecraft.getInstance().getBlockRenderer().getBlockModel(material);

		// The material's own (possibly connected-texture) model must see
		// "same-material neighbours" where the tank connects: present the
		// material's state at connecting positions (most CT logic decides by
		// block equality — a raw tank state would never connect), air elsewhere.
		MaterialConnectivityGetter filtered = new MaterialConnectivityGetter(world, material,
			target -> connectsTo(world, pos, state, target));
		ModelData materialData = model.getModelData(filtered, pos, material, ModelData.EMPTY);

		// Merge side and unified quads per face.
		Map<Direction, List<BakedQuad>> faceQuads = new EnumMap<>(Direction.class);
		RandomSource rand = RandomSource.create();
		for (Direction d : Iterate.directions)
			faceQuads.put(d, new ArrayList<>(model.getQuads(material, d, rand, materialData, RenderType.cutoutMipped())));
		RandomSource nullRand = RandomSource.create();
		for (BakedQuad quad : model.getQuads(material, null, nullRand, materialData, RenderType.cutoutMipped())) {
			Direction d = quad.getDirection();
			if (d != null)
				faceQuads.computeIfAbsent(d, x -> new ArrayList<>()).add(quad);
		}

		MaterialFaces faces = new MaterialFaces();
		for (Direction d : Iterate.directions)
			faces.byFace.put(d, faceQuads.getOrDefault(d, List.of()));
		faces.particle = model.getParticleIcon(materialData);
		return faces;
	}

	// ---- shell skinning (rect clipping) --------------------------------------

	/**
	 * Replaces one shell quad with the material's face quads clipped to the shell
	 * quad's region; window panes always stay glass.
	 */
	private static List<BakedQuad> skinned(BakedQuad shell, MaterialFaces faces) {
		TextureAtlasSprite shellSprite = shell.getSprite();
		if (shellSprite == null)
			return List.of(shell);
		ResourceLocation name = shellSprite.contents().name();
		if (SIDE_WINDOW_TEX.equals(name) || TOP_WINDOW_TEX.equals(name))
			return List.of(shell);

		Direction d = shell.getDirection();
		if (d == null)
			return List.of(shell);
		List<BakedQuad> materialQuads = faces.forFace(d);
		if (materialQuads.isEmpty())
			return List.of(shell);

		List<BakedQuad> out = new ArrayList<>();
		for (BakedQuad material : materialQuads) {
			BakedQuad clipped = clipTo(shell, material);
			if (clipped != null)
				out.add(clipped);
		}
		return out.isEmpty() ? List.of(shell) : out;
	}

	/**
	 * Clips one material face quad to the shell quad's axis-aligned rectangle on
	 * the shared face plane; returns null when they don't overlap. The clipped
	 * quad inherits the shell's vertices (position/layout/lighting) with the
	 * position and UVs rebuilt from the intersection rectangle, and the
	 * material's sprite, tint and UV mapping — no sprite-space remapping needed.
	 */
	private static BakedQuad clipTo(BakedQuad shell, BakedQuad material) {
		Direction d = shell.getDirection();
		Direction.Axis normalAxis = d.getAxis();
		Direction.Axis aAxis = normalAxis == Direction.Axis.X ? Direction.Axis.Z : Direction.Axis.X;
		Direction.Axis bAxis = normalAxis == Direction.Axis.Y ? Direction.Axis.Z : Direction.Axis.Y;

		float[] shellRect = rectOf(shell, aAxis, bAxis);
		float[] matRect = rectOf(material, aAxis, bAxis);
		if (shellRect == null || matRect == null)
			return null;

		float minA = Math.max(shellRect[0], matRect[0]);
		float minB = Math.max(shellRect[1], matRect[1]);
		float maxA = Math.min(shellRect[2], matRect[2]);
		float maxB = Math.min(shellRect[3], matRect[3]);
		if (maxA - minA < EPS || maxB - minB < EPS)
			return null;

		float plane = planeOf(shell, normalAxis);
		int[] vertices = shell.getVertices().clone();
		if (vertices.length < 8 * 4)
			return null;

		// Corner roles of the material quad (in plane coords) and their atlas UVs.
		float[][][] matUv = cornerUvs(material, aAxis, bAxis, matRect);
		if (matUv == null)
			return null;

		// Insert the four clip corners in the shell's winding (orientation).
		float[][] corners = {
				{ minA, minB },
				{ maxA, minB },
				{ maxA, maxB },
				{ minA, maxB } };
		if (signedArea(shell, aAxis, bAxis) < 0) {
			float[] tmp = corners[1];
			corners[1] = corners[3];
			corners[3] = tmp;
		}

		for (int i = 0; i < 4; i++) {
			int base = i * 8;
			float a = corners[i][0];
			float b = corners[i][1];
			// Position on the shell's plane.
			vertices[base] = Float.floatToRawIntBits(coordinate(a, b, plane, aAxis, bAxis, normalAxis, Direction.Axis.X));
			vertices[base + 1] = Float.floatToRawIntBits(coordinate(a, b, plane, aAxis, bAxis, normalAxis, Direction.Axis.Y));
			vertices[base + 2] = Float.floatToRawIntBits(coordinate(a, b, plane, aAxis, bAxis, normalAxis, Direction.Axis.Z));
			// UV: bilinear over the material quad at the relative position.
			float s = (a - matRect[0]) / (matRect[2] - matRect[0]);
			float t = (b - matRect[1]) / (matRect[3] - matRect[1]);
			float u = (1 - s) * (1 - t) * matUv[0][0][0] + s * (1 - t) * matUv[1][0][0] + (1 - s) * t * matUv[0][1][0]
				+ s * t * matUv[1][1][0];
			float v = (1 - s) * (1 - t) * matUv[0][0][1] + s * (1 - t) * matUv[1][0][1] + (1 - s) * t * matUv[0][1][1]
				+ s * t * matUv[1][1][1];
			vertices[base + 4] = Float.floatToRawIntBits(u);
			vertices[base + 5] = Float.floatToRawIntBits(v);
		}

		return new BakedQuad(vertices, material.getTintIndex(), d, material.getSprite(), shell.isShade());
	}

	private static float coordinate(float a, float b, float plane, Direction.Axis aAxis, Direction.Axis bAxis,
		Direction.Axis normalAxis, Direction.Axis axis) {
		if (axis == aAxis)
			return a;
		if (axis == bAxis)
			return b;
		return plane;
	}

	/** {minA, minB, maxA, maxB} of the quad's vertices in the given tangent axes; null for degenerate input. */
	private static float[] rectOf(BakedQuad quad, Direction.Axis aAxis, Direction.Axis bAxis) {
		int[] vertices = quad.getVertices();
		if (vertices.length < 8 * 4)
			return null;
		float minA = Float.MAX_VALUE, minB = Float.MAX_VALUE, maxA = -Float.MAX_VALUE, maxB = -Float.MAX_VALUE;
		for (int i = 0; i < 4; i++) {
			int base = i * 8;
			float a = component(vertices, base, aAxis);
			float b = component(vertices, base, bAxis);
			minA = Math.min(minA, a);
			maxA = Math.max(maxA, a);
			minB = Math.min(minB, b);
			maxB = Math.max(maxB, b);
		}
		return new float[] { minA, minB, maxA, maxB };
	}

	private static float planeOf(BakedQuad quad, Direction.Axis normalAxis) {
		int base = 0;
		return component(quad.getVertices(), base, normalAxis);
	}

	private static float component(int[] vertices, int base, Direction.Axis axis) {
		return switch (axis) {
			case X -> Float.intBitsToFloat(vertices[base]);
			case Y -> Float.intBitsToFloat(vertices[base + 1]);
			case Z -> Float.intBitsToFloat(vertices[base + 2]);
		};
	}

	/** Signed doubled area of the quad in plane coords (winding orientation). */
	private static float signedArea(BakedQuad quad, Direction.Axis aAxis, Direction.Axis bAxis) {
		int[] vertices = quad.getVertices();
		float sum = 0;
		for (int i = 0; i < 4; i++) {
			int j = (i + 1) & 3;
			float ai = component(vertices, i * 8, aAxis);
			float bi = component(vertices, i * 8, bAxis);
			float aj = component(vertices, j * 8, aAxis);
			float bj = component(vertices, j * 8, bAxis);
			sum += ai * bj - aj * bi;
		}
		return sum;
	}

	/**
	 * Atlas UVs of the material quad keyed by its corner role
	 * ([loA|hiA][loB|hiB]); null when the quad is degenerate in plane coords.
	 */
	private static float[][][] cornerUvs(BakedQuad quad, Direction.Axis aAxis, Direction.Axis bAxis, float[] rect) {
		int[] vertices = quad.getVertices();
		float midA = (rect[0] + rect[2]) * 0.5f;
		float midB = (rect[1] + rect[3]) * 0.5f;
		float[][][] uv = new float[2][2][2];
		boolean[] seen = new boolean[4];
		for (int i = 0; i < 4; i++) {
			int base = i * 8;
			float a = component(vertices, base, aAxis);
			float b = component(vertices, base, bAxis);
			int roleA = a < midA ? 0 : 1;
			int roleB = b < midB ? 0 : 1;
			int slot = roleA * 2 + roleB;
			if (seen[slot])
				return null;
			seen[slot] = true;
			uv[roleA][roleB][0] = Float.intBitsToFloat(vertices[base + 4]);
			uv[roleA][roleB][1] = Float.intBitsToFloat(vertices[base + 5]);
		}
		return uv;
	}

	// ---- helpers ------------------------------------------------------------

	/**
	 * World view used to evaluate the material's own (possibly connected-texture)
	 * model: "connecting" positions (same-material tanks) are presented as the
	 * material's own state so block-equality CT logic connects; everything else
	 * reads as air. Block entities and model data of allowed positions pass
	 * through for appearance/extended CT implementations.
	 */
	private static final class MaterialConnectivityGetter implements BlockAndTintGetter {

		private final BlockAndTintGetter wrapped;
		private final BlockState material;
		private final Predicate<BlockPos> allowed;

		MaterialConnectivityGetter(BlockAndTintGetter wrapped, BlockState material, Predicate<BlockPos> allowed) {
			this.wrapped = wrapped;
			this.material = material;
			this.allowed = allowed;
		}

		@Override
		public BlockState getBlockState(BlockPos pos) {
			return allowed.test(pos) ? material : Blocks.AIR.defaultBlockState();
		}

		@Override
		public BlockEntity getBlockEntity(BlockPos pos) {
			return allowed.test(pos) ? wrapped.getBlockEntity(pos) : null;
		}

		@Override
		public FluidState getFluidState(BlockPos pos) {
			return allowed.test(pos) ? wrapped.getFluidState(pos) : Fluids.EMPTY.defaultFluidState();
		}

		@Override
		public ModelData getModelData(BlockPos pos) {
			return allowed.test(pos) ? wrapped.getModelData(pos) : ModelData.EMPTY;
		}

		@Override
		public int getHeight() {
			return wrapped.getHeight();
		}

		@Override
		public int getMinBuildHeight() {
			return wrapped.getMinBuildHeight();
		}

		@Override
		public float getShade(Direction direction, boolean shade) {
			return wrapped.getShade(direction, shade);
		}

		@Override
		public LevelLightEngine getLightEngine() {
			return wrapped.getLightEngine();
		}

		@Override
		public int getBlockTint(BlockPos pos, ColorResolver colorResolver) {
			return wrapped.getBlockTint(pos, colorResolver);
		}
	}

	private static final class MaterialFaces {
		final Map<Direction, List<BakedQuad>> byFace = new EnumMap<>(Direction.class);
		TextureAtlasSprite particle;

		List<BakedQuad> forFace(Direction face) {
			List<BakedQuad> list = byFace.get(face);
			return list != null ? list : List.of();
		}
	}

	private static class CullData {
		final boolean[] culledFaces = new boolean[6];

		void setCulled(Direction face, boolean cull) {
			culledFaces[face.get3DDataValue()] = cull;
		}

		boolean isCulled(Direction face) {
			return culledFaces[face.get3DDataValue()];
		}
	}
}