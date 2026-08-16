package com.iridium126.createmanaindustry.content.fluids.fueltank;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

import org.jetbrains.annotations.Nullable;

import com.iridium126.createmanaindustry.CreateManaIndustry;
import com.iridium126.createmanaindustry.config.ServerConfig;

import net.createmod.catnip.animation.LerpedFloat;
import net.createmod.catnip.animation.LerpedFloat.Chaser;
import net.createmod.catnip.nbt.NBTHelper;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.NbtUtils;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.material.Fluid;

import net.neoforged.neoforge.fluids.FluidStack;

/**
 * Connectivity and fluid simulation for the Molten Salt Fuel Tank.
 * <p>
 * <b>Grouping</b> — a BFS over face-adjacent fuel tanks forms one connected
 * component of any shape (no Create box constraint). The controller is the
 * lexicographically smallest block (Y, then X, then Z); it alone holds the
 * group's fluid and basin state.
 * <p>
 * <b>Basins (watershed)</b> — the tank is decomposed into basins = drainage
 * areas of its local-minimum plateaus ("sinks"). Regions that connect only
 * through a high saddle split into separate basins (e.g. the two legs of an
 * inverted U). Within a basin the liquid surface is flat; fluid flows between
 * basins in spill order — it fills the entry basin first and only crosses a
 * saddle once that basin is full (trapping-rain-water behaviour).
 * <p>
 * <b>Fill</b> seeds from the cell where a pipe attached; basins in that cascade
 * order absorb the new fluid, valleys first and ridge (saddle) basins last — an
 * inverted-U fills its legs before its top bar. <b>Draw</b> mirrors this with the
 * ridges emptied first, then the valleys (the top bar drains before the legs), so
 * the tank's surfaces always sum to the stored total.
 */
public final class FuelTankConnectivity {

	private FuelTankConnectivity() {
	}

	// ============================ group BFS ============================

	/** Whether the block at {@code pos} is a fuel tank. */
	public static boolean isFuelTankAt(Level level, BlockPos pos) {
		return level != null && level.getBlockState(pos).getBlock() instanceof FuelTankBlock;
	}

	@Nullable
	public static FuelTankBlockEntity partAt(BlockEntityType<?> type, BlockGetter level, BlockPos pos) {
		BlockEntity be = level.getBlockEntity(pos);
		if (be instanceof FuelTankBlockEntity tank && be.getType() == type && !be.isRemoved())
			return tank;
		return null;
	}

	/** Whether two fuel tank blocks belong to the same connected group. */
	public static boolean sameGroup(FuelTankBlockEntity a, FuelTankBlockEntity b) {
		if (a == null || b == null)
			return false;
		BlockPos ca = a.getController();
		BlockPos cb = b.getController();
		return ca != null && ca.equals(cb);
	}

	/** Whether the blocks at {@code a} and {@code b} are fuel tanks of the same group. */
	public static boolean isSameGroup(BlockGetter level, BlockPos a, BlockPos b) {
		BlockState state = level.getBlockState(a);
		if (!(state.getBlock() instanceof FuelTankBlock tank))
			return false;
		FuelTankBlockEntity beA = partAt(tank.getBlockEntityType(), level, a);
		FuelTankBlockEntity beB = partAt(tank.getBlockEntityType(), level, b);
		return sameGroup(beA, beB);
	}

	private static Set<BlockPos> findGroup(Level level, BlockPos start, BlockEntityType<?> type, int maxBlocks) {
		Set<BlockPos> visited = new HashSet<>();
		Deque<BlockPos> queue = new ArrayDeque<>();
		visited.add(start);
		queue.add(start);
		while (!queue.isEmpty() && visited.size() < maxBlocks) {
			BlockPos cur = queue.poll();
			for (Direction d : Direction.values()) {
				BlockPos next = cur.relative(d);
				if (visited.contains(next))
					continue;
				if (!isFuelTankAt(level, next))
					continue;
				if (partAt(type, level, next) == null)
					continue;
				visited.add(next);
				queue.add(next);
				if (visited.size() >= maxBlocks)
					break;
			}
		}
		return visited;
	}

	static BlockPos pickController(Set<BlockPos> group) {
		BlockPos best = null;
		for (BlockPos p : group)
			if (best == null || comparePos(p, best) < 0)
				best = p;
		return best;
	}

	private static int comparePos(BlockPos a, BlockPos b) {
		int c = a.getY() - b.getY();
		if (c != 0)
			return c;
		c = a.getX() - b.getX();
		if (c != 0)
			return c;
		return a.getZ() - b.getZ();
	}

	// ============================ connectivity ============================

	/**
	 * Forms or merges the connected group containing {@code be} (a freshly placed
	 * block). Assigns one controller, merges fluid, recomputes basins and updates
	 * the top/bottom blockstate of every part.
	 */
	public static void updateConnectivity(FuelTankBlockEntity be) {
		Level level = be.getLevel();
		if (level == null || level.isClientSide)
			return;
		BlockEntityType<?> type = be.getType();
		Set<BlockPos> group = findGroup(level, be.getBlockPos(), type, ServerConfig.fuelTankMaxBlocks);
		if (group.isEmpty())
			return;

		// Cross-chunk group still loading: do not merge/clamp/recompute. Forming a
		// partial group here would clamp away fluid (applyFluidTankSize) and settle
		// (flatten) the saved per-basin distribution. Wait until the full group has
		// loaded or is confirmed to have shrunk.
		for (BlockPos p : group) {
			FuelTankBlockEntity part = partAt(type, level, p);
			if (part != null && part.hasPendingGroupLoad()) {
				int savedCount = part.savedCount;
				if (group.size() < savedCount && canGroupStillGrow(level, group))
					return;
				break;
			}
		}

		// Existing controllers within the group and their fluids.
		Map<BlockPos, FluidStack> controllerFluids = new LinkedHashMap<>();
		for (BlockPos p : group) {
			FuelTankBlockEntity part = partAt(type, level, p);
			if (part != null && part.isController())
				controllerFluids.put(p.immutable(), part.tankInventory.getFluid());
		}

		// Distinct non-empty fluids — incompatible groups must not merge.
		Set<Fluid> distinct = new HashSet<>();
		for (FluidStack f : controllerFluids.values())
			if (!f.isEmpty())
				distinct.add(f.getFluid());
		if (distinct.size() > 1) {
			// Incompatible fluids in one connected component. Component-level
			// assignment: every fluid-bearing group stays intact and untouched;
			// every empty block (solo block or member of an empty group) joins the
			// first fluid group its face-connected piece meets, scanning neighbours
			// in Direction order. Replaces the old "first neighbour" logic, which
			// assumed a single empty new block: on contraption disassembly next to
			// an incompatible tank it destroyed the incoming fluid (assignGroup
			// clears absorbed parts' tanks) and fragmented empty parts into
			// multiple groups (each part re-ran the bridge once and never
			// converged).
			Map<BlockPos, Set<BlockPos>> fluidGroups = new LinkedHashMap<>();
			for (Map.Entry<BlockPos, FluidStack> e : controllerFluids.entrySet()) {
				if (e.getValue().isEmpty())
					continue;
				BlockPos ctrl = e.getKey();
				if (fluidGroups.containsKey(ctrl))
					continue;
				Set<BlockPos> cells = new HashSet<>();
				findGroupSameController(level, ctrl, ctrl, type, cells);
				if (!cells.isEmpty())
					fluidGroups.put(ctrl, cells);
			}
			Set<BlockPos> fluidCells = new HashSet<>();
			for (Set<BlockPos> cells : fluidGroups.values())
				fluidCells.addAll(cells);
			Set<BlockPos> free = new HashSet<>(group);
			free.removeAll(fluidCells);

			// Resolve each face-connected piece of free cells to one fluid group:
			// BFS from the piece's lexicographically smallest cell, first fluid
			// neighbour in Direction order wins for the whole piece (deterministic).
			Map<BlockPos, List<BlockPos>> freeByTarget = new HashMap<>();
			Set<BlockPos> seen = new HashSet<>();
			for (BlockPos start : free) {
				if (!seen.add(start))
					continue;
				Deque<BlockPos> queue = new ArrayDeque<>();
				queue.add(start);
				List<BlockPos> piece = new ArrayList<>();
				BlockPos target = null;
				while (!queue.isEmpty()) {
					BlockPos cur = queue.poll();
					piece.add(cur);
					for (Direction d : Direction.values()) {
						BlockPos n = cur.relative(d);
						if (fluidCells.contains(n)) {
							FuelTankBlockEntity nPart = partAt(type, level, n);
							if (nPart != null) {
								target = nPart.getController();
								break;
							}
						}
						if (free.contains(n) && seen.add(n))
							queue.add(n);
					}
					if (target != null)
						break;
				}
				if (target == null)
					target = start; // unreachable in a connected component; safe fallback
				freeByTarget.computeIfAbsent(target, k -> new ArrayList<>()).addAll(piece);
			}

			for (Map.Entry<BlockPos, Set<BlockPos>> e : fluidGroups.entrySet()) {
				Set<BlockPos> sub = new HashSet<>(e.getValue());
				List<BlockPos> joined = freeByTarget.get(e.getKey());
				if (joined != null)
					sub.addAll(joined);
				assignGroup(level, type, sub);
			}
			for (Map.Entry<BlockPos, List<BlockPos>> e : freeByTarget.entrySet()) {
				if (fluidGroups.containsKey(e.getKey()))
					continue; // already assigned above
				assignGroup(level, type, new HashSet<>(e.getValue()));
			}
			return;
		}

		// Compatible (or all empty) — merge the whole component.
		assignGroup(level, type, group);
	}

