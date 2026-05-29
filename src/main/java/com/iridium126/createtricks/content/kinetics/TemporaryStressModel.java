package com.iridium126.createtricks.content.kinetics;

import org.jetbrains.annotations.Nullable;

import com.iridium126.createtricks.CreateTricksPartialModels;
import com.simibubi.create.AllBlocks;
import com.simibubi.create.AllPartialModels;
import com.simibubi.create.content.kinetics.base.KineticBlockEntity;
import com.simibubi.create.content.kinetics.simpleRelays.ICogWheel;

import dev.engine_room.flywheel.lib.model.baked.PartialModel;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;

public final class TemporaryStressModel {
	private TemporaryStressModel() {}

	public static PartialModel select(BlockEntity be, PartialModel normal, PartialModel stressed) {
		return TemporaryStress.isActive(be) ? stressed : normal;
	}

	public static PartialModel shaft(BlockEntity be) {
		return select(be, AllPartialModels.SHAFT, CreateTricksPartialModels.STRESSED_SHAFT);
	}

	public static PartialModel shaftHalf(BlockEntity be) {
		return select(be, AllPartialModels.SHAFT_HALF, CreateTricksPartialModels.STRESSED_SHAFT_HALF);
	}

	public static PartialModel cogwheel(BlockEntity be) {
		return select(be, AllPartialModels.COGWHEEL, CreateTricksPartialModels.STRESSED_COGWHEEL);
	}

	public static PartialModel shaftlessLargeCogwheel(BlockEntity be) {
		return select(be, AllPartialModels.SHAFTLESS_LARGE_COGWHEEL,
				CreateTricksPartialModels.STRESSED_SHAFTLESS_LARGE_COGWHEEL);
	}

	public static PartialModel shaftlessCogwheel(BlockEntity be) {
		return select(be, AllPartialModels.SHAFTLESS_COGWHEEL,
				CreateTricksPartialModels.STRESSED_SHAFTLESS_COGWHEEL);
	}

	public static PartialModel cogwheelShaft(BlockEntity be) {
		return select(be, AllPartialModels.COGWHEEL_SHAFT, CreateTricksPartialModels.STRESSED_COGWHEEL_SHAFT);
	}

	@Nullable
	public static PartialModel rotatingBlockModel(KineticBlockEntity be) {
		BlockState state = be.getBlockState();
		if (AllBlocks.COGWHEEL.is(state.getBlock()))
			return cogwheel(be);
		if (AllBlocks.SHAFT.is(state.getBlock()))
			return shaft(be);
		if (ICogWheel.isLargeCog(state))
			return shaftlessLargeCogwheel(be);
		return null;
	}
}
