package com.iridium126.createtricks.mixin;

import org.jetbrains.annotations.Nullable;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import com.iridium126.createtricks.content.kinetics.TemporaryStress;
import com.iridium126.createtricks.content.kinetics.TemporaryStressModel;
import com.simibubi.create.content.kinetics.base.KineticBlockEntity;
import com.simibubi.create.content.kinetics.base.KineticBlockEntityVisual;
import com.simibubi.create.content.kinetics.base.RotatingInstance;
import com.simibubi.create.content.kinetics.base.SingleAxisRotatingVisual;
import com.simibubi.create.foundation.render.AllInstanceTypes;

import dev.engine_room.flywheel.api.visualization.VisualizationContext;
import dev.engine_room.flywheel.lib.instance.AbstractInstance;
import dev.engine_room.flywheel.lib.model.Models;

@Mixin(value = SingleAxisRotatingVisual.class, remap = false)
public abstract class SingleAxisRotatingVisualMixin<T extends KineticBlockEntity> extends KineticBlockEntityVisual<T> {
	@Shadow
	@Final
	protected RotatingInstance rotatingModel;

	@Unique
	private RotatingInstance createtricks$stressedModel;

	@Unique
	private boolean createtricks$stressedModelActive;

	protected SingleAxisRotatingVisualMixin(VisualizationContext context, T blockEntity, float partialTick) {
		super(context, blockEntity, partialTick);
	}

	@Inject(method = "update", at = @At("RETURN"))
	private void createtricks$updateStressedModel(float pt, CallbackInfo ci) {
		boolean active = TemporaryStress.isActive(blockEntity);
		if (active)
			createtricks$ensureStressedModel();
		if (createtricks$stressedModel != null)
			createtricks$stressedModel.setup(blockEntity)
				.setPosition(getVisualPosition())
				.setChanged();
		if (!active && createtricks$stressedModel != null) {
			createtricks$stressedModel.delete();
			createtricks$stressedModel = null;
			createtricks$stressedModelActive = false;
		}
		rotatingModel.setVisible(!active);
	}

	@Inject(method = "updateLight", at = @At("RETURN"))
	private void createtricks$updateStressedLight(float partialTick, CallbackInfo ci) {
		if (createtricks$stressedModel != null)
			relight(createtricks$stressedModel);
	}

	@Inject(method = "_delete", at = @At("RETURN"))
	private void createtricks$deleteStressedModel(CallbackInfo ci) {
		if (createtricks$stressedModel != null)
			createtricks$stressedModel.delete();
	}

	private void createtricks$ensureStressedModel() {
		if (createtricks$stressedModelActive)
			return;

		var partial = TemporaryStressModel.rotatingBlockModel(blockEntity);
		if (partial == null)
			return;

		createtricks$stressedModel = instancerProvider().instancer(AllInstanceTypes.ROTATING, Models.partial(partial))
			.createInstance();
		createtricks$stressedModelActive = true;
	}
}