	private static void findGroupSameController(Level level, BlockPos start, BlockPos controllerPos,
			BlockEntityType<?> type, Set<BlockPos> out) {
		Deque<BlockPos> queue = new ArrayDeque<>();
		queue.add(start);
		// Include and validate the seed itself; previously the seed was never added, so
		// split() left it pointing at the removed controller.
		if (out.add(start)) {
			FuelTankBlockEntity part = partAt(type, level, start);
			if (part == null || !controllerPos.equals(part.getController())) {
				out.remove(start);
				return;
			}
		}
		while (!queue.isEmpty()) {
			BlockPos cur = queue.poll();
			for (Direction d : Direction.values()) {
				BlockPos next = cur.relative(d);
				if (!out.add(next))
					continue;
				FuelTankBlockEntity part = partAt(type, level, next);
				if (part == null || !controllerPos.equals(part.getController())) {
					out.remove(next);
					continue;
				}
				queue.add(next);
			}
		}
	}

	/** Assigns every block in {@code group} to one controller and merges its fluid. */
	private static void assignGroup(Level level, BlockEntityType<?> type, Set<BlockPos> group) {
		if (group.isEmpty())
			return;
		BlockPos controllerPos = pickController(group);
		FuelTankBlockEntity controllerBE = partAt(type, level, controllerPos);
		if (controllerBE == null)
			return;

		// Transfer fluid from absorbed controllers into the new controller.
		FluidStack merged = FluidStack.EMPTY;
		for (BlockPos p : group) {
			FuelTankBlockEntity part = partAt(type, level, p);
			if (part == null || part == controllerBE)
				continue;
			FluidStack f = part.tankInventory.getFluid();
			if (!f.isEmpty()) {
				if (merged.isEmpty())
					merged = f.copy();
				else if (FluidStack.isSameFluidSameComponents(merged, f))
					merged.grow(f.getAmount());
				part.tankInventory.setFluid(FluidStack.EMPTY);
			}
			part.tankInventory.setCapacity(FuelTankBlockEntity.getCapacityPerBlock());
		}
		if (!merged.isEmpty())
			controllerBE.tankInventory.setFluid(merged);

		// Assign controller + count to every part.
		int size = 0;
		for (BlockPos p : group) {
			FuelTankBlockEntity part = partAt(type, level, p);
			if (part == null)
				continue;
			part.preventConnectivityUpdate();
			part.setController(controllerPos);
			size++;
		}
		for (BlockPos p : group) {
			FuelTankBlockEntity part = partAt(type, level, p);
			if (part != null)
				part.count = size;
		}
		controllerBE.count = size;
		controllerBE.applyFluidTankSize(size);

		// Basins + blockstates + sync.
		recomputeBasins(controllerBE, group);
		for (BlockPos p : group) {
			FuelTankBlockEntity part = partAt(type, level, p);
			if (part != null)
				part.notifyMultiUpdated();
		}
		controllerBE.sendDataImmediately();
	}

	/**
	 * Splits the group after a block is removed. Finds the connected components of
	 * the remaining blocks, gives each its own controller and distributes the old
	 * group's fluid across them per-block (overflow is lost).
	 *
	 * @param removed the block entity of the removed block (before removal)
	 */
	public static void split(FuelTankBlockEntity removed) {
		Level level = removed.getLevel();
		if (level == null || level.isClientSide)
			return;
		BlockEntityType<?> type = removed.getType();
		BlockPos removedPos = removed.getBlockPos();

		FuelTankBlockEntity oldController = removed.getControllerBE();
		if (oldController == null)
			return;
		BlockPos oldControllerPos = oldController.getController();
		FluidStack fluid = oldController.tankInventory.getFluid().copy();
		int perBlock = FuelTankBlockEntity.getCapacityPerBlock();

		// Basin-preserving distribution: re-derive the exact per-cell amount (mB) from
		// the old controller's basin surfaces, then map each surviving cell's amount
		// into its new component's basins. The removed cell's amount dies with the block
		// (the same loss Create incurs — it reserves one block's worth in the removed
		// part). Falls back to the settled distribution when the old basin data is
		// unavailable (e.g. a cross-chunk group still loading).
		Map<BlockPos, Long> cellAmounts = oldController.basins != null
				? cellAmounts(oldController.basins, perBlock)
				: Map.of();

		// Components among the remaining blocks that belonged to this group.
		List<Set<BlockPos>> components = new ArrayList<>();
		Set<BlockPos> visited = new HashSet<>();
		for (Direction d : Direction.values()) {
			BlockPos seed = removedPos.relative(d);
			if (visited.contains(seed))
				continue;
			FuelTankBlockEntity seedBE = partAt(type, level, seed);
			if (seedBE == null || !oldControllerPos.equals(seedBE.getController()))
				continue;
			Set<BlockPos> comp = new HashSet<>();
			findGroupSameController(level, seed, oldControllerPos, type, comp);
			visited.addAll(comp);
			components.add(comp);
		}
		if (components.isEmpty()) {
			// Nothing left connected — all fluid is lost with the removed block.
			return;
		}

		// Cross-chunk split: the per-cell mapping can only cover loaded parts, so
		// splitting while the group may still grow would silently destroy the fluid
		// stored in the unloaded parts. Defer when the removed block was not the
		// controller (the fluid and its per-basin distribution stay on the old
		// controller; the parts re-form via updateConnectivity once their chunks
		// load — the removed cell's share is conserved too and spreads over the
		// group on the later settle). When the removed block WAS the controller
		// the fluid must leave the dying block entity now, so fall back to the
		// settled distribution: every loaded component gets as much as it can hold
		// from the full amount, and only the overflow beyond their total capacity
		// is lost.
		Set<BlockPos> allComponents = new HashSet<>();
		for (Set<BlockPos> comp : components)
			allComponents.addAll(comp);
		if (oldControllerPos.equals(removedPos)) {
			if (canGroupStillGrow(level, allComponents))
				cellAmounts = Map.of(); // force the settled fallback below
		} else if (canGroupStillGrow(level, allComponents)) {
			// Defer the split entirely: refresh the top/bottom states around the
			// hole now, keep the fluid on the controller, re-form on later loads.
			for (BlockPos p : allComponents) {
				FuelTankBlockEntity part = partAt(type, level, p);
				if (part != null)
					part.notifyMultiUpdated();
			}
			return;
		}

		// Distribute per component, in deterministic order (by controller pos).
		components.sort((a, b) -> comparePos(pickController(a), pickController(b)));
		long remaining = fluid.getAmount();
		for (Set<BlockPos> comp : components) {
			BlockPos cPos = pickController(comp);
			FuelTankBlockEntity cBE = partAt(type, level, cPos);
			if (cBE == null)
				continue;
			int compSize = comp.size();

			// Assign controller + count to every part first (parts keep their fluid empty).
			for (BlockPos p : comp) {
				FuelTankBlockEntity part = partAt(type, level, p);
				if (part == null)
					continue;
				part.preventConnectivityUpdate();
				part.setController(cPos);
				part.count = compSize;
			}
			cBE.count = compSize;
			cBE.tankInventory.setFluid(FluidStack.EMPTY);
			cBE.applyFluidTankSize(compSize);

			// New basin decomposition of this component; the controller carries it directly.
			BasinData data = computeBasins(comp);
			cBE.basins = data;

			if (!cellAmounts.isEmpty()) {
				// Map each surviving cell's amount into its new basin; surfaces derive from
				// the mapped totals so inventory amount == sum of basin volumes exactly.
				long amount = 0;
				long[] basinAmounts = new long[data.basins.size()];
				for (BlockPos p : comp) {
					Long a = cellAmounts.get(p);
					if (a == null)
						continue;
					Integer b = data.basinByCell.get(p);
					if (b != null)
						basinAmounts[b] += a;
					amount += a;
				}
				amount = Math.min(amount, (long) compSize * perBlock);
				if (amount > 0)
					cBE.tankInventory.setFluid(fluid.copyWithAmount((int) amount));
				for (int b = 0; b < basinAmounts.length; b++) {
					Basin basin = data.basins.get(b);
					long cap = basin.cellCount * perBlock;
					data.surfaces[b] = surfaceForVolume(basin, Math.min(basinAmounts[b], cap), perBlock);
				}
			} else {
				// Old basin data unavailable — settled distribution (lowest basins first).
				long share = Math.min(remaining, (long) compSize * perBlock);
				remaining -= share;
				if (share > 0)
					cBE.tankInventory.setFluid(fluid.copyWithAmount((int) share));
				settle(cBE);
			}

			for (BlockPos p : comp) {
				FuelTankBlockEntity part = partAt(type, level, p);
				if (part != null)
					part.notifyMultiUpdated();
			}
			cBE.markBasinsDirty();
			cBE.sendDataImmediately();
		}
	}

