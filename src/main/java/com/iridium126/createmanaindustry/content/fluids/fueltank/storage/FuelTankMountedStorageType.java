package com.iridium126.createmanaindustry.content.fluids.fueltank.storage;

import org.jetbrains.annotations.Nullable;

import com.iridium126.createmanaindustry.content.fluids.fueltank.FuelTankBlockEntity;
import com.simibubi.create.api.contraption.storage.fluid.MountedFluidStorageType;

import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;

/**
 * Mounted-storage type for the fuel tank; only the group controller carries the
 * storage (mirrors Create's {@code FluidTankMountedStorageType}).
 */
public class FuelTankMountedStorageType extends MountedFluidStorageType<FuelTankMountedStorage> {

	public FuelTankMountedStorageType() {
		super(FuelTankMountedStorage.CODEC);
	}

	@Override
	@Nullable
	public FuelTankMountedStorage mount(Level level, BlockState state, BlockPos pos, @Nullable BlockEntity be) {
		if (be instanceof FuelTankBlockEntity tank && tank.isController())
			return FuelTankMountedStorage.fromTank(tank);
		return null;
	}
}
