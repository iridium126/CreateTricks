package com.iridium126.createtricks.mixin;

import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import com.iridium126.createtricks.CreateTricksPartialModels;
import com.simibubi.create.AllPartialModels;
import com.iridium126.createtricks.content.kinetics.TemporaryStress;
import com.simibubi.create.content.kinetics.base.KineticBlockEntity;
import com.simibubi.create.content.kinetics.base.KineticBlockEntityVisual;
import com.simibubi.create.content.kinetics.base.RotatingInstance;
import com.simibubi.create.content.kinetics.base.SingleAxisRotatingVisual;

import dev.engine_room.flywheel.api.visual.BlockEntityVisual;
import dev.engine_room.flywheel.api.visualization.VisualizationContext;
import dev.engine_room.flywheel.lib.model.Models;
import dev.engine_room.flywheel.lib.visualization.SimpleBlockEntityVisualizer;

@Mixin(value = SingleAxisRotatingVisual.class, remap = false)
public abstract class SingleAxisRotatingVisualMixin<T extends KineticBlockEntity> extends KineticBlockEntityVisual<T> {
	@Shadow
	@Final
	protected RotatingInstance rotatingModel;

	@Unique
	private boolean createtricks$wasActive;

	protected SingleAxisRotatingVisualMixin(VisualizationContext context, T blockEntity, float partialTick) {
		super(context, blockEntity, partialTick);
	}

	@Inject(method = "of", at = @At("HEAD"), cancellable = true)
	private static <T extends KineticBlockEntity> void createtricks$useStressedCogwheel(
			CallbackInfoReturnable<SimpleBlockEntityVisualizer.Factory<T>> cir) {
		cir.setReturnValue(SingleAxisRotatingVisualMixin::createtricks$create);
	}

	@Inject(method = "shaft", at = @At("HEAD"), cancellable = true)
	private static <T extends KineticBlockEntity> void createtricks$useStressedShaft(VisualizationContext context, T be,
			float partialTick, CallbackInfoReturnable<SingleAxisRotatingVisual<T>> cir) {
		if (!TemporaryStress.isActive(be))
			return;
		cir.setReturnValue(new SingleAxisRotatingVisual<>(context, be, partialTick,
				Models.partial(CreateTricksPartialModels.STRESSED_SHAFT)));
	}

	@Unique
	private static <T extends KineticBlockEntity> BlockEntityVisual<? super T> createtricks$create(
			VisualizationContext context, T be, float partialTick) {
		return new SingleAxisRotatingVisual<>(context, be, partialTick, TemporaryStress.isActive(be)
				? Models.partial(CreateTricksPartialModels.STRESSED_COGWHEEL)
				: Models.partial(AllPartialModels.COGWHEEL));
	}

	@Inject(method = "update", at = @At("RETURN"))
	private void createtricks$updateStressedModel(float pt, CallbackInfo ci) {
		boolean active = TemporaryStress.isActive(blockEntity);
		if (createtricks$wasActive && !active)
			rotatingModel.setup(blockEntity, 0)
				.setPosition(getVisualPosition())
				.setChanged();
		createtricks$wasActive = active;
	}
}