	/**
	 * Re-derives the exact fluid amount (mB) in every cell from the controller's basin
	 * surfaces. Full levels contribute {@code perBlock} per cell; a partial level shares
	 * its volume evenly (remainder to earlier cells). The amounts sum exactly to
	 * {@code basinVolume(b, surfaces[b])}, so remapping them into a new decomposition
	 * preserves the stored total precisely.
	 */
	private static Map<BlockPos, Long> cellAmounts(BasinData data, int perBlock) {
		Map<BlockPos, Long> out = new HashMap<>();
		for (Basin b : data.basins) {
			float surface = data.surfaces[b.id];
			for (Map.Entry<Integer, List<BlockPos>> e : b.cellsByLevel.entrySet()) {
				int y = e.getKey();
				List<BlockPos> cells = e.getValue();
				int count = cells.size();
				if (y + 1 <= surface) {
					for (BlockPos p : cells)
						out.put(p, (long) perBlock);
				} else if (y < surface) {
					long total = Math.round(count * perBlock * (surface - y));
					long per = total / count;
					long rem = total % count;
					for (int i = 0; i < count; i++)
						out.put(cells.get(i), per + (i < rem ? 1 : 0));
				}
			}
		}
		return out;
	}

	// ============================ basins ============================

	/** Recomputes the basin decomposition of the controller's group. */
	static void recomputeBasins(FuelTankBlockEntity controller) {
		Level level = controller.getLevel();
		if (level == null || level.isClientSide || !controller.isController())
			return;
		Set<BlockPos> group = findGroup(level, controller.getBlockPos(), controller.getType(),
				ServerConfig.fuelTankMaxBlocks);
		if (group.isEmpty())
			return;
		recomputeBasins(controller, group);
	}

	/**
	 * Recomputes the basin decomposition from an already-known group set, skipping
	 * the second {@link #findGroup} BFS. The group is the exact set the caller
	 * assigned, so the basins always cover the assigned cells — unlike a BFS
	 * restart from the controller, whose truncation can differ from the caller's
	 * when the component exceeds {@code fuelTankMaxBlocks}.
	 */
	static void recomputeBasins(FuelTankBlockEntity controller, Set<BlockPos> group) {
		Level level = controller.getLevel();
		if (level == null || level.isClientSide || !controller.isController())
			return;

		// A cross-chunk group is still loading: keep the saved distribution untouched
		// and retry on a later lazy tick instead of settling (which would flatten it).
		if (controller.savedCount != null) {
			int savedCount = controller.savedCount;
			if (group.size() < savedCount && canGroupStillGrow(level, group)) {
				controller.basins = null;
				return;
			}
			BasinData data = computeBasins(group);
			controller.basins = data;
			if (group.size() == savedCount && controller.savedSurfaces != null
					&& controller.savedBasins != null
					&& controller.savedBasins.basinByCell.keySet().equals(data.basinByCell.keySet())) {
				// Full group present and the shape matches the saved one — restore the
				// saved per-basin surfaces. A same-count different-shape group (modified
				// while the controller's chunk was unloaded) falls through to settle:
				// pasting surfaces by index onto a different decomposition would break
				// the volume invariant.
				data.restoreSurfaces(controller.savedSurfaces);
			} else {
				// Confirmed shrink or shape change (neighbours all loaded) — redistribute
				// the current fluid.
				settle(controller);
			}
			controller.savedSurfaces = null;
			controller.savedCount = null;
			controller.savedBasins = null;
			controller.savedHeight = -1;
			controller.savedMin = null;
			controller.savedMax = null;
			controller.markBasinsDirty();
			return;
		}

		BasinData old = controller.basins;
		BasinData data = computeBasins(group);
		controller.basins = data;
		// Keep the surfaces only when the cell set is identical: the decomposition is
		// deterministic for a given set, so copying by index is safe there. Counting
		// basins alone is not enough — a changed shape can coincidentally keep the same
		// basin count, and reusing the old surfaces would paste them onto new basins.
		if (old != null && old.surfaces != null
				&& old.basinByCell.keySet().equals(data.basinByCell.keySet())) {
			data.restoreSurfaces(old.surfaces);
		} else if (old != null && old.surfaces != null) {
			// Shape changed by adding a block: keep the liquid where it was instead of
			// re-settling it (settle would dump it into the lowest basin and read as the
			// liquid "moving" into a newly attached empty tank). Re-derive the old per-cell
			// amounts and map them into the new basins. Only when every mB maps exactly (a
			// lone group + an empty addition) is this valid; merging two fluid-bearing
			// groups lacks the absorbed group's per-cell distribution and falls back to
			// settle.
			int perBlock = FuelTankBlockEntity.getCapacityPerBlock();
			Map<BlockPos, Long> amounts = cellAmounts(old, perBlock);
			long[] basinAmounts = new long[data.basins.size()];
			long mapped = 0;
			for (Map.Entry<BlockPos, Long> e : amounts.entrySet()) {
				Integer b = data.basinByCell.get(e.getKey());
				if (b == null)
					continue;
				basinAmounts[b] += e.getValue();
				mapped += e.getValue();
			}
			if (mapped == controller.tankInventory.getFluidAmount()) {
				for (int b = 0; b < basinAmounts.length; b++) {
					Basin basin = data.basins.get(b);
					long cap = basin.cellCount * perBlock;
					data.surfaces[b] = surfaceForVolume(basin, Math.min(basinAmounts[b], cap), perBlock);
				}
			} else {
				settle(controller);
			}
		} else {
			// New group or no old distribution — distribute the current fluid in settled order.
			settle(controller);
		}
		controller.markBasinsDirty();
	}

	/**
	 * Whether any neighbour of the group sits in an unloaded chunk, i.e. the group may
	 * still grow once those chunks load. Only checks chunk presence ({@code isLoaded}),
	 * never forces a chunk to load.
	 */
	private static boolean canGroupStillGrow(Level level, Set<BlockPos> group) {
		for (BlockPos p : group)
			for (Direction d : Direction.values()) {
				BlockPos n = p.relative(d);
				if (!level.isLoaded(n))
					return true;
			}
		return false;
	}

	/**
	 * Distribures the controller's current fluid into its basins in "settled"
	 * order — starting from the lowest basin, each filling completely. Used when a
	 * tank loads from NBT with no fill history.
	 */
	public static void settle(FuelTankBlockEntity controller) {
		BasinData data = controller.basins;
		if (data == null)
			return;
		// Each basin starts at its own floor; basin 0 is not necessarily the lowest.
		for (int i = 0; i < data.surfaces.length; i++)
			data.surfaces[i] = data.basins.get(i).minY;
		long total = controller.tankInventory.getFluidAmount();
		fillCascade(data, data.lowestBasin(), total);
		controller.markBasinsDirty();
	}

