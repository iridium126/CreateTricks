package com.iridium126.createmanaindustry.content.fluids.fueltank;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import org.jetbrains.annotations.Nullable;

import com.iridium126.createmanaindustry.config.ServerConfig;

import net.createmod.catnip.nbt.NBTHelper;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtUtils;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;

/**
 * Molten Salt Reactor Fuel Rod — structural recognition for the fuel-rod
 * multiblock: a vertical stack of diamond layers. A layer of radius {@code r}
 * (2 ≤ r ≤ {@link ServerConfig#fuelRodMaxRadius}) is a solid diamond of molten
 * salt fuel tanks at Manhattan distance {@code < r - 1} from the layer's
 * centre, outlined by glass blocks ({@code #minecraft:impermeable}) at distance
 * {@code r - 1}. The minimum layer (r = 2) is one tank surrounded by four
 * glass blocks. Layers stack contiguously with their centres aligned; with
 * {@link ServerConfig#fuelRodStrictStacking} every layer's radius must be ≤
 * the layer below it.
 * <p>
 * Recognition is driven from any structure-affecting change: fuel tank
 * place/remove (piggybacking the connectivity update), glass place/break
 * (NeoForge events) and a lazy self-heal on the rod controller. The verdict
 * lives on the bottom-centre tank's block entity as {@link RodData}; shape
 * validation is fully decoupled from the fuel tanks' fluid grouping.
 * <p>
 * The centre of a rod is found by probing every candidate column within
 * Manhattan distance {@code fuelRodMaxRadius - 1} of the trigger position —
 * every layer's centre cell is a fuel tank, so the true centre column is the
 * candidate whose probed cell is a tank — then descending that column to its
 * bottom tank. Cells in unloaded chunks make the verdict inconclusive
 * ("uncertain"); existing data is preserved and the lazy self-heal retries.
 */
public final class FuelRodStructure {

	private FuelRodStructure() {
	}

	/** The formed rod's data; stored on the bottom-centre tank's block entity. */
	public static final class RodData {
		public final BlockPos center;
		public final int height;
		public final int[] radii; // per layer, index = y offset from center.getY()
		public final int maxRadius; // largest layer radius

		public RodData(BlockPos center, int[] radii) {
			this.center = center.immutable();
			this.radii = radii.clone();
			this.height = radii.length;
			int m = 0;
			for (int r : radii)
				m = Math.max(m, r);
			this.maxRadius = m;
		}

		public int radiusAt(int yOffset) {
			return radii[yOffset];
		}

		public CompoundTag writeToNBT() {
			CompoundTag tag = new CompoundTag();
			tag.put("Center", NbtUtils.writeBlockPos(center));
			tag.putIntArray("Radii", radii);
			return tag;
		}

		public static RodData readFromNBT(CompoundTag tag) {
			return new RodData(NBTHelper.readBlockPos(tag, "Center"), tag.getIntArray("Radii"));
		}

		@Override
		public boolean equals(Object o) {
			return o instanceof RodData r && r.center.equals(center) && Arrays.equals(r.radii, radii);
		}

		@Override
		public int hashCode() {
			return 31 * center.hashCode() + Arrays.hashCode(radii);
		}
	}

	/** Verdict of a structural check. */
	private static final class Check {
		static final Check UNCERTAIN = new Check(null, null, true);
		final RodData rod;
		final BlockPos bottom;
		final boolean uncertain;

		private Check(RodData rod, BlockPos bottom, boolean uncertain) {
			this.rod = rod;
			this.bottom = bottom;
			this.uncertain = uncertain;
		}

		static Check invalid(BlockPos bottom) {
			return new Check(null, bottom, false);
		}

		static Check formed(RodData rod) {
			return new Check(rod, rod.center, false);
		}
	}

	// ============================ entry points ============================

	/**
	 * Re-validates every fuel rod whose structure could contain {@code pos}
	 * (the position of a placed/removed fuel tank or glass block). Candidate
	 * centres lie within Manhattan distance {@code fuelRodMaxRadius - 1} of the
	 * trigger; each candidate column is descended to its bottom tank and the
	 * stack validated once per distinct bottom.
	 */
	public static void validateFor(Level level, BlockPos pos) {
		if (level == null || level.isClientSide)
			return;
		int n = ServerConfig.fuelRodMaxRadius;
		Set<BlockPos> bottoms = new HashSet<>();
		for (int dx = -(n - 1); dx <= n - 1; dx++) {
			for (int dz = -(n - 1); dz <= n - 1; dz++) {
				if (Math.abs(dx) + Math.abs(dz) > n - 1)
					continue;
				BlockPos cand = pos.offset(dx, 0, dz);
				BlockPos bottom = bottomOf(level, cand, pos.getY());
				if (bottom != null)
					bottoms.add(bottom);
			}
		}
		for (BlockPos bottom : bottoms)
			apply(level, validateStack(level, bottom));
	}

	/**
	 * Validates the rod whose bottom-centre tank is {@code bottomPos} and applies
	 * the verdict (self-heal path; {@code bottomPos} is known to be a tank).
	 */
	public static void validateFrom(Level level, BlockPos bottomPos) {
		if (level == null || level.isClientSide)
			return;
		apply(level, validateStack(level, bottomPos));
	}

	// ============================ stack check ============================

