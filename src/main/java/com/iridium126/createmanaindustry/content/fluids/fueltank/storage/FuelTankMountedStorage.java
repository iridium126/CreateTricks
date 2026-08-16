package com.iridium126.createmanaindustry.content.fluids.fueltank.storage;

import org.jetbrains.annotations.Nullable;

import com.iridium126.createmanaindustry.CMIMountedStorageTypes;
import com.iridium126.createmanaindustry.content.fluids.fueltank.FuelTankBlockEntity;
import com.iridium126.createmanaindustry.content.fluids.fueltank.FuelTankConnectivity;
import com.iridium126.createmanaindustry.content.fluids.fueltank.storage.FuelTankMountedStorage.Handler;
import com.mojang.serialization.MapCodec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import com.simibubi.create.api.contraption.storage.SyncedMountedStorage;
import com.simibubi.create.api.contraption.storage.fluid.MountedFluidStorageType;
import com.simibubi.create.api.contraption.storage.fluid.WrapperMountedFluidStorage;
import com.simibubi.create.content.contraptions.Contraption;

import net.createmod.catnip.animation.LerpedFloat;
import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.util.ExtraCodecs;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;

import net.neoforged.neoforge.fluids.FluidStack;
import net.neoforged.neoforge.fluids.capability.templates.FluidTank;

/**
 * Mounted fluid storage for the fuel tank, so pipes can access its contents
 * while it rides a contraption (mirrors Create's {@code FluidTankMountedStorage}).
 */
public class FuelTankMountedStorage extends WrapperMountedFluidStorage<Handler> implements SyncedMountedStorage {

	public static final MapCodec<FuelTankMountedStorage> CODEC = RecordCodecBuilder.mapCodec(i -> i.group(
		ExtraCodecs.NON_NEGATIVE_INT.fieldOf("capacity").forGetter(FuelTankMountedStorage::getCapacity),
		FluidStack.OPTIONAL_CODEC.fieldOf("fluid").forGetter(FuelTankMountedStorage::getFluid)
	).apply(i, FuelTankMountedStorage::new));

	private boolean dirty;

	protected FuelTankMountedStorage(MountedFluidStorageType<?> type, int capacity, FluidStack stack) {
		super(type, new Handler(capacity, stack));
		this.wrapped.onChange = () -> this.dirty = true;
	}

	protected FuelTankMountedStorage(int capacity, FluidStack stack) {
		this(CMIMountedStorageTypes.MOLTEN_SALT_FUEL_TANK.get(), capacity, stack);
	}

	@Override
	public void unmount(Level level, BlockState state, BlockPos pos, @Nullable BlockEntity be) {
		if (be instanceof FuelTankBlockEntity tank && tank.isController()) {
			FluidTank inventory = tank.getTankInventory();
			inventory.setFluid(this.wrapped.getFluid());
		}
	}

	public FluidStack getFluid() {
		return this.wrapped.getFluid();
	}

	public int getCapacity() {
		return this.wrapped.getCapacity();
	}

	@Override
	public boolean isDirty() {
		return this.dirty;
	}

	@Override
	public void markClean() {
		this.dirty = false;
	}

	@Override
	public void afterSync(Contraption contraption, BlockPos localPos) {
		BlockEntity be = contraption.getBlockEntityClientSide(localPos);
		if (!(be instanceof FuelTankBlockEntity tank))
			return;

		FluidTank inv = tank.getTankInventory();
		inv.setFluid(this.getFluid());
		// The contraption only carries the total fluid, not the per-basin distribution.
		// Re-derive the surfaces only when the total actually changed; otherwise keep
		// the serialized dynamic distribution (the chasers animate any change).
		if (tank.basins != null && !FuelTankConnectivity.surfacesRepresent(tank, inv.getFluidAmount()))
			FuelTankConnectivity.settle(tank);
		float fillLevel = inv.getCapacity() == 0 ? 0 : inv.getFluidAmount() / (float) inv.getCapacity();
		LerpedFloat level = tank.getFluidLevel();
		if (level == null) {
			tank.setFluidLevel(LerpedFloat.linear().startWithValue(fillLevel));
			level = tank.getFluidLevel();
		}
		if (level != null)
			level.chase(fillLevel, 0.5, LerpedFloat.Chaser.EXP);
	}

	public static FuelTankMountedStorage fromTank(FuelTankBlockEntity tank) {
		FluidTank inventory = tank.getTankInventory();
		return new FuelTankMountedStorage(inventory.getCapacity(), inventory.getFluid().copy());
	}

	public static FuelTankMountedStorage fromLegacy(HolderLookup.Provider registries, CompoundTag nbt) {
		int capacity = nbt.getInt("Capacity");
		FluidStack fluid = FluidStack.parseOptional(registries, nbt);
		return new FuelTankMountedStorage(capacity, fluid);
	}

	public static final class Handler extends FluidTank {
		private Runnable onChange = () -> {
		};

		public Handler(int capacity, FluidStack stack) {
			super(capacity);
			this.setFluid(stack);
		}

		@Override
		protected void onContentsChanged() {
			this.onChange.run();
		}
	}
}
