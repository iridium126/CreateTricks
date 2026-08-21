package com.iridium126.createmanaindustry.content.fluids.fueltank;

import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

import org.jetbrains.annotations.Nullable;

import com.iridium126.createmanaindustry.config.ServerConfig;
import com.simibubi.create.api.equipment.goggles.IHaveGoggleInformation;
import com.simibubi.create.content.decoration.copycat.CopycatBlockEntity;
import com.simibubi.create.foundation.blockEntity.IMultiBlockEntityContainer;
import com.simibubi.create.foundation.blockEntity.behaviour.BlockEntityBehaviour;
import com.simibubi.create.foundation.fluid.SmartFluidTank;

import net.createmod.catnip.animation.LerpedFloat;
import net.createmod.catnip.animation.LerpedFloat.Chaser;
import net.createmod.catnip.nbt.NBTHelper;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtUtils;
import net.minecraft.network.chat.Component;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.lighting.LevelLightEngine;
import net.minecraft.world.phys.AABB;

import net.neoforged.neoforge.fluids.FluidStack;
import net.neoforged.neoforge.fluids.IFluidTank;
import net.neoforged.neoforge.fluids.capability.IFluidHandler;
import net.neoforged.neoforge.fluids.capability.IFluidHandler.FluidAction;
import net.neoforged.neoforge.fluids.capability.templates.FluidTank;

/**
 * Block entity of the Molten Salt Fuel Tank — a multi-block fluid storage whose
 * connected group can take any shape (not Create's box constraint).
 * <p>
 * The group is a BFS connected component of face-adjacent fuel tanks. Its
 * {@code controller} is the lexicographically smallest block (Y, then X, then Z)
 * of the group; the controller alone stores the group's fluid and its basin
 * simulation state. See {@link FuelTankConnectivity}.
 * <p>
 * Extends {@code CopycatBlockEntity} so the per-cell copycat material storage,
 * NBT keys ({@code Material}/{@code Item}), validation, {@code getModelData}
 * and creative handling strictly mirror Create's implementation.
 */