	/**
	 * Entry point from the fluid handler: re-derives the per-basin surfaces after
	 * the tank's total fluid changed by {@code delta} mB, seeded by the cell the
	 * pipe attached to.
	 */
	public static void applyDelta(Level level, FuelTankBlockEntity controller, BlockPos seedCell, int delta) {
		if (level == null || level.isClientSide || delta == 0)
			return;
		if (controller == null || !controller.isController())
			return;
		BasinData data = controller.basins;
		if (data == null) {
			recomputeBasins(controller);
			data = controller.basins;
			if (data == null)
				return;
		}
		Integer seedBasin = data.basinByCell.get(seedCell);
		if (seedBasin == null)
			seedBasin = data.lowestBasin();
		// The cascade covers the whole connected basin graph, so a non-zero leftover
		// indicates the graph is no longer volume-preserving (e.g. a future refactor
		// disconnects it). Surface it in the log instead of silently losing fluid.
		if (delta > 0) {
			long leftover = fillCascade(data, seedBasin, delta);
			if (leftover > 0)
				CreateManaIndustry.LOGGER.warn("Fuel tank @ {} fill left {} mB undelivered (basin graph underfilled)",
						controller.getBlockPos(), leftover);
		} else {
			long leftover = drainCascade(data, seedBasin, -delta);
			if (leftover > 0)
				CreateManaIndustry.LOGGER.warn("Fuel tank @ {} drain left {} mB unremoved",
						controller.getBlockPos(), leftover);
		}
		controller.markSurfacesDirty();
		controller.sendData();
		controller.setChanged();
	}

	// ---- fill / drain cascade ------------------------------------------------

	/**
	 * Whether the current per-basin surfaces represent exactly {@code amount} mB.
	 * Lets the contraption sync keep the dynamic distribution instead of re-settling
	 * it when the total has not actually changed.
	 */
	public static boolean surfacesRepresent(FuelTankBlockEntity controller, long amount) {
		BasinData data = controller.basins;
		if (data == null)
			return false;
		int perBlock = FuelTankBlockEntity.getCapacityPerBlock();
		long vol = 0;
		for (int i = 0; i < data.basins.size(); i++)
			vol += basinVolume(data.basins.get(i), data.surfaces[i], perBlock);
		return vol == amount;
	}

	private static long fillCascade(BasinData data, int seedBasin, long mbar) {
		long leftover = fillWater(data, seedBasin, mbar);
		rebalanceOverSpill(data);
		return leftover;
	}

	private static long drainCascade(BasinData data, int seedBasin, long mbar) {
		long leftover = drainWater(data, seedBasin, mbar);
		rebalanceOverSpill(data);
		return leftover;
	}

	/**
	 * Connected-vessel equalization: any liquid sitting above a saddle while the far side is
	 * dry (below the saddle) spills across until the level drops to the saddle or the dry side
	 * fills up to it. Iterates until no such boundary remains — a tall pillar beside a low
	 * wide basin levels out as real water would, instead of leaving the level hovering above a
	 * dry neighbour.
	 */
	private static void rebalanceOverSpill(BasinData data) {
		int n = data.basins.size();
		int perBlock = FuelTankBlockEntity.getCapacityPerBlock();
		boolean changed = true;
		int guard = 0;
		while (changed && guard++ < n * 4) {
			changed = false;
			for (int a = 0; a < n; a++) {
				for (int b : data.adjacency[a]) {
					int s = data.adjHeight[a][b];
					if (data.surfaces[a] <= s || data.surfaces[b] >= s)
						continue;
					long aVol = basinVolume(data.basins.get(a), data.surfaces[a], perBlock)
							- basinVolume(data.basins.get(a), s, perBlock);
					long bCap = basinVolume(data.basins.get(b), s, perBlock)
							- basinVolume(data.basins.get(b), data.surfaces[b], perBlock);
					long move = Math.min(aVol, bCap);
					if (move <= 0)
						continue;
					data.surfaces[a] = surfaceForVolume(data.basins.get(a),
							basinVolume(data.basins.get(a), data.surfaces[a], perBlock) - move, perBlock);
					data.surfaces[b] = surfaceForVolume(data.basins.get(b),
							basinVolume(data.basins.get(b), data.surfaces[b], perBlock) + move, perBlock);
					changed = true;
				}
			}
		}
		if (changed)
			CreateManaIndustry.LOGGER.warn("Fuel tank @ {} rebalanceOverSpill stopped after {} passes "
					+ "(guard {}), an unbalanced boundary remains above a saddle",
					data.minCell, guard, n * 4);
	}

	// ---- connected-vessel water-level fill / drain ---------------------------

	/**
	 * Fills {@code mbar} mB from {@code seedBasin} following connected-vessel physics: the
	 * water level rises from the injection basin, submerging basin saddles in height order,
	 * and every basin whose saddle the level covers shares one flat level (water pressure).
	 * Returns the amount that could not be placed (0 while the basin graph is
	 * volume-preserving).
	 */
	private static long fillWater(BasinData data, int seedBasin, long mbar) {
		long remaining = mbar;
		if (remaining <= 0)
			return 0;
		int perBlock = FuelTankBlockEntity.getCapacityPerBlock();
		boolean[] in = new boolean[data.basins.size()];
		in[seedBasin] = true;
		// Basins already connected through a saddle both sides currently cover share the
		// level (pre-existing connected liquid).
		expandRegion(data, in, -1);
		// Raise the whole region to the highest surface already present (top-up).
		float level = maxSurface(data, in);
		long need = raiseNeed(data, in, level, perBlock);
		if (need > 0) {
			if (remaining < need) {
				raiseTo(data, in, findFillHeight(data, in, level, remaining, perBlock));
				return 0;
			}
			remaining -= need;
			raiseTo(data, in, level);
		}
		while (remaining > 0) {
			// Submerge new neighbours whose saddle the reached level now covers. A basin
			// joins only once the source basin's own surface has submerged the saddle, so
			// the fill spreads basin by basin instead of flooding the whole shape at once.
			expandRegion(data, in, (int) level);
			// Newly joined basins sit at the current level — top them up to it before
			// looking for the next event, else a full basin's surplus would be left over
			// (e.g. a full crossbar spilling into legs already at the level).
			need = raiseNeed(data, in, level, perBlock);
			if (need > 0) {
				if (remaining < need) {
					raiseTo(data, in, findFillHeight(data, in, level, remaining, perBlock));
					return 0;
				}
				remaining -= need;
				raiseTo(data, in, level);
			}
			// Next event: a region basin with room fills, or a saddle spills into a neighbour.
			float next = Float.MAX_VALUE;
			for (int b = 0; b < data.basins.size(); b++) {
				if (!in[b])
					continue;
				float top = data.basins.get(b).maxY + 1f;
				if (top > level)
					next = Math.min(next, top);
				for (int nb : data.adjacency[b])
					if (!in[nb] && data.adjHeight[b][nb] > level)
						next = Math.min(next, data.adjHeight[b][nb]);
			}
			if (next == Float.MAX_VALUE || next <= level)
				break;   // nothing more can rise; excess stays with the caller
			need = raiseNeed(data, in, next, perBlock);
			if (remaining < need) {
				raiseTo(data, in, findFillHeight(data, in, next, remaining, perBlock));
				return 0;
			}
			remaining -= need;
			raiseTo(data, in, next);
			level = next;
		}
		return remaining;
	}

