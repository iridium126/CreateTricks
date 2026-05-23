package com.iridium126.createtricks.content.kinetics;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.math.Axis;
import com.simibubi.create.foundation.blockEntity.behaviour.ValueBoxTransform;

import net.createmod.catnip.math.VecHelper;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.LevelAccessor;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;

public class StressManaConverterScrollSlot extends ValueBoxTransform {

	@Override
	public Vec3 getLocalOffset(LevelAccessor level, BlockPos pos, BlockState state) {
		return VecHelper.voxelSpace(8, 8, 2.6f);
	}

	@Override
	public boolean testHit(LevelAccessor level, BlockPos pos, BlockState state, Vec3 localHit) {
		if (localHit.y < 2 || localHit.y > 14)
			return false;

		double dx = Math.abs(localHit.x - 8);
		double dz = Math.abs(localHit.z - 8);
		double edge = scale / 2 + 0.5;

		if (localHit.x <= edge && dz <= 4)
			return true;
		if (localHit.x >= 16 - edge && dz <= 4)
			return true;
		if (localHit.z <= edge && dx <= 4)
			return true;
		return localHit.z >= 16 - edge && dx <= 4;
	}

	@Override
	public void rotate(LevelAccessor level, BlockPos pos, BlockState state, PoseStack ms) {
		ms.mulPose(Axis.XP.rotationDegrees(90));
	}
}
