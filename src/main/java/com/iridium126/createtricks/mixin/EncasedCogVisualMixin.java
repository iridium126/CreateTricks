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
import com.simibubi.create.content.kinetics.base.KineticBlockEntity;
import com.simibubi.create.content.kinetics.base.KineticBlockEntityVisual;
import com.simibubi.create.content.kinetics.base.RotatingInstance;
import com.simibubi.create.content.kinetics.simpleRelays.encased.EncasedCogVisual;

import dev.engine_room.flywheel.api.visualization.VisualizationContext;

@Mixin(value = EncasedCogVisual.class, remap = false)
public abstract class EncasedCogVisualMixin extends KineticBlockEntityVisual<KineticBlockEntity> {
	@Shadow
	@Final
	protected RotatingInstance rotatingModel;

	@Shadow
	@Final
	@Nullable
	protected RotatingInstance rotatingTopShaft;

	@Shadow
	@Final
	@Nullable
	protected RotatingInstance rotatingBottomShaft;

	@Unique
	private boolean createtricks$wasActive;

	protected EncasedCogVisualMixin(VisualizationContext context, KineticBlockEntity blockEntity, float partialTick) {
		super(context, blockEntity, partialTick);
	}

	@Inject(method = "update", at = @At("RETURN"))
	private void createtricks$updateStressedModels(float pt, CallbackInfo ci) {
		boolean active = TemporaryStress.isActive(blockEntity);
		if (!createtricks$wasActive || active) {
			createtricks$wasActive = active;
			return;
		}
		createtricks$stopInstance(rotatingModel);
		if (rotatingTopShaft != null)
			createtricks$stopInstance(rotatingTopShaft);
		if (rotatingBottomShaft != null)
			createtricks$stopInstance(rotatingBottomShaft);
		createtricks$wasActive = false;
	}

	@Unique
	private void createtricks$stopInstance(RotatingInstance instance) {
		instance.setup(blockEntity, 0)
			.setPosition(getVisualPosition())
			.setChanged();
	}
}
