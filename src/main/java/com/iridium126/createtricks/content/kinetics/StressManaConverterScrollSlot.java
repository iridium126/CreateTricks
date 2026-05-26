package com.iridium126.createtricks.content.kinetics;

import com.simibubi.create.content.kinetics.base.DirectionalKineticBlock;
import com.simibubi.create.foundation.blockEntity.behaviour.ValueBoxTransform;

import net.createmod.catnip.math.AngleHelper;
import net.createmod.catnip.math.VecHelper;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.Direction.Axis;
import net.minecraft.world.level.LevelAccessor;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;

public class StressManaConverterScrollSlot extends ValueBoxTransform.Sided {

	@Override
	public Vec3 getLocalOffset(LevelAccessor level, BlockPos pos, BlockState state) {
		Direction side = getSide();
		Axis axis = state.getValue(DirectionalKineticBlock.FACING).getAxis();

		if (axis == Axis.Y) {
			float horizontalAngle = AngleHelper.horizontalAngle(side);
			return VecHelper.rotateCentered(VecHelper.voxelSpace(8, 3, 15.5f), horizontalAngle, Axis.Y);
		}

		double alongAxis = 3;
		double x = 8, y = 3, z = 8;
		switch (axis) {
			case X -> x = alongAxis;
			case Z -> z = alongAxis;
			default -> {
			}
		}

		switch (side) {
			case EAST -> x = 15.5;
			case WEST -> x = 0.5;
			case UP -> y = axis == Axis.Y ? 15.5 : 6.5;
			case DOWN -> y = 0.5;
			case SOUTH -> z = 15.5;
			case NORTH -> z = 0.5;
			default -> {
			}
		}

		return VecHelper.voxelSpace((float) x, (float) y, (float) z);
	}

	@Override
	protected boolean isSideActive(BlockState state, Direction direction) {
		return direction.getAxis() != state.getValue(DirectionalKineticBlock.FACING).getAxis();
	}

	@Override
	protected Vec3 getSouthLocation() {
		return Vec3.ZERO;
	}
}