	/**
	 * Drains {@code mbar} mB from {@code seedBasin} following connected-vessel physics: the
	 * shared level of the connected region drops; basins empty as the level passes their
	 * floors, and a neighbour whose saddle is exposed joins only while it still holds
	 * liquid above it. Returns the amount that could not be removed.
	 */
	private static long drainWater(BasinData data, int seedBasin, long mbar) {
		long remaining = mbar;
		if (remaining <= 0)
			return 0;
		int perBlock = FuelTankBlockEntity.getCapacityPerBlock();
		boolean[] in = new boolean[data.basins.size()];
		in[seedBasin] = true;
		expandRegion(data, in, -1);   // connected through saddles both sides cover
		float level = maxSurface(data, in);
		// Bring everything above the shared level down to it first.
		long drain = drainNeed(data, in, level, perBlock);
		if (drain > 0) {
			if (remaining < drain) {
				lowerTo(data, in, findDrainHeight(data, in, level, remaining, perBlock));
				return 0;
			}
			remaining -= drain;
			lowerTo(data, in, level);
		}
		while (remaining > 0) {
			// Next event: a not-yet-empty region basin empties, or the level reaches a
			// saddle below which an outside basin still holds liquid (it joins and supplies).
			// NEGATIVE_INFINITY sentinel: no event found (real event heights are finite).
			float next = Float.NEGATIVE_INFINITY;
			for (int b = 0; b < data.basins.size(); b++) {
				if (!in[b])
					continue;
				if (data.surfaces[b] > data.basins.get(b).minY)
					next = Math.max(next, data.basins.get(b).minY);
				for (int nb : data.adjacency[b])
					if (!in[nb] && data.surfaces[nb] > data.adjHeight[b][nb] && data.adjHeight[b][nb] < level)
						next = Math.max(next, data.adjHeight[b][nb]);
			}
			if (next == Float.NEGATIVE_INFINITY || next >= level)
				break;   // nothing more can drain
			drain = drainNeed(data, in, next, perBlock);
			if (remaining < drain) {
				lowerTo(data, in, findDrainHeight(data, in, level, remaining, perBlock));
				return 0;
			}
			remaining -= drain;
			lowerTo(data, in, next);
			level = next;
			// Recompute the connected region: a basin whose saddle is now above its level
			// disconnects (it can no longer supply); a basin still holding liquid above an
			// exposed saddle joins and supplies the extraction point.
			in = new boolean[data.basins.size()];
			in[seedBasin] = true;
			expandRegion(data, in, -1);
		}
		// The seed's connected region is empty, but fluid may remain in basins whose level
		// sits below a saddle (a siphon limit on the connected region). The tank must be
		// drainable from any extraction point, so pull the rest from the remaining basins —
		// highest current surface first — until everything is empty or the requested amount
		// is satisfied. `rebalanceOverSpill` (called by the caller) then lets liquid that
		// ended up above a saddle spill back down, keeping surfaces consistent.
		List<Integer> order = new ArrayList<>();
		for (int b = 0; b < data.basins.size(); b++)
			if (data.surfaces[b] > data.basins.get(b).minY)
				order.add(b);
		order.sort((a, b) -> Float.compare(data.surfaces[b], data.surfaces[a]));
		for (int b : order) {
			if (remaining <= 0)
				break;
			float top = data.surfaces[b];
			long vol = basinVolume(data.basins.get(b), top, perBlock)
					- basinVolume(data.basins.get(b), data.basins.get(b).minY, perBlock);
			if (remaining >= vol) {
				remaining -= vol;
				data.surfaces[b] = data.basins.get(b).minY;
			} else {
				data.surfaces[b] = surfaceForVolume(data.basins.get(b),
						basinVolume(data.basins.get(b), top, perBlock) - remaining, perBlock);
				remaining = 0;
			}
		}
		return remaining;
	}

	/** Adds to {@code in} every basin reachable from a member via a saddle the source basin's
	 * own surface has submerged (at {@code level}, or both current surfaces when {@code level < 0}).
	 * During a fill, an empty basin (surface on its own floor) does not submerge a saddle yet —
	 * it has no liquid to cross — so it does not propagate. */
	private static void expandRegion(BasinData data, boolean[] in, int level) {
		boolean changed = true;
		while (changed) {
			changed = false;
			for (int b = 0; b < data.basins.size(); b++) {
				if (!in[b])
					continue;
				for (int nb : data.adjacency[b]) {
					if (in[nb])
						continue;
					int s = data.adjHeight[b][nb];
					boolean join = level < 0
							? s <= Math.min(data.surfaces[b], data.surfaces[nb])
							: data.surfaces[b] > data.basins.get(b).minY
									&& s <= Math.min(data.surfaces[b], level);
					if (join) {
						in[nb] = true;
						changed = true;
					}
				}
			}
		}
	}

	private static float maxSurface(BasinData data, boolean[] in) {
		// NEGATIVE_INFINITY, not Float.MIN_VALUE (the smallest *positive* float):
		// every basin surface is finite, so the max is always a real surface even
		// for underground tanks whose surfaces are all negative (with MIN_VALUE the
		// level would be ~0 — a full level for an underground tank only by
		// coincidence).
		float m = Float.NEGATIVE_INFINITY;
		for (int b = 0; b < data.basins.size(); b++)
			if (in[b])
				m = Math.max(m, data.surfaces[b]);
		return m;
	}

	private static long raiseNeed(BasinData data, boolean[] in, float target, int perBlock) {
		long need = 0;
		for (int b = 0; b < data.basins.size(); b++)
			if (in[b] && data.surfaces[b] < target)
				need += basinVolume(data.basins.get(b), target, perBlock)
						- basinVolume(data.basins.get(b), data.surfaces[b], perBlock);
		return need;
	}

	private static long drainNeed(BasinData data, boolean[] in, float target, int perBlock) {
		long need = 0;
		for (int b = 0; b < data.basins.size(); b++)
			if (in[b] && data.surfaces[b] > target)
				need += basinVolume(data.basins.get(b), data.surfaces[b], perBlock)
						- basinVolume(data.basins.get(b), target, perBlock);
		return need;
	}

	private static void raiseTo(BasinData data, boolean[] in, float target) {
		for (int b = 0; b < data.basins.size(); b++)
			if (in[b] && data.surfaces[b] < target)
				data.surfaces[b] = Math.min(target, data.basins.get(b).maxY + 1f);
	}

	private static void lowerTo(BasinData data, boolean[] in, float target) {
		for (int b = 0; b < data.basins.size(); b++)
			if (in[b] && data.surfaces[b] > target)
				data.surfaces[b] = Math.max(target, data.basins.get(b).minY);
	}

	/** Highest level reachable by raising every region basin from its surface with {@code mbar} mB. */
	private static float findFillHeight(BasinData data, boolean[] in, float target, long mbar, int perBlock) {
		float lo = Float.MAX_VALUE, hi = target;
		for (int b = 0; b < data.basins.size(); b++)
			if (in[b])
				lo = Math.min(lo, data.surfaces[b]);
		float best = lo;
		for (int i = 0; i < 48; i++) {
			float mid = (lo + hi) / 2;
			if (raiseNeed(data, in, mid, perBlock) <= mbar) {
				best = mid;
				lo = mid;
			} else {
				hi = mid;
			}
		}
		return best;
	}

	/** Lowest level reachable by lowering every region basin from its surface by {@code mbar} mB. */
	private static float findDrainHeight(BasinData data, boolean[] in, float fromLevel, long mbar, int perBlock) {
		float lo = Float.MAX_VALUE, hi = fromLevel;
		for (int b = 0; b < data.basins.size(); b++)
			if (in[b])
				lo = Math.min(lo, data.basins.get(b).minY);
		float best = hi;
		for (int i = 0; i < 48; i++) {
			float mid = (lo + hi) / 2;
			if (drainNeed(data, in, mid, perBlock) <= mbar) {
				best = mid;
				hi = mid;
			} else {
				lo = mid;
			}
		}
		return best;
	}

	private static long basinVolume(Basin b, float surface, int perBlock) {
		int[] ys = b.ensureVolumeCache(perBlock);
		// Full levels: y + 1 <= surface. The float comparison is kept verbatim — a
		// transformed integer/ceil predicate would flip on near-integral surfaces
		// (e.g. 4.0000001f, which the binary-search heights can produce).
		int lo = 0, hi = ys.length;
		while (lo < hi) {
			int mid = (lo + hi) >>> 1;
			if (ys[mid] + 1 <= surface)
				lo = mid + 1;
			else
				hi = mid;
		}
		long vol = b.prefixCaps[lo];
		// Partial level: y = floor(surface) when the surface is not exactly integral
		// (same float promotion as the original `y < surface`; the level at
		// floor(surface) then always satisfies y + 1 > surface, so it is never full).
		int py = (int) Math.floor(surface);
		if (py < surface) {
			int idx = Arrays.binarySearch(ys, py);
			if (idx >= 0)
				vol += Math.round((float) b.levelCaps[idx] * (surface - (float) py));
		}
		return vol;
	}

	private static float surfaceForVolume(Basin b, long targetVol, int perBlock) {
		int[] ys = b.ensureVolumeCache(perBlock);
		long[] prefix = b.prefixCaps;
		long total = prefix[ys.length];
		if (total < targetVol)
			return b.maxY + 1; // matches the original linear walk falling through
		// First level whose cumulative capacity reaches targetVol (prefix strictly
		// ascending: every level holds at least one block's worth).
		int lo = 0, hi = ys.length - 1, k = 0;
		while (lo <= hi) {
			int mid = (lo + hi) >>> 1;
			if (prefix[mid + 1] >= targetVol) {
				k = mid;
				hi = mid - 1;
			} else {
				lo = mid + 1;
			}
		}
		float frac = (float) (targetVol - prefix[k]) / (float) b.levelCaps[k];
		return ys[k] + Math.min(1f, Math.max(0f, frac));
	}

	// ============================ decomposition ============================