	/**
	 * Validates the stack rooted at the bottom-centre tank {@code bottom} (the
	 * caller derived the centre) and returns the verdict: the bottom cell must
	 * itself be a tank, each layer upward must be a valid diamond whose centre
	 * cell is a tank (the rod ends at the first non-tank centre cell), and with
	 * strict stacking each layer's radius must not exceed the layer below.
	 */
	private static Check validateStack(Level level, BlockPos bottom) {
		if (!isFuelTankAt(level, bottom))
			return Check.invalid(bottom);
		int n = ServerConfig.fuelRodMaxRadius;
		boolean strict = ServerConfig.fuelRodStrictStacking;

		List<Integer> radii = new ArrayList<>();
		int prev = -1;
		for (int ly = 0;; ly++) {
			BlockPos centerCell = bottom.above(ly);
			if (!level.isLoaded(centerCell))
				return Check.UNCERTAIN;
			if (!isFuelTankAt(level, centerCell))
				break; // rod ends here
			int r = scanLayer(level, centerCell, n);
			if (r == -2)
				return Check.UNCERTAIN;
			if (r < 0)
				return Check.invalid(bottom);
			if (strict && prev >= 0 && r > prev)
				return Check.invalid(bottom);
			radii.add(r);
			prev = r;
		}
		if (radii.isEmpty())
			return Check.invalid(bottom);

		int[] arr = new int[radii.size()];
		for (int i = 0; i < arr.length; i++)
			arr[i] = radii.get(i);
		return Check.formed(new RodData(bottom, arr));
	}

	/**
	 * Scans one layer for its radius: rings at distance {@code d = 1..n-1} must
	 * be entirely fuel tanks until the first entirely-glass ring, which is the
	 * outline (radius {@code d + 1}). A mixed or foreign ring, or no outline
	 * within the max radius, invalidates the layer. Returns the radius, {@code -1}
	 * for an invalid layer, or {@code -2} when an unloaded chunk blocks a verdict.
	 */
	private static int scanLayer(Level level, BlockPos center, int maxRadius) {
		for (int d = 1; d < maxRadius; d++) {
			int tank = 0, glass = 0;
			for (int dx = -d; dx <= d; dx++) {
				for (int dz = -d; dz <= d; dz++) {
					if (Math.abs(dx) + Math.abs(dz) != d)
						continue;
					BlockPos p = center.offset(dx, 0, dz);
					if (!level.isLoaded(p))
						return -2;
					BlockState st = level.getBlockState(p);
					if (isFuelTank(st))
						tank++;
					else if (st.is(BlockTags.IMPERMEABLE))
						glass++;
					else
						return -1; // foreign block in the diamond area
				}
			}
			if (glass == 4 * d)
				return d + 1; // outline — inner rings were all tanks by induction
			if (tank == 4 * d)
				continue; // interior extends
			return -1; // mixed ring
		}
		return -1; // no outline within maxRadius
	}

	// ============================ verdict application ============================

	/**
	 * Applies a verdict to the rod's bottom-centre tank block entity: writes the
	 * formed data or clears it, syncing immediately when the state changed.
	 * Uncertain verdicts (unloaded chunks) never touch existing data.
	 */
	private static void apply(Level level, Check check) {
		if (check.uncertain)
			return;
		FuelTankBlockEntity be = partAt(level, check.bottom);
		if (be == null)
			return;
		be.setRodData(check.rod, true);
		if (check.rod == null)
			return;
		// A layer added below the previous bottom moves the holder up the centre
		// column; clear any stale data on the mid-layer centre tanks.
		for (int ly = 1; ly < check.rod.height; ly++) {
			FuelTankBlockEntity mid = partAt(level, check.bottom.above(ly));
			if (mid != null)
				mid.setRodData(null, true);
		}
	}

	// ============================ helpers ============================

	/**
	 * Descends the column at {@code cand} to its bottom fuel tank, probing the
	 * trigger layer and the layers directly above/below it (the trigger's own
	 * layer may be a hole left by a removed centre tank, or the column's rod may
	 * continue above a removed bottom centre). Returns the bottom tank position,
	 * or {@code null} when the column has no fuel tank in the probed layers or an
	 * unloaded chunk blocks a verdict.
	 */
	@Nullable
	private static BlockPos bottomOf(Level level, BlockPos cand, int triggerY) {
		for (int dy = 1; dy >= -1; dy--) {
			BlockPos probe = cand.atY(triggerY + dy);
			if (!level.isLoaded(probe))
				return null;
			if (isFuelTankAt(level, probe)) {
				BlockPos cur = probe;
				while (true) {
					BlockPos below = cur.below();
					if (!level.isLoaded(below))
						return null;
					if (!isFuelTankAt(level, below))
						break;
					cur = below;
				}
				return cur;
			}
		}
		return null;
	}

	@Nullable
	private static FuelTankBlockEntity partAt(Level level, BlockPos pos) {
		if (!level.isLoaded(pos))
			return null;
		BlockEntity be = level.getBlockEntity(pos);
		return be instanceof FuelTankBlockEntity t && !t.isRemoved() ? t : null;
	}

	private static boolean isFuelTankAt(Level level, BlockPos pos) {
		return isFuelTank(level.getBlockState(pos));
	}

	private static boolean isFuelTank(BlockState state) {
		return state.getBlock() instanceof FuelTankBlock;
	}
}
