package com.iridium126.createtricks.content.kinetics;

import com.mojang.blaze3d.vertex.PoseStack;
import com.simibubi.create.foundation.blockEntity.behaviour.ValueBoxTransform;

import dev.engine_room.flywheel.lib.transform.TransformStack;
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
	protected Vec3 getSouthLocation() {
		return Vec3.ZERO;
	}

	@Override
	public Vec3 getLocalOffset(LevelAccessor level, BlockPos pos, BlockState state) {
		Direction facing = state.getValue(StressManaConverterBlock.FACING);
		Direction side = getSide();

		// UP
		if (facing == Direction.UP) {
			return switch (side) {
				case SOUTH -> VecHelper.voxelSpace(8, 3, 15.5f);
				case NORTH -> VecHelper.voxelSpace(8, 3, 0.5f);
				case EAST -> VecHelper.voxelSpace(15.5f, 3, 8);
				case WEST -> VecHelper.voxelSpace(0.5f, 3, 8);
				default -> Vec3.ZERO;
			};
		}

		// DOWN
		if (facing == Direction.DOWN) {
			return switch (side) {
				case SOUTH -> VecHelper.voxelSpace(8, 13, 15.5f);
				case NORTH -> VecHelper.voxelSpace(8, 13, 0.5f);
				case EAST -> VecHelper.voxelSpace(15.5f, 13, 8);
				case WEST -> VecHelper.voxelSpace(0.5f, 13, 8);
				default -> Vec3.ZERO;
			};
		}

		// NORTH
		if (facing == Direction.NORTH) {
			return switch (side) {
				case UP -> VecHelper.voxelSpace(8, 15.5f, 13);
				case DOWN -> VecHelper.voxelSpace(8, 0.5f, 13);
				case EAST -> VecHelper.voxelSpace(15.5f, 8, 13);
				case WEST -> VecHelper.voxelSpace(0.5f, 8, 13);
				default -> Vec3.ZERO;
			};
		}

		// SOUTH
		if (facing == Direction.SOUTH) {
			return switch (side) {
				case UP -> VecHelper.voxelSpace(8, 15.5f, 3);
				case DOWN -> VecHelper.voxelSpace(8, 0.5f, 3);
				case EAST -> VecHelper.voxelSpace(15.5f, 8, 3);
				case WEST -> VecHelper.voxelSpace(0.5f, 8, 3);
				default -> Vec3.ZERO;
			};
		}

		// EAST
		if (facing == Direction.EAST) {
			return switch (side) {
				case UP -> VecHelper.voxelSpace(3, 15.5f, 8);
				case DOWN -> VecHelper.voxelSpace(3, 0.5f, 8);
				case SOUTH -> VecHelper.voxelSpace(3, 8, 15.5f);
				case NORTH -> VecHelper.voxelSpace(3, 8, 0.5f);
				default -> Vec3.ZERO;
			};
		}

		// WEST
		return switch (side) {
			case UP -> VecHelper.voxelSpace(13, 15.5f, 8);
			case DOWN -> VecHelper.voxelSpace(13, 0.5f, 8);
			case SOUTH -> VecHelper.voxelSpace(13, 8, 15.5f);
			case NORTH -> VecHelper.voxelSpace(13, 8, 0.5f);
			default -> Vec3.ZERO;
		};
	}

	@Override
	public void rotate(LevelAccessor level, BlockPos pos, BlockState state, PoseStack ms) {
		super.rotate(level, pos, state, ms);
		Direction facing = state.getValue(StressManaConverterBlock.FACING);
		if (facing.getAxis() == Axis.Y)
			return;
		if (getSide() == Direction.UP
				|| (getSide() == Direction.DOWN && (facing == Direction.WEST || facing == Direction.EAST)))
			TransformStack.of(ms).rotateZDegrees(-AngleHelper.horizontalAngle(facing) + 180);
	}

	@Override
	protected boolean isSideActive(BlockState state, Direction direction) {
		Direction facing = state.getValue(StressManaConverterBlock.FACING);
		return direction.getAxis() != facing.getAxis();
	}
}
