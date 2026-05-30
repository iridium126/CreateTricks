package com.iridium126.createtricks.content.kinetics;

import org.jetbrains.annotations.Nullable;

import com.iridium126.createtricks.CreateTricksPartialModels;
import com.simibubi.create.AllBlocks;
import com.simibubi.create.AllPartialModels;
import com.simibubi.create.content.kinetics.base.HorizontalKineticBlock;
import com.simibubi.create.content.kinetics.base.KineticBlockEntity;
import com.simibubi.create.content.kinetics.simpleRelays.AbstractSimpleShaftBlock;
import com.simibubi.create.content.kinetics.simpleRelays.ICogWheel;

import dev.engine_room.flywheel.lib.model.baked.PartialModel;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;

public final class TemporaryStressModel {
	private TemporaryStressModel() {}

	public static PartialModel select(BlockEntity be, PartialModel normal, PartialModel stressed) {
		return TemporaryStress.isActive(be) ? stressed : normal;
	}

	public static PartialModel innerStressManaConverter(BlockEntity be) {
		return select(be, CreateTricksPartialModels.STRESS_MANA_CONVERTER_INNER,
				CreateTricksPartialModels.STRESSED_STRESS_MANA_CONVERTER_INNER);
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
	public static PartialModel replacement(BlockEntity be, PartialModel model) {
		if (model == CreateTricksPartialModels.STRESS_MANA_CONVERTER_INNER)
			return innerStressManaConverter(be);
		if (model == AllPartialModels.SHAFT)
			return shaft(be);
		if (model == AllPartialModels.SHAFT_HALF)
			return shaftHalf(be);
		if (model == AllPartialModels.COGWHEEL)
			return cogwheel(be);
		if (model == AllPartialModels.SHAFTLESS_COGWHEEL)
			return shaftlessCogwheel(be);
		if (model == AllPartialModels.SHAFTLESS_LARGE_COGWHEEL)
			return shaftlessLargeCogwheel(be);
		if (model == AllPartialModels.COGWHEEL_SHAFT)
			return cogwheelShaft(be);
		return null;
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
		if (state.hasProperty(HorizontalKineticBlock.HORIZONTAL_FACING)
				|| state.hasProperty(AbstractSimpleShaftBlock.AXIS)
				|| state.hasProperty(BlockStateProperties.AXIS))
			return shaft(be);
		return null;
	}
}