	/**
	 * Watershed decomposition of a fuel-tank group.
	 * <p>
	 * Sinks = connected plateaus of local-minimum cells (no face-neighbour lower).
	 * Every cell drains (via a strictly-descending path) to a sink; basins are the
	 * drainage areas. A basin whose cells are all local minima is a "ridge" (a
	 * saddle — e.g. the top bar of an inverted U) and flows into whichever
	 * neighbour is drained; other basins are valleys and stay sealed.
	 * <p>
	 * A final "saddle truncation" cuts each valley one block below an adjacent
	 * ridge: every cell at or above the ridge's height that sits on the ridge's
	 * shelf moves into the ridge basin, so an inverted-U's legs span
	 * {@code y0..h-1} and its top bar occupies {@code y = h} instead of the legs
	 * climbing up to the highest block.
	 */
	static BasinData computeBasins(Set<BlockPos> cells) {
		BasinData data = new BasinData();
		data.totalCells = cells.size();
		data.minCell = null;
		data.maxCell = null;
		for (BlockPos p : cells) {
			if (data.minCell == null || comparePos(p, data.minCell) < 0)
				data.minCell = p;
			if (data.maxCell == null || comparePos(p, data.maxCell) > 0)
				data.maxCell = p;
		}

		int n = cells.size();
		// Deterministic iteration order: the input is a HashSet whose iteration follows
		// insertion order (BFS visit order, which depends on how the tank was built).
		// Basin ids and the fill/drain tie-breaks derive from this array's order, so
		// sorting makes the whole decomposition a pure function of the shape — the fill
		// and drain order is then decided by shape + injection/extraction position only.
		BlockPos[] arr = cells.toArray(new BlockPos[0]);
		Arrays.sort(arr, (a, b) -> comparePos(a, b));
		Map<BlockPos, Integer> index = new HashMap<>();
		for (int i = 0; i < n; i++)
			index.put(arr[i], i);

		// union-find over cells
		int[] parent = new int[n];
		for (int i = 0; i < n; i++)
			parent[i] = i;
		int[] find = new int[n];
		for (int i = 0; i < n; i++)
			find[i] = i;
		java.util.function.IntUnaryOperator findOp = i -> {
			int root = i;
			while (find[root] != root)
				root = find[root];
			while (find[i] != i) {
				int next = find[i];
				find[i] = root;
				i = next;
			}
			return root;
		};
		java.util.function.BiConsumer<Integer, Integer> union = (a, b) -> {
			int ra = findOp.applyAsInt(a), rb = findOp.applyAsInt(b);
			if (ra != rb)
				find[rb] = ra;
		};

		// 1. local minima
		Set<Integer> minima = new HashSet<>();
		for (int i = 0; i < n; i++) {
			BlockPos p = arr[i];
			boolean isMin = true;
			for (Direction d : Direction.values()) {
				Integer j = index.get(p.relative(d));
				if (j != null && arr[j].getY() < p.getY()) {
					isMin = false;
					break;
				}
			}
			if (isMin)
				minima.add(i);
		}
		// union equal-height adjacent minima into plateaus
		for (int i : minima) {
			BlockPos p = arr[i];
			for (Direction d : Direction.values()) {
				Integer j = index.get(p.relative(d));
				if (j != null && minima.contains(j) && arr[j].getY() == p.getY())
					union.accept(i, j);
			}
		}
		Map<Integer, Integer> sinkRootToBasin = new HashMap<>();
		for (int i : minima)
			sinkRootToBasin.computeIfAbsent(findOp.applyAsInt(i), k -> sinkRootToBasin.size());

		// 2. assign every cell to the sink it drains to
		int[] basinOfCell = new int[n];
		boolean[] resolved = new boolean[n];
		for (int i = 0; i < n; i++) {
			List<Integer> path = new ArrayList<>();
			int cur = i;
			while (true) {
				if (resolved[cur]) {
					int b = basinOfCell[cur];
					for (int p : path)
						basinOfCell[p] = b;
					for (int p : path)
						resolved[p] = true;
					break;
				}
				if (minima.contains(cur)) {
					int b = sinkRootToBasin.get(findOp.applyAsInt(cur));
					basinOfCell[cur] = b;
					resolved[cur] = true;
					for (int p : path)
						basinOfCell[p] = b;
					for (int p : path)
						resolved[p] = true;
					break;
				}
				path.add(cur);
				int best = -1;
				for (Direction d : Direction.values()) {
					Integer j = index.get(arr[cur].relative(d));
					if (j == null || arr[j].getY() >= arr[cur].getY())
						continue;
					if (best == -1 || arr[j].getY() < arr[best].getY()
							|| (arr[j].getY() == arr[best].getY() && comparePos(arr[j], arr[best]) < 0))
						best = j;
				}
				if (best == -1) {
					// unreachable; treat as its own sink
					int b = sinkRootToBasin.size();
					sinkRootToBasin.put(cur, b);
					minima.add(cur);
					basinOfCell[cur] = b;
					resolved[cur] = true;
					for (int p : path)
						basinOfCell[p] = b;
					for (int p : path)
						resolved[p] = true;
					break;
				}
				cur = best;
			}
		}

		// 3. build Basin objects
		Map<Integer, Basin> byId = new LinkedHashMap<>();
		for (int i = 0; i < n; i++) {
			int b = basinOfCell[i];
			Basin basin = byId.computeIfAbsent(b, k -> new Basin());
			basin.cells.add(arr[i]);
			basin.cellsByLevel.computeIfAbsent(arr[i].getY(), k -> new ArrayList<>()).add(arr[i]);
			basin.minY = Math.min(basin.minY, arr[i].getY());
			basin.maxY = Math.max(basin.maxY, arr[i].getY());
			basin.cellCount++;
		}
		int basinCount = byId.size();
		Basin[] basinArr = new Basin[basinCount];
		int bi = 0;
		for (Basin basin : byId.values()) {
			basin.id = bi;
			basinArr[bi] = basin;
			// ridge = every cell is a local minimum (no drainage from above)
			boolean ridge = true;
			for (BlockPos p : basin.cells) {
				if (!minima.contains(index.get(p))) {
					ridge = false;
					break;
				}
			}
			basin.ridge = ridge;
			bi++;
		}
		data.basins.addAll(Arrays.asList(basinArr));

		// 3.5 shelf merge — a local-minimum basin is a true saddle only when it touches
		// >= 2 basins AND sits at or above every neighbour's highest cell (a "peak"
		// separating them: inverted-U top bar, table top, ring's top bar). Otherwise it is
		// a dead-end or mid-level "shelf" (a tree branch, a T arm, a mid-height plate that
		// neighbours extend above) and merges into the adjacent basin with the lowest
		// base, so same-level connected cells share one flat surface.
		boolean[] allMin = new boolean[basinCount];
		int[] basinMinY = new int[basinCount];
		int[] basinMaxY = new int[basinCount];
		for (int i = 0; i < basinCount; i++) {
			allMin[i] = data.basins.get(i).ridge;
			basinMinY[i] = Integer.MAX_VALUE;
			basinMaxY[i] = Integer.MIN_VALUE;
		}
		for (int i = 0; i < n; i++) {
			int b = basinOfCell[i];
			basinMinY[b] = Math.min(basinMinY[b], arr[i].getY());
			basinMaxY[b] = Math.max(basinMaxY[b], arr[i].getY());
		}
		@SuppressWarnings("unchecked")
		Set<Integer>[] nbors = new Set[basinCount];
		for (int i = 0; i < basinCount; i++)
			nbors[i] = new HashSet<>();
		for (int i = 0; i < n; i++) {
			int bSrc = basinOfCell[i];
			for (Direction d : Direction.values()) {
				Integer j = index.get(arr[i].relative(d));
				if (j == null)
					continue;
				int bDst = basinOfCell[j];
				if (bDst != bSrc) {
					nbors[bSrc].add(bDst);
					nbors[bDst].add(bSrc);
				}
			}
		}
		boolean[] isSaddle = new boolean[basinCount];
		for (int b = 0; b < basinCount; b++)
			isSaddle[b] = allMin[b] && nbors[b].size() >= 2;
		boolean merged;
		do {
			merged = false;
			for (int b = 0; b < basinCount; b++) {
				if (!allMin[b] || isSaddle[b] || nbors[b].isEmpty())
					continue;
				int target = -1;
				for (int nb : nbors[b])
					if (target == -1 || basinMinY[nb] < basinMinY[target]
							|| (basinMinY[nb] == basinMinY[target] && nb < target))
						target = nb;
				for (int i = 0; i < n; i++)
					if (basinOfCell[i] == b)
						basinOfCell[i] = target;
				basinMinY[target] = Math.min(basinMinY[target], basinMinY[b]);
				basinMaxY[target] = Math.max(basinMaxY[target], basinMaxY[b]);
				nbors[target].remove(b);
				nbors[b].clear();
				allMin[b] = false;
				merged = true;
			}
		} while (merged);

		// 4. saddle truncation — every cell at or above an adjacent saddle's height merges
		// into the *highest* such saddle (the top bars claim the leg tops before the lower
		// plate claims the chimneys; independent of iteration order). This cuts columns
		// that pass through a saddle level — a leg below an inverted-U bar keeps only its
		// part beneath it, the cells above (chimneys / the vessel) join the saddle.
		int guard = 0;
		boolean moved;
		do {
			moved = false;
			for (int i = 0; i < n; i++) {
				int src = basinOfCell[i];
				if (isSaddle[src])
					continue; // ridge cells never leave the ridge
				int bestDst = -1;
				int bestH = Integer.MIN_VALUE;
				for (Direction d : Direction.values()) {
					Integer j = index.get(arr[i].relative(d));
					if (j == null)
						continue;
					int dst = basinOfCell[j];
					if (dst == src || !isSaddle[dst])
						continue; // only move into an adjacent ridge
					int h = basinMaxY[dst];
					if (arr[i].getY() >= h && h > bestH) {
						bestH = h;
						bestDst = dst;
					}
				}
				if (bestDst != -1) {
					basinOfCell[i] = bestDst;
					moved = true;
				}
			}
		} while (moved && guard++ < 1024);

		// 4.5 merge face-adjacent ridge basins at the same level — the watershed can
		// split one saddle plateau around cells that are not local minima (the two top
		// bars of a 3-leg tank meet through a leg top); the truncation reconnects them,
		// so the pieces merge back into a single ridge.
		for (int i = 0; i < basinCount; i++)
			nbors[i].clear();
		for (int i = 0; i < n; i++) {
			int bSrc = basinOfCell[i];
			for (Direction d : Direction.values()) {
				Integer j = index.get(arr[i].relative(d));
				if (j == null)
					continue;
				int bDst = basinOfCell[j];
				if (bDst != bSrc) {
					nbors[bSrc].add(bDst);
					nbors[bDst].add(bSrc);
				}
			}
		}
		boolean ridgeMerged;
		do {
			ridgeMerged = false;
			for (int a = 0; a < basinCount; a++) {
				if (!isSaddle[a])
					continue;
				for (int b : new ArrayList<>(nbors[a])) {
					if (!isSaddle[b] || b <= a || basinMaxY[a] != basinMaxY[b])
						continue;
					for (int i = 0; i < n; i++)
						if (basinOfCell[i] == b)
							basinOfCell[i] = a;
					for (int nb : nbors[b]) {
						nbors[nb].remove(b);
						nbors[nb].add(a);
					}
					nbors[a].remove(b);
					nbors[a].addAll(nbors[b]);
					nbors[b].clear();
					isSaddle[b] = false;
					ridgeMerged = true;
					break;
				}
			}
		} while (ridgeMerged);

		// 5. re-decompose valleys by (original basin id, connected component): a saddle
		// can cut a column apart (an asymmetric inverted-U tall leg becomes the part below
		// the bar and the chimney above it), so each disconnected piece becomes its own
		// basin; adjacent but distinct basins never merge. Saddles keep a single id each.
		int saddleCount = 0;
		Map<Integer, Integer> saddleNewId = new HashMap<>();
		for (int b = 0; b < basinCount; b++)
			if (isSaddle[b])
				saddleNewId.put(b, saddleCount++);
		int[] compId = new int[n];
		Arrays.fill(compId, -1);
		int nextId = saddleCount;
		for (int i = 0; i < n; i++) {
			if (isSaddle[basinOfCell[i]] || compId[i] != -1)
				continue;
			int myId = basinOfCell[i];
			Deque<Integer> queue = new ArrayDeque<>();
			queue.add(i);
			compId[i] = nextId;
			int cid = nextId++;
			while (!queue.isEmpty()) {
				int cur = queue.poll();
				for (Direction d : Direction.values()) {
					Integer j = index.get(arr[cur].relative(d));
					if (j == null)
						continue;
					int jb = basinOfCell[j];
					if (isSaddle[jb] || compId[j] != -1 || jb != myId)
						continue;
					compId[j] = cid;
					queue.add(j);
				}
			}
		}
		// final assignment
		for (int i = 0; i < n; i++)
			basinOfCell[i] = isSaddle[basinOfCell[i]] ? saddleNewId.get(basinOfCell[i]) : compId[i];
		int finalCount = nextId;

		// rebuild basins
		final int numSaddles = saddleCount;
		Map<Integer, Basin> newById = new LinkedHashMap<>();
		for (int i = 0; i < n; i++) {
			int b = basinOfCell[i];
			Basin nb = newById.computeIfAbsent(b, k -> {
				Basin b2 = new Basin();
				b2.id = k;
				b2.ridge = k < numSaddles;
				return b2;
			});
			nb.cells.add(arr[i]);
			nb.cellsByLevel.computeIfAbsent(arr[i].getY(), k2 -> new ArrayList<>()).add(arr[i]);
			nb.minY = Math.min(nb.minY, arr[i].getY());
			nb.maxY = Math.max(nb.maxY, arr[i].getY());
			nb.cellCount++;
		}
		Basin[] basinArr2 = new Basin[finalCount];
		for (Map.Entry<Integer, Basin> e : newById.entrySet())
			basinArr2[e.getKey()] = e.getValue();
		data.basins.clear();
		data.basins.addAll(Arrays.asList(basinArr2));

		// basinByCell
		data.basinByCell.clear();
		for (int i = 0; i < n; i++)
			data.basinByCell.put(arr[i], basinOfCell[i]);

		// adjacency
		@SuppressWarnings("unchecked")
		Set<Integer>[] adj = new Set[finalCount];
		for (int i = 0; i < finalCount; i++)
			adj[i] = new HashSet<>();
		for (int i = 0; i < n; i++) {
			int bii = basinOfCell[i];
			for (Direction d : Direction.values()) {
				Integer j = index.get(arr[i].relative(d));
				if (j == null)
					continue;
				int bj = basinOfCell[j];
				if (bii != bj) {
					adj[bii].add(bj);
					adj[bj].add(bii);
				}
			}
		}
		data.adjacency = new int[finalCount][];
		for (int i = 0; i < finalCount; i++) {
			int[] arr2 = adj[i].stream().mapToInt(Integer::intValue).toArray();
			Arrays.sort(arr2);
			data.adjacency[i] = arr2;
		}

		// Spill heights: for each adjacent pair, the lowest boundary height at which
		// liquid crosses (max of the two touching cells' Y). The fill/drain water-level
		// scan submerges a saddle at this height and the neighbour joins the region.
		data.adjHeight = new int[finalCount][finalCount];
		for (int i = 0; i < finalCount; i++)
			Arrays.fill(data.adjHeight[i], Integer.MAX_VALUE);
		for (int i = 0; i < n; i++) {
			int bii = basinOfCell[i];
			for (Direction d : Direction.values()) {
				Integer j = index.get(arr[i].relative(d));
				if (j == null)
					continue;
				int bj = basinOfCell[j];
				if (bii != bj) {
					int h = Math.max(arr[i].getY(), arr[j].getY());
					if (h < data.adjHeight[bii][bj]) {
						data.adjHeight[bii][bj] = h;
						data.adjHeight[bj][bii] = h;
					}
				}
			}
		}

		// A ridge is only a true saddle when it actually separates >= 2 basins; a
		// single-basin group (or a ridge demoted by a shelf merge) is just a valley.
		for (Basin b : data.basins)
			if (b.ridge && data.adjacency[b.id].length < 2)
				b.ridge = false;

		data.surfaces = new float[finalCount];
		return data;
	}

