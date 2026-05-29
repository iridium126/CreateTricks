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
import com.simibubi.create.content.kinetics.simpleRelays.ICogWheel;
import com.simibubi.create.content.kinetics.simpleRelays.encased.EncasedCogVisual;
import com.simibubi.create.foundation.render.AllInstanceTypes;

import dev.engine_room.flywheel.api.visualization.VisualizationContext;
import dev.engine_room.flywheel.lib.instance.AbstractInstance;
import dev.engine_room.flywheel.lib.model.Models;

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
	private RotatingInstance createtricks$stressedCog;

	@Unique
	private RotatingInstance createtricks$stressedTopShaft;

	@Unique
	private RotatingInstance createtricks$stressedBottomShaft;

	protected EncasedCogVisualMixin(VisualizationContext context, KineticBlockEntity blockEntity, float partialTick) {
		super(context, blockEntity, partialTick);
	}

	@Inject(method = "update", at = @At("RETURN"))
	private void createtricks$updateStressedModels(float pt, CallbackInfo ci) {
		boolean active = TemporaryStress.isActive(blockEntity);
		if (active)
			createtricks$ensureStressedModels();
		createtricks$updateInstance(createtricks$stressedCog);
		createtricks$updateInstance(createtricks$stressedTopShaft);
		createtricks$updateInstance(createtricks$stressedBottomShaft);
		if (!active)
			createtricks$deleteStressedModels();

		rotatingModel.setVisible(!active);
		if (rotatingTopShaft != null)
			rotatingTopShaft.setVisible(!active);
		if (rotatingBottomShaft != null)
			rotatingBottomShaft.setVisible(!active);
	}

	@Inject(method = "updateLight", at = @At("RETURN"))
	private void createtricks$updateLight(float partialTick, CallbackInfo ci) {
		relight(createtricks$stressedCog, createtricks$stressedTopShaft, createtricks$stressedBottomShaft);
	}

	@Inject(method = "_delete", at = @At("RETURN"))
	private void createtricks$delete(CallbackInfo ci) {
		createtricks$deleteStressedModels();
	}

	@Unique
	private void createtricks$ensureStressedModels() {
		if (createtricks$stressedCog != null)
			return;

		createtricks$stressedCog = instancerProvider().instancer(AllInstanceTypes.ROTATING, Models.partial(
				ICogWheel.isLargeCog(blockEntity.getBlockState())
						? TemporaryStressModel.shaftlessLargeCogwheel(blockEntity)
						: TemporaryStressModel.shaftlessCogwheel(blockEntity)))
			.createInstance();
		createtricks$stressedCog.setup(blockEntity)
			.setPosition(getVisualPosition())
			.rotateToFace(KineticBlockEntityVisual.rotationAxis(blockEntity.getBlockState()))
			.setChanged();

		if (rotatingTopShaft != null)
			createtricks$stressedTopShaft = createtricks$shaftCopy(rotatingTopShaft);
		if (rotatingBottomShaft != null)
			createtricks$stressedBottomShaft = createtricks$shaftCopy(rotatingBottomShaft);
	}

	@Unique
	private RotatingInstance createtricks$shaftCopy(RotatingInstance original) {
		RotatingInstance instance = instancerProvider().instancer(AllInstanceTypes.ROTATING,
				Models.partial(TemporaryStressModel.shaftHalf(blockEntity)))
			.createInstance();
		instance.rotation.set(original.rotation);
		instance.setup(blockEntity)
			.setPosition(getVisualPosition())
			.setRotationOffset(original.rotationOffset)
			.setChanged();
		return instance;
	}

	@Unique
	private void createtricks$updateInstance(@Nullable RotatingInstance instance) {
		if (instance != null)
			instance.setup(blockEntity)
				.setPosition(getVisualPosition())
				.setChanged();
	}

	@Unique
	private void createtricks$deleteStressedModels() {
		if (createtricks$stressedCog != null) {
			createtricks$stressedCog.delete();
			createtricks$stressedCog = null;
		}
		if (createtricks$stressedTopShaft != null) {
			createtricks$stressedTopShaft.delete();
			createtricks$stressedTopShaft = null;
		}
		if (createtricks$stressedBottomShaft != null) {
			createtricks$stressedBottomShaft.delete();
			createtricks$stressedBottomShaft = null;
		}
	}
}
