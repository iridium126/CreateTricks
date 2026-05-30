package com.iridium126.createtricks.mixin;

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
import com.simibubi.create.content.kinetics.base.SingleAxisRotatingVisual;

import dev.engine_room.flywheel.api.visualization.VisualizationContext;

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