	// ============================ data classes ============================

	/** Per-group basin state; lives on the controller (server: authoritative, client: render data). */
	public static final class BasinData {
		public final Map<BlockPos, Integer> basinByCell = new HashMap<>();
		public final List<Basin> basins = new ArrayList<>();
		public int[][] adjacency;
		/**
		 * Per-pair spill height: the lowest height at which liquid crosses from basin
		 * {@code a} to {@code b} (min over the shared boundary of {@code max(p.y, q.y)}).
		 * The dynamic-water fill/drain algorithm submerges a saddle when the level reaches
		 * this height, so the neighbour joins the connected region (real connected-vessel
		 * behaviour).
		 */
		public int[][] adjHeight;
		public float[] surfaces;
		public BlockPos minCell;
		public BlockPos maxCell;
		public int totalCells;
		/** Client-side per-basin animation chasers (transient). */
		public transient LerpedFloat[] chasers;
		/** Client-side merged-geometry cache (transient; held by the renderer). */
		public transient Object renderCache;

		public BlockPos minCell() {
			return minCell;
		}

		public BlockPos maxCell() {
			return maxCell;
		}

		public void restoreSurfaces(float[] saved) {
			if (saved == null)
				return;
			for (int i = 0; i < Math.min(saved.length, surfaces.length); i++)
				surfaces[i] = saved[i];
		}