public class FuelTankBlockEntity extends CopycatBlockEntity
		implements IHaveGoggleInformation, IMultiBlockEntityContainer.Fluid {

	/** Fluid handler exposed through the capability; re-created on connectivity change. */
	protected IFluidHandler fluidCapability;
	/** Group fluid storage — only meaningful on the controller. */
	protected FluidTank tankInventory;
	/** Controller of the connected group; {@code null} means this block is the controller. */
	protected BlockPos controller;
	/** Number of blocks in the group (controller only). */
	protected int count = 1;
	protected boolean updateConnectivity;
	protected boolean updateCapability;
	/** Emitted light, propagated per-block from the fluid (mirrors Create). */
	protected int luminosity;

	/**
	 * Fuel rod structure data; non-null only on a formed rod's bottom-centre
	 * tank (see {@link FuelRodStructure}). Synced to clients and persisted in
	 * NBT; cleared when the structure breaks.
	 */
	@Nullable
	public FuelRodStructure.RodData rodData;
	/** Whether the next client packet must carry the rod "cleared" marker (the rod itself is always written when present). */
	private boolean needsRodSync;

	/**
	 * Basin decomposition + per-basin liquid surfaces. Non-null only on the
	 * controller (both sides — the server drives it, the client renders from it).
	 */
	@Nullable
	public FuelTankConnectivity.BasinData basins;

	/**
	 * Surfaces stashed on load, restored after the group's basins are recomputed from
	 * the world. {@code savedCount} is the group size the surfaces were saved with; a
	 * non-null value marks a cross-chunk group load still in progress (see
	 * {@link FuelTankConnectivity#recomputeBasins}).
	 */
	transient float[] savedSurfaces;
	transient Integer savedCount;
	/** Deserialized saved basin geometry, used to verify the re-formed group's shape
	 *  matches the saved one before restoring the saved surfaces (same count with a
	 *  different shape must not paste surfaces by index). */
	transient FuelTankConnectivity.BasinData savedBasins;
	/** Vertical extent of the saved group (maxY - minY + 1), for the renderer fallback. */
	transient int savedHeight = -1;
	/** Horizontal footprint of the saved group, for the renderer fallback (group-wide box). */
	transient BlockPos savedMin;
	transient BlockPos savedMax;

	/** Animation helper for the whole-tank fill level (used by contraptions). */
	private LerpedFloat fluidLevel;

	private static final int SYNC_RATE = 8;
	protected int syncCooldown;
	protected boolean queuedSync;
	private boolean forceFluidLevelUpdate = true;
	private boolean needsBasinSync;
	private boolean needsSurfaceSync;
	private boolean needsNeighborRefresh;

	public FuelTankBlockEntity(BlockEntityType<?> type, BlockPos pos, BlockState state) {
		super(type, pos, state);
		tankInventory = createInventory();
		updateConnectivity = false;
		updateCapability = false;
		count = 1;
		refreshCapability();
	}

	protected FluidTank createInventory() {
		return new SmartFluidTank(getCapacityPerBlock(), this::onFluidStackChanged);
	}

	// ---- connectivity --------------------------------------------------------

	protected void updateConnectivity() {
		updateConnectivity = false;
		if (level.isClientSide)
			return;
		FuelTankConnectivity.updateConnectivity(this);
		// This tank may have joined, extended or broken a fuel rod structure.
		FuelRodStructure.validateFor(level, worldPosition);
		// Regrouping changes which basin/surface each cell compares against, so
		// LIT verdicts can flip WITHOUT any fluid stack event; refresh this
		// group's cells now instead of waiting for the lazy-tick self-heal.
		if (level instanceof net.minecraft.server.level.ServerLevel serverLevel) {
			FuelTankBlockEntity controllerBE = getControllerBE();
			Set<BlockPos> cells = controllerBE != null && controllerBE.basins != null
				? controllerBE.basins.basinByCell.keySet()
				: Set.of(worldPosition);
			for (BlockPos p : cells)
				FuelTankBlock.refreshLitState(serverLevel, p);
		}
	}

	/** Rebuilds the fluid capability and notifies the capability caches. */
	void refreshCapability() {
		fluidCapability = handlerForCapability();
		invalidateCapabilities();
	}

	/** Public accessor for the registered fluid capability. */
	public IFluidHandler getFluidCapability() {
		return fluidCapability;
	}

	/**
	 * Each block exposes a handler that wraps the controller's inventory and seeds
	 * basin updates with this block's own position (the cell where a pipe attaches).
	 */
	protected IFluidHandler handlerForCapability() {
		FuelTankBlockEntity controllerBE = getControllerBE();
		if (controllerBE == null)
			return new FluidTank(0);
		return new SeedTrackingHandler(controllerBE, worldPosition);
	}

	// ---- ticking -------------------------------------------------------------

	@Override
	public void tick() {
		super.tick();
		if (syncCooldown > 0) {
			syncCooldown--;
			if (syncCooldown == 0 && queuedSync)
				sendData();
		}

		if (fluidLevel != null)
			fluidLevel.tickChaser();
		if (level.isClientSide && basins != null)
			basins.tickChasers();

		if (updateCapability) {
			updateCapability = false;
			refreshCapability();
		}
		if (updateConnectivity)
			updateConnectivity();
	}

	@Override
	public void lazyTick() {
		super.lazyTick();
		// Keep retrying while a cross-chunk group is still loading (pending) or the
		// basins have not been computed yet.
		if (!level.isClientSide && isController() && (basins == null || hasPendingGroupLoad()))
			FuelTankConnectivity.updateConnectivity(this);
		// Self-heal the shader-facing LIT flag: converges paths that bypass
		// onFluidStackChanged (connectivity rebuilds, chunk loads with stale
		// blockstates, deferred surface recomputes) within one lazy-tick period.
		if (!level.isClientSide && isController() && level instanceof net.minecraft.server.level.ServerLevel serverLevel) {
			if (basins != null)
				basins.basinByCell.keySet().forEach(p -> FuelTankBlock.refreshLitState(serverLevel, p));
			else
				FuelTankBlock.refreshLitState(serverLevel, worldPosition);
		}
		// Self-heal the fuel rod structure: while formed, re-validate on every lazy
		// tick so glass moved by pistons, explosions and other un-triggered changes
		// converge (and clear stale data) within one lazy-tick period.
		if (!level.isClientSide && rodData != null)
			FuelRodStructure.validateFrom(level, rodData.center);
	}

	// ---- fluid handler (capability) ------------------------------------------

	/**
	 * Wraps the controller's inventory and records the attaching cell so basin
	 * surfaces can be re-derived from where the fluid entered.
	 */
	private static final class SeedTrackingHandler implements IFluidHandler {
		private final FuelTankBlockEntity controllerBE;
		private final BlockPos seed;

		SeedTrackingHandler(FuelTankBlockEntity controllerBE, BlockPos seed) {
			this.controllerBE = controllerBE;
			this.seed = seed;
		}

		private IFluidHandler delegate() {
			return controllerBE.tankInventory;
		}

		@Override
		public int getTanks() {
			return delegate().getTanks();
		}

		@Override
		public FluidStack getFluidInTank(int tank) {
			return delegate().getFluidInTank(tank);
		}

		@Override
		public int getTankCapacity(int tank) {
			return delegate().getTankCapacity(tank);
		}

		@Override
		public boolean isFluidValid(int tank, FluidStack stack) {
			return delegate().isFluidValid(tank, stack);
		}

		@Override
		public int fill(FluidStack resource, FluidAction action) {
			IFluidHandler inv = delegate();
			int before = inv.getFluidInTank(0).getAmount();
			int filled = inv.fill(resource, action);
			if (action.execute() && filled > 0 && controllerBE.hasLevel() && !controllerBE.getLevel().isClientSide) {
				int after = inv.getFluidInTank(0).getAmount();
				FuelTankConnectivity.applyDelta(controllerBE.getLevel(), controllerBE, seed, after - before);
			}
			return filled;
		}

		@Override
		public FluidStack drain(FluidStack resource, FluidAction action) {
			IFluidHandler inv = delegate();
			int before = inv.getFluidInTank(0).getAmount();
			FluidStack drained = inv.drain(resource, action);
			if (action.execute() && !drained.isEmpty() && controllerBE.hasLevel()
					&& !controllerBE.getLevel().isClientSide) {
				int after = inv.getFluidInTank(0).getAmount();
				FuelTankConnectivity.applyDelta(controllerBE.getLevel(), controllerBE, seed, after - before);
			}
			return drained;
		}

		@Override
		public FluidStack drain(int maxDrain, FluidAction action) {
			IFluidHandler inv = delegate();
			int before = inv.getFluidInTank(0).getAmount();
			FluidStack drained = inv.drain(maxDrain, action);
			if (action.execute() && !drained.isEmpty() && controllerBE.hasLevel()
					&& !controllerBE.getLevel().isClientSide) {
				int after = inv.getFluidInTank(0).getAmount();
				FuelTankConnectivity.applyDelta(controllerBE.getLevel(), controllerBE, seed, after - before);
			}
			return drained;
		}
	}

	// ---- fluid content -------------------------------------------------------

	protected void onFluidStackChanged(FluidStack newFluidStack) {
		if (!hasLevel())
			return;

		if (isController()) {
			int newLum = (int) (newFluidStack.getFluid().getFluidType().getLightLevel(newFluidStack) / 1.2f);
			if (luminosity != newLum) {
				luminosity = newLum;
				// Every part's block reads its light emission from the controller's luminosity,
				// so only the light engine needs re-evaluating at each part.
				if (!level.isClientSide) {
					Set<BlockPos> cells = basins != null ? basins.basinByCell.keySet() : Set.of(worldPosition);
					for (BlockPos p : cells)
						level.getChunkSource().getLightEngine().checkBlock(p);
				}
			}
			// Keep the shader-facing LIT flag in step with the fluid surface even
			// when the luminosity itself is unchanged: surface movement changes
			// WHICH cells are bright without changing the fluid type or its light.
			if (!level.isClientSide && level instanceof net.minecraft.server.level.ServerLevel serverLevel) {
				if (basins != null)
					basins.basinByCell.keySet().forEach(p -> FuelTankBlock.refreshLitState(serverLevel, p));
				else
					FuelTankBlock.refreshLitState(serverLevel, worldPosition);
			}
			markSurfacesDirty();
			setChanged();
			sendData();
		}

		if (fluidLevel == null)
			fluidLevel = LerpedFloat.linear().startWithValue(getFillState());
		fluidLevel.chase(getFillState(), 0.5f, Chaser.EXP);
	}

	// ---- group accessors -----------------------------------------------------

	@Override
	public boolean isController() {
		return controller == null || worldPosition.equals(controller);
	}

	@Override
	public BlockPos getController() {
		return isController() ? worldPosition : controller;
	}

	@Override
	public void setController(BlockPos pos) {
		if (level.isClientSide && !isVirtual())
			return;
		if (Objects.equals(pos, controller))
			return;
		controller = pos;
		refreshCapability();
		setChanged();
		// The block model culls faces from the block entity's controller, so push the
		// new controller to the client immediately instead of waiting out the 8-tick
		// sync rate (otherwise the last-placed tank keeps its unculled wall until then).
		sendDataImmediately();
	}

	@Override
	public void removeController(boolean keepFluids) {
		if (level.isClientSide)
			return;
		updateConnectivity = true;
		if (!keepFluids)
			applyFluidTankSize(1);
		controller = null;
		count = 1;
		basins = null;
		savedSurfaces = null;
		savedCount = null;
		savedBasins = null;
		savedHeight = -1;
		savedMin = null;
		savedMax = null;
		updateCapability = true;
		setChanged();
		sendData();
	}

	@Override
	public void preventConnectivityUpdate() {
		updateConnectivity = false;
	}

	@SuppressWarnings("unchecked")
	@Override
	public FuelTankBlockEntity getControllerBE() {
		if (isController() || !hasLevel())
			return this;
		BlockEntity be = level.getBlockEntity(controller);
		if (be instanceof FuelTankBlockEntity)
			return (FuelTankBlockEntity) be;
		return null;
	}

	@Override
	public BlockPos getLastKnownPos() {
		return worldPosition;
	}

	@Override
	public void notifyMultiUpdated() {
		// The unified model needs no top/bottom blockstate updates: all six faces are
		// culled per same-group adjacency by FuelTankModel, and re-renders after
		// connectivity changes are driven by sendDataImmediately/sendBlockUpdated.
		setChanged();
	}

	// ---- IMultiBlockEntityContainer (compatibility; connectivity is graph-based) ----

	@Override
	public Direction.Axis getMainConnectionAxis() {
		return Direction.Axis.Y;
	}

	@Override
	public int getMaxLength(Direction.Axis longAxis, int width) {
		return ServerConfig.fuelTankMaxBlocks;
	}

	@Override
	public int getMaxWidth() {
		return ServerConfig.fuelTankMaxBlocks;
	}

	@Override
	public int getHeight() {
		return count;
	}

	@Override
	public void setHeight(int height) {
		this.count = height;
	}

	@Override
	public int getWidth() {
		return 1;
	}

	@Override
	public void setWidth(int width) {
	}

	@Override
	public boolean hasTank() {
		return true;
	}

	@Override
	public int getTankSize(int tank) {
		return getCapacityPerBlock();
	}

	@Override
	public void setTankSize(int tank, int blocks) {
		applyFluidTankSize(blocks);
	}

	@Override
	public IFluidTank getTank(int tank) {
		return tankInventory;
	}

	@Override
	public FluidStack getFluid(int tank) {
		return tankInventory.getFluid().copy();
	}

	// ---- capacity ------------------------------------------------------------

	public static int getCapacityPerBlock() {
		return ServerConfig.fuelTankCapacity * 1000;
	}

	public void applyFluidTankSize(int blocks) {
		int capacity = blocks * getCapacityPerBlock();
		tankInventory.setCapacity(capacity);
		if (tankInventory.getFluidAmount() > capacity)
			tankInventory.drain(tankInventory.getFluidAmount() - capacity, FluidAction.EXECUTE);
	}

	public FluidTank getTankInventory() {
		return tankInventory;
	}

	public int getCount() {
		return isController() ? count : getControllerBE() != null ? getControllerBE().count : 1;
	}

	public float getFillState() {
		int capacity = tankInventory.getCapacity();
		return capacity == 0 ? 0 : (float) tankInventory.getFluidAmount() / capacity;
	}

	/** True while a saved group load has not yet confirmed its full block count. */
	public boolean hasPendingGroupLoad() {
		return savedCount != null;
	}

	/**
	 * Vertical extent used by the renderer's uniform-level fallback: the saved group's
	 * real height when one was loaded, otherwise the current group block count.
	 */
	public int getFallbackHeight() {
		return savedHeight > 0 ? savedHeight : Math.max(1, getCount());
	}

	@Nullable
	public BlockPos getFallbackMin() {
		return savedMin;
	}

	@Nullable
	public BlockPos getFallbackMax() {
		return savedMax;
	}

	@Nullable
	public LerpedFloat getFluidLevel() {
		return fluidLevel;
	}

	public void setFluidLevel(LerpedFloat fluidLevel) {
		this.fluidLevel = fluidLevel;
	}

	// ---- goggle tooltip ------------------------------------------------------

	@Override
	public boolean addToGoggleTooltip(List<Component> tooltip, boolean isPlayerSneaking) {
		FuelTankBlockEntity controllerBE = getControllerBE();
		if (controllerBE == null)
			return false;
		return containedFluidTooltip(tooltip, isPlayerSneaking, controllerBE.tankInventory);
	}

	// ---- sync ----------------------------------------------------------------

	@Override
	public void sendData() {
		if (syncCooldown > 0) {
			queuedSync = true;
			return;
		}
		super.sendData();
		queuedSync = false;
		syncCooldown = SYNC_RATE;
		if (needsNeighborRefresh) {
			needsNeighborRefresh = false;
			if (!level.isClientSide)
				refreshNeighborSignals();
		}
	}

	public void sendDataImmediately() {
		syncCooldown = 0;
		queuedSync = false;
		sendData();
	}

	/**
	 * Updates the fuel rod structure state, syncing immediately when the state
	 * actually changed (forming, extending or breaking a rod).
	 */
	public void setRodData(@Nullable FuelRodStructure.RodData rod, boolean syncImmediately) {
		if (Objects.equals(this.rodData, rod))
			return;
		this.rodData = rod;
		needsRodSync = true;
		setChanged();
		if (syncImmediately)
			sendDataImmediately();
	}

	/**
	 * Re-arms comparators next to any part of the group. Mirrors Create's per-part
	 * {@code updateNeighbourForOutputSignal}; throttled to the sync cadence so a large
	 * tank does not pay this cost on every pipe fill.
	 */
	private void refreshNeighborSignals() {
		Set<BlockPos> cells = basins != null ? basins.basinByCell.keySet() : Set.of(worldPosition);
		for (BlockPos p : cells) {
			FuelTankBlockEntity part = FuelTankConnectivity.partAt(getType(), level, p);
			if (part == null)
				continue;
			level.updateNeighbourForOutputSignal(p, part.getBlockState().getBlock());
		}
	}

	/** Marks that the next client packet must carry the full basin geometry. */
	public void markBasinsDirty() {
		needsBasinSync = true;
		needsSurfaceSync = true;
		forceFluidLevelUpdate = true;
		needsNeighborRefresh = true;
	}

	/** Marks that the next client packet must carry the per-basin surfaces. */
	public void markSurfacesDirty() {
		needsSurfaceSync = true;
		forceFluidLevelUpdate = true;
		needsNeighborRefresh = true;
	}

	@Override
	public void initialize() {
		super.initialize();
		sendData();
		if (level.isClientSide)
			invalidateRenderBoundingBox();
	}

	// ---- read/write ----------------------------------------------------------

	@Override
	protected void read(CompoundTag tag, HolderLookup.Provider registries, boolean clientPacket) {
		super.read(tag, registries, clientPacket);

		BlockPos controllerBefore = controller;
		int prevLum = luminosity;
		luminosity = tag.getInt("Luminosity");

		controller = null;
		if (tag.contains("Controller"))
			controller = NBTHelper.readBlockPos(tag, "Controller");

		if (isController()) {
			count = tag.getInt("Count");
			tankInventory.setCapacity(count * getCapacityPerBlock());
			tankInventory.readFromNBT(registries, tag.getCompound("TankContent"));
			if (tankInventory.getSpace() < 0)
				tankInventory.drain(-tankInventory.getSpace(), FluidAction.EXECUTE);

			// Full save (chunk load / contraption): stash the group's surfaces and size so
			// the load-in-progress logic can restore the distribution once the whole group
			// is present, and (on the client) rebuild the exact basin geometry directly.
			if (!clientPacket && tag.contains("Basins")) {
				CompoundTag basinsTag = tag.getCompound("Basins");
				savedSurfaces = FuelTankConnectivity.BasinData.readSurfaces(basinsTag, "Surfaces");
				savedCount = count;
				// Deserialized for the shape-equality guard in recomputeBasins: the saved
				// surfaces may only be restored onto an identical decomposition.
				savedBasins = FuelTankConnectivity.BasinData.readFromNBT(registries, basinsTag, worldPosition);
				if (basinsTag.contains("Min") && basinsTag.contains("Max")) {
					BlockPos mn = NBTHelper.readBlockPos(basinsTag, "Min");
					BlockPos mx = NBTHelper.readBlockPos(basinsTag, "Max");
					savedHeight = mx.getY() - mn.getY() + 1;
					savedMin = mn;
					savedMax = mx;
				}
				if (level != null && level.isClientSide) {
					basins = savedBasins;
					if (basins.chasers == null)
						basins.initChasers(basins.surfaces);
					invalidateRenderBoundingBox();
				}
			}
		}

		// Fuel rod structure (any tank may be a rod's bottom centre, regardless of
		// fluid-group controller). Client packets carry it only when it changed,
		// including an explicit "cleared" marker so removals reach the client.
		if (clientPacket) {
			FuelRodStructure.RodData prevRod = rodData;
			if (tag.contains("Rod"))
				rodData = FuelRodStructure.RodData.readFromNBT(tag.getCompound("Rod"));
			else if (tag.contains("RodCleared"))
				rodData = null;
			// Notify the client-side bloom handler when the rod state changed.
			if (level != null && level.isClientSide && !Objects.equals(prevRod, rodData))
				FuelRodSync.notifyClientSync(worldPosition, rodData);
		} else if (tag.contains("Rod")) {
			rodData = FuelRodStructure.RodData.readFromNBT(tag.getCompound("Rod"));
		}

		// On load, re-form the group from the world (blocks may be loading across chunks).
		if (!clientPacket)
			updateConnectivity = true;

		if (clientPacket && isController()) {
			FuelTankConnectivity.BasinData prevBasins = basins;
			if (tag.contains("BasinGeometry")) {
				basins = FuelTankConnectivity.BasinData.readFromNBT(registries, tag.getCompound("BasinGeometry"),
						worldPosition);
				invalidateRenderBoundingBox();
			}
			if (tag.contains("Surfaces") && basins != null) {
				basins.surfaces = FuelTankConnectivity.BasinData.readSurfaces(tag, "Surfaces");
				if (basins.chasers == null)
					basins.initChasers(basins.surfaces);
			}

			// Light emission re-check (mirrors Create's FluidTankBlockEntity#read,
			// extended to the whole group): the client's light engine must be told
			// when the emission of any cell changed — otherwise it only learns the
			// tank's glow from an unrelated block update that happens to force a
			// re-pull. The luminosity is controller-authoritative (every part's
			// block reads its emission from the controller), so the controller
			// re-checks on:
			//  - luminosity transitions (0 <-> luminous), and
			//  - basin geometry changes, and
			//  - liquid surfaces crossing a cell boundary (floor change), so a
			//    continuously filling/draining tank relights without block updates.
			if (level != null && level.isClientSide) {
				Set<BlockPos> cells = null;
				if (luminosity != prevLum || tag.contains("BasinGeometry")) {
					cells = basins != null ? basins.basinByCell.keySet() : Set.of(worldPosition);
				} else if (tag.contains("Surfaces") && basins != null && prevBasins != null
						&& prevBasins.surfaces != null) {
					if (basins.surfaces.length != prevBasins.surfaces.length) {
						cells = basins.basinByCell.keySet();
					} else {
						for (int i = 0; i < basins.surfaces.length; i++) {
							if ((int) Math.floor(basins.surfaces[i]) == (int) Math.floor(prevBasins.surfaces[i]))
								continue;
							final int basinId = i;
							if (cells == null)
								cells = new HashSet<>();
							final Set<BlockPos> target = cells;
							basins.basinByCell.forEach((p, b) -> {
								if (b == basinId)
									target.add(p);
							});
						}
					}
				}
				if (cells != null && !cells.isEmpty()) {
					LevelLightEngine lightEngine = level.getChunkSource().getLightEngine();
					for (BlockPos p : cells)
						lightEngine.checkBlock(p);
				}
			}
		}

		if (tag.contains("ForceFluidLevel") || fluidLevel == null)
			fluidLevel = LerpedFloat.linear().startWithValue(getFillState());
		fluidLevel.chase(getFillState(), 0.5f, Chaser.EXP);

		// A controller change means the block model's face-culling data (which reads the
		// controller) must be rebuilt. The placement block update renders the block before
		// this BE packet arrives, so force a re-render now that the controller is fresh —
		// otherwise the last-placed tank keeps its unculled wall until some later re-render.
		if (clientPacket && level != null && level.isClientSide && !Objects.equals(controllerBefore, controller))
			level.sendBlockUpdated(worldPosition, getBlockState(), getBlockState(), Block.UPDATE_CLIENTS);

		updateCapability = true;
	}

	@Override
	protected void write(CompoundTag tag, HolderLookup.Provider registries, boolean clientPacket) {
		super.write(tag, registries, clientPacket);

		tag.putInt("Luminosity", luminosity);
		if (!isController())
			tag.put("Controller", NbtUtils.writeBlockPos(controller));
		if (isController()) {
			tag.putInt("Count", count);
			tag.put("TankContent", tankInventory.writeToNBT(registries, new CompoundTag()));
			if (!clientPacket) {
				if (basins != null)
					tag.put("Basins", basins.writeToNBT(registries, count));
			} else if (basins != null) {
				if (needsBasinSync)
					tag.put("BasinGeometry", basins.writeToNBT(registries, count));
				else if (needsSurfaceSync)
					FuelTankConnectivity.BasinData.writeSurfaces(tag, "Surfaces", basins.surfaces);
			}
		}
		needsBasinSync = false;
		needsSurfaceSync = false;

		// Fuel rod state: persisted on every full save; client packets always
		// carry the rod when present so a freshly loaded chunk syncs it with the
		// initial packet (no setRodData runs between the NBT restore and the
		// first sendData), and the "RodCleared" marker (gated by needsRodSync)
		// distinguishes a clear from "unchanged".
		if (!clientPacket) {
			if (rodData != null)
				tag.put("Rod", rodData.writeToNBT());
		} else {
			if (rodData != null) {
				tag.put("Rod", rodData.writeToNBT());
				needsRodSync = false;
			} else if (needsRodSync) {
				needsRodSync = false;
				tag.putBoolean("RodCleared", true);
			}
		}

		if (!clientPacket)
			return;
		if (forceFluidLevelUpdate)
			tag.putBoolean("ForceFluidLevel", true);
		forceFluidLevelUpdate = false;
	}

	@Override
	public void writeSafe(CompoundTag tag, HolderLookup.Provider registries) {
		// Copycat material safe-NBT (without data components) + the group count.
		super.writeSafe(tag, registries);
		if (isController())
			tag.putInt("Count", count);
	}

	// ---- render bounding box -------------------------------------------------

	@Override
	protected AABB createRenderBoundingBox() {
		if (isController() && basins != null) {
			BlockPos min = basins.minCell();
			BlockPos max = basins.maxCell();
			if (min != null && max != null)
				return new AABB(min.getX(), min.getY(), min.getZ(), max.getX() + 1D, max.getY() + 1D,
					max.getZ() + 1D);
		}
		return super.createRenderBoundingBox();
	}

	@Override
	public void addBehaviours(List<BlockEntityBehaviour> behaviours) {
		super.addBehaviours(behaviours);
	}
}
