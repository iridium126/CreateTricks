package com.iridium126.createtricks.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import com.iridium126.createtricks.content.kinetics.TemporaryStressBracketedLargeCogVisual;
import com.iridium126.createtricks.content.kinetics.TemporaryStressModel;
import com.iridium126.createtricks.content.kinetics.TemporaryStressVisual;
import com.simibubi.create.AllBlocks;
import com.simibubi.create.AllPartialModels;
import com.simibubi.create.content.kinetics.base.SingleAxisRotatingVisual;
import com.simibubi.create.content.kinetics.simpleRelays.BracketedKineticBlockEntity;
import com.simibubi.create.content.kinetics.simpleRelays.BracketedKineticBlockEntityVisual;
import com.simibubi.create.content.kinetics.simpleRelays.ICogWheel;

import dev.engine_room.flywheel.api.visual.BlockEntityVisual;
import dev.engine_room.flywheel.api.visualization.VisualizationContext;
import dev.engine_room.flywheel.lib.model.Models;
import dev.engine_room.flywheel.lib.model.baked.PartialModel;
import net.minecraft.core.Direction;
import net.minecraft.world.level.block.state.BlockState;

@Mixin(value = BracketedKineticBlockEntityVisual.class, remap = false)
public abstract class BracketedKineticBlockEntityVisualMixin {
	@Inject(method = "create", at = @At("HEAD"), cancellable = true)
	private static void createtricks$create(VisualizationContext context, BracketedKineticBlockEntity be,
			float partialTick, CallbackInfoReturnable<BlockEntityVisual<BracketedKineticBlockEntity>> cir) {
		BlockState state = be.getBlockState();
		if (ICogWheel.isLargeCog(state)) {
			cir.setReturnValue(new TemporaryStressBracketedLargeCogVisual(context, be, partialTick));
			return;
		}

		PartialModel partial = AllBlocks.COGWHEEL.is(state.getBlock()) ? AllPartialModels.COGWHEEL : AllPartialModels.SHAFT;
		SingleAxisRotatingVisual<BracketedKineticBlockEntity> visual = new SingleAxisRotatingVisual<>(context, be,
				partialTick, Models.partial(TemporaryStressModel.replacementOrSelf(be, partial)));
		((TemporaryStressVisual) visual).createtricks$setTemporaryStressModel(partial, Direction.UP);
		cir.setReturnValue(visual);
	}
}