		public int lowestBasin() {
			int best = 0;
			for (int i = 1; i < basins.size(); i++) {
				Basin a = basins.get(i), b = basins.get(best);
				if (a.minY < b.minY || (a.minY == b.minY && a.id < b.id))
					best = i;
			}
			return best;
		}

		public void initChasers(float[] initial) {
			chasers = new LerpedFloat[basins.size()];
			for (int i = 0; i < chasers.length; i++) {
				float start = initial != null && i < initial.length ? initial[i] : 0f;
				chasers[i] = LerpedFloat.linear().startWithValue(start);
			}
		}

		public void tickChasers() {
			if (chasers == null)
				return;
			for (int i = 0; i < chasers.length; i++) {
				if (chasers[i] != null) {
					chasers[i].chase(surfaces[i], 0.5f, Chaser.EXP);
					chasers[i].tickChaser();
				}
			}
		}

		// ---- NBT ----

		/** Serialises a float array as a float {@link ListTag} (1.21 removed the float-array tag). */
		public static void writeSurfaces(CompoundTag tag, String key, float[] surfaces) {
			ListTag list = new ListTag();
			for (float f : surfaces)
				list.add(net.minecraft.nbt.FloatTag.valueOf(f));
			tag.put(key, list);
		}

		public static float[] readSurfaces(CompoundTag tag, String key) {
			ListTag list = tag.getList(key, net.minecraft.nbt.Tag.TAG_FLOAT);
			float[] arr = new float[list.size()];
			for (int i = 0; i < list.size(); i++)
				arr[i] = list.getFloat(i);
			return arr;
		}

		public CompoundTag writeToNBT(HolderLookup.Provider registries, int count) {
			CompoundTag tag = new CompoundTag();
			tag.putInt("Count", count);
			if (minCell != null) {
				tag.put("Min", NbtUtils.writeBlockPos(minCell));
				tag.put("Max", NbtUtils.writeBlockPos(maxCell));
			}
			writeSurfaces(tag, "Surfaces", surfaces);
			// Cells are written relative to minCell so a contraption BE (whose worldPosition
			// is in contraption-local space) can re-base them on read. Surfaces stay absolute.
			BlockPos base = minCell != null ? minCell : BlockPos.ZERO;
			ListTag basinsTag = new ListTag();
			for (Basin basin : basins) {
				CompoundTag bt = new CompoundTag();
				bt.putBoolean("Ridge", basin.ridge);
				ListTag cellsTag = new ListTag();
				for (BlockPos p : basin.cells)
					cellsTag.add(NbtUtils.writeBlockPos(p.subtract(base)));
				bt.put("Cells", cellsTag);
				basinsTag.add(bt);
			}
			tag.put("Basins", basinsTag);
			return tag;
		}

		public static BasinData readFromNBT(HolderLookup.Provider registries, CompoundTag tag, BlockPos base) {
			BasinData data = new BasinData();
			BlockPos storedMin = tag.contains("Min") ? NBTHelper.readBlockPos(tag, "Min") : null;
			// Offset between the stored reference point and where this BE actually sits.
			// Zero for normal in-world BEs (stored min == the controller's own position);
			// non-zero for contraption BEs whose worldPosition is in contraption-local space.
			BlockPos offset = storedMin != null ? base.subtract(storedMin) : BlockPos.ZERO;
			if (tag.contains("Min"))
				data.minCell = NBTHelper.readBlockPos(tag, "Min").offset(offset);
			if (tag.contains("Max"))
				data.maxCell = NBTHelper.readBlockPos(tag, "Max").offset(offset);
			data.surfaces = readSurfaces(tag, "Surfaces");
			for (int i = 0; i < data.surfaces.length; i++)
				data.surfaces[i] += offset.getY();
			ListTag basinsTag = tag.getList("Basins", net.minecraft.nbt.Tag.TAG_COMPOUND);
			for (int i = 0; i < basinsTag.size(); i++) {
				CompoundTag bt = basinsTag.getCompound(i);
				Basin basin = new Basin();
				basin.id = i;
				basin.ridge = bt.getBoolean("Ridge");
				ListTag cellsTag = bt.getList("Cells", net.minecraft.nbt.Tag.TAG_INT_ARRAY);
				for (int j = 0; j < cellsTag.size(); j++) {
					int[] a = ((net.minecraft.nbt.IntArrayTag) cellsTag.get(j)).getAsIntArray();
					BlockPos p = new BlockPos(a[0], a[1], a[2]).offset(base);
					basin.cells.add(p);
					basin.cellsByLevel.computeIfAbsent(p.getY(), k -> new ArrayList<>()).add(p);
					basin.minY = Math.min(basin.minY, p.getY());
					basin.maxY = Math.max(basin.maxY, p.getY());
					basin.cellCount++;
					data.basinByCell.put(p, i);
				}
				data.basins.add(basin);
			}
			// rebuild adjacency
			int n = data.basins.size();
			@SuppressWarnings("unchecked")
			Set<Integer>[] adj = new Set[n];
			for (int i = 0; i < n; i++)
				adj[i] = new HashSet<>();
			for (int i = 0; i < n; i++) {
				Basin basin = data.basins.get(i);
				for (BlockPos p : basin.cells) {
					for (Direction d : Direction.values()) {
						Integer j = data.basinByCell.get(p.relative(d));
						if (j != null && j != i) {
							adj[i].add(j);
							adj[j].add(i);
						}
					}
				}
			}
			data.adjacency = new int[n][];
			for (int i = 0; i < n; i++) {
				int[] a = adj[i].stream().mapToInt(Integer::intValue).toArray();
				Arrays.sort(a);
				data.adjacency[i] = a;
			}
			data.adjHeight = new int[n][n];
			for (int i = 0; i < n; i++)
				Arrays.fill(data.adjHeight[i], Integer.MAX_VALUE);
			for (int i = 0; i < n; i++) {
				Basin basin = data.basins.get(i);
				for (BlockPos p : basin.cells) {
					for (Direction d : Direction.values()) {
						Integer j = data.basinByCell.get(p.relative(d));
						if (j != null && j != i) {
							int h = Math.max(p.getY(), p.relative(d).getY());
							if (h < data.adjHeight[i][j]) {
								data.adjHeight[i][j] = h;
								data.adjHeight[j][i] = h;
							}
						}
					}
				}
			}
			return data;
		}
	}

	/** One basin of the decomposition. */
	public static final class Basin {
		int id;
		boolean ridge;
		final List<BlockPos> cells = new ArrayList<>();
		final TreeMap<Integer, List<BlockPos>> cellsByLevel = new TreeMap<>();
		int minY = Integer.MAX_VALUE;
		int maxY = Integer.MIN_VALUE;
		long cellCount;
		/** Transient volume-lookup cache; see {@link #ensureVolumeCache(int)}. */
		transient int[] levelYs;
		transient int[] levelCaps;
		transient long[] prefixCaps;
		transient int cachedPerBlock = -1;

		/**
		 * Lazily builds the per-level volume lookup used by
		 * {@link FuelTankConnectivity#basinVolume} and {@code surfaceForVolume}:
		 * level Ys (ascending), per-level capacities ({@code count × perBlock}, int
		 * semantics identical to the call sites) and their long prefix sums. Rebuilt
		 * whenever {@code perBlock} changes (server-config hot reload), so a cached
		 * sum can never go stale against the live capacity.
		 *
		 * @return the level Ys array (ascending)
		 */
		int[] ensureVolumeCache(int perBlock) {
			if (levelYs != null && cachedPerBlock == perBlock)
				return levelYs;
			int n = cellsByLevel.size();
			levelYs = new int[n];
			levelCaps = new int[n];
			prefixCaps = new long[n + 1];
			int i = 0;
			for (Map.Entry<Integer, List<BlockPos>> e : cellsByLevel.entrySet()) {
				int y = e.getKey();
				int cap = e.getValue().size() * perBlock;
				levelYs[i] = y;
				levelCaps[i] = cap;
				prefixCaps[i + 1] = prefixCaps[i] + cap;
				i++;
			}
			cachedPerBlock = perBlock;
			return levelYs;
		}

		public List<BlockPos> cells() {
			return cells;
		}

		public TreeMap<Integer, List<BlockPos>> cellsByLevel() {
			return cellsByLevel;
		}

		public boolean isRidge() {
			return ridge;
		}

		public int maxY() {
			return maxY;
		}
	}
}
