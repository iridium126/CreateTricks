package com.iridium126.createtricks.mixin;

import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Mutable;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import com.iridium126.createtricks.content.kinetics.TemporaryStress;
import com.iridium126.createtricks.content.kinetics.TemporaryStressModel;
import com.iridium126.createtricks.content.kinetics.TemporaryStressVisual;
import com.simibubi.create.AllPartialModels;
import com.simibubi.create.content.kinetics.base.KineticBlockEntity;
import com.simibubi.create.content.kinetics.base.KineticBlockEntityVisual;
import com.simibubi.create.content.kinetics.base.RotatingInstance;
import com.simibubi.create.content.kinetics.base.SingleAxisRotatingVisual;
import com.simibubi.create.foundation.render.AllInstanceTypes;

import dev.engine_room.flywheel.api.visual.BlockEntityVisual;
import dev.engine_room.flywheel.api.visualization.VisualizationContext;
import dev.engine_room.flywheel.lib.model.Models;
import dev.engine_room.flywheel.lib.model.baked.PartialModel;
import dev.engine_room.flywheel.lib.visualization.SimpleBlockEntityVisualizer;
import net.minecraft.core.Direction;

@Mixin(value = SingleAxisRotatingVisual.class, remap = false)
public abstract class SingleAxisRotatingVisualMixin<T extends KineticBlockEntity> extends KineticBlockEntityVisual<T>
		implements TemporaryStressVisual {
	@Shadow
	@Final
	@Mutable
	protected RotatingInstance rotatingModel;

	@Unique
	private PartialModel createtricks$model;

	@Unique
	private Direction createtricks$sourceDirection;

	@Unique
	private boolean createtricks$stressed;

	protected SingleAxisRotatingVisualMixin(VisualizationContext context, T blockEntity, float partialTick) {
		super(context, blockEntity, partialTick);
	}

	@Inject(method = "of", at = @At("HEAD"), cancellable = true)
	private static <T extends KineticBlockEntity> void createtricks$useStressedModel(PartialModel partial,
			CallbackInfoReturnable<SimpleBlockEntityVisualizer.Factory<T>> cir) {
		if (TemporaryStressModel.hasReplacement(partial))
			cir.setReturnValue((context, be, partialTick) -> createtricks$create(context, be, partialTick, partial,
					Direction.UP));
	}

	@Inject(method = "ofZ", at = @At("HEAD"), cancellable = true)
	private static <T extends KineticBlockEntity> void createtricks$useStressedZModel(PartialModel partial,
			CallbackInfoReturnable<SimpleBlockEntityVisualizer.Factory<T>> cir) {
		if (TemporaryStressModel.hasReplacement(partial))
			cir.setReturnValue((context, be, partialTick) -> createtricks$create(context, be, partialTick, partial,
					Direction.SOUTH));
	}

	@Inject(method = "shaft", at = @At("HEAD"), cancellable = true)
	private static <T extends KineticBlockEntity> void createtricks$useStressedShaft(VisualizationContext context, T be,
			float partialTick, CallbackInfoReturnable<SingleAxisRotatingVisual<T>> cir) {
		SingleAxisRotatingVisual<T> visual = new SingleAxisRotatingVisual<>(context, be, partialTick,
				Models.partial(TemporaryStressModel.shaft(be)));
		((TemporaryStressVisual) visual).createtricks$setTemporaryStressModel(AllPartialModels.SHAFT, Direction.UP);
		cir.setReturnValue(visual);
	}

	@Inject(method = "update", at = @At("HEAD"), cancellable = true)
	private void createtricks$updateModel(float pt, CallbackInfo ci) {
		if (createtricks$model == null)
			return;
		boolean active = TemporaryStress.isActive(blockEntity);
		if (active == createtricks$stressed)
			return;

		rotatingModel.delete();
		createtricks$setupModel();
		ci.cancel();
	}

	@Override
	public void createtricks$setTemporaryStressModel(PartialModel model, Direction sourceDirection) {
		createtricks$model = model;
		createtricks$sourceDirection = sourceDirection;
		createtricks$stressed = TemporaryStress.isActive(blockEntity);
	}

	@Unique
	private static <T extends KineticBlockEntity> BlockEntityVisual<? super T> createtricks$create(VisualizationContext context,
			T be, float partialTick, PartialModel partial, Direction sourceDirection) {
		SingleAxisRotatingVisual<T> visual = new SingleAxisRotatingVisual<>(context, be, partialTick, sourceDirection,
				Models.partial(TemporaryStressModel.replacementOrSelf(be, partial)));
		((TemporaryStressVisual) visual).createtricks$setTemporaryStressModel(partial, sourceDirection);
		return visual;
	}

	@Unique
	private void createtricks$setupModel() {
		createtricks$stressed = TemporaryStress.isActive(blockEntity);
		rotatingModel = instancerProvider().instancer(AllInstanceTypes.ROTATING,
				Models.partial(TemporaryStressModel.replacementOrSelf(blockEntity, createtricks$model)))
			.createInstance()
			.rotateToFace(createtricks$sourceDirection, rotationAxis())
			.setup(blockEntity)
			.setPosition(getVisualPosition());
		rotatingModel.setChanged();
	}
}
