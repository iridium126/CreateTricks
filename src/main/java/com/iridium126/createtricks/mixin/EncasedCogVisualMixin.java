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
import com.simibubi.create.content.kinetics.base.IRotate;
import com.simibubi.create.content.kinetics.base.KineticBlockEntity;
import com.simibubi.create.content.kinetics.base.KineticBlockEntityVisual;
import com.simibubi.create.content.kinetics.base.RotatingInstance;
import com.simibubi.create.content.kinetics.simpleRelays.BracketedKineticBlockEntityRenderer;
import com.simibubi.create.content.kinetics.simpleRelays.encased.EncasedCogVisual;
import com.simibubi.create.foundation.render.AllInstanceTypes;

import dev.engine_room.flywheel.api.visual.BlockEntityVisual;
import dev.engine_room.flywheel.api.visualization.VisualizationContext;
import dev.engine_room.flywheel.api.model.Model;
import dev.engine_room.flywheel.lib.model.Models;
import net.createmod.catnip.data.Iterate;
import net.minecraft.core.Direction;
import net.minecraft.world.level.block.Block;

@Mixin(value = EncasedCogVisual.class, remap = false)
public abstract class EncasedCogVisualMixin extends KineticBlockEntityVisual<KineticBlockEntity> {
	@Shadow
	@Final
	private boolean large;

	@Shadow
	@Final
	@Mutable
	protected RotatingInstance rotatingModel;

	@Shadow
	@Final
	@Mutable
	protected RotatingInstance rotatingTopShaft;

	@Shadow
	@Final
	@Mutable
	protected RotatingInstance rotatingBottomShaft;

	@Unique
	private boolean createtricks$stressed;

	protected EncasedCogVisualMixin(VisualizationContext context, KineticBlockEntity blockEntity, float partialTick) {
		super(context, blockEntity, partialTick);
	}

	@Inject(method = "small", at = @At("HEAD"), cancellable = true)
	private static void createtricks$useStressedSmall(VisualizationContext context, KineticBlockEntity be, float partialTick,
			CallbackInfoReturnable<BlockEntityVisual<KineticBlockEntity>> cir) {
		cir.setReturnValue(
				new EncasedCogVisual(context, be, false, partialTick, Models.partial(TemporaryStressModel.shaftlessCogwheel(be))));
	}

	@Inject(method = "large", at = @At("HEAD"), cancellable = true)
	private static void createtricks$useStressedLarge(VisualizationContext context, KineticBlockEntity be, float partialTick,
			CallbackInfoReturnable<BlockEntityVisual<KineticBlockEntity>> cir) {
		cir.setReturnValue(new EncasedCogVisual(context, be, true, partialTick,
				Models.partial(TemporaryStressModel.shaftlessLargeCogwheel(be))));
	}

	@Inject(method = "<init>", at = @At("RETURN"))
	private void createtricks$init(VisualizationContext context, KineticBlockEntity blockEntity, boolean large,
			float partialTick, Model model, CallbackInfo ci) {
		boolean active = TemporaryStress.isActive(blockEntity);
		createtricks$stressed = active;
		if (!active)
			return;

		rotatingModel.delete();
		if (rotatingTopShaft != null)
			rotatingTopShaft.delete();
		if (rotatingBottomShaft != null)
			rotatingBottomShaft.delete();
		createtricks$setupModels();
	}

	@Inject(method = "update", at = @At("HEAD"), cancellable = true)
	private void createtricks$updateModel(float pt, CallbackInfo ci) {
		boolean active = TemporaryStress.isActive(blockEntity);
		if (active == createtricks$stressed)
			return;

		rotatingModel.delete();
		if (rotatingTopShaft != null)
			rotatingTopShaft.delete();
		if (rotatingBottomShaft != null)
			rotatingBottomShaft.delete();
		createtricks$setupModels();
		ci.cancel();
	}

	@Unique
	private void createtricks$setupModels() {
		createtricks$stressed = TemporaryStress.isActive(blockEntity);
		rotatingModel = instancerProvider().instancer(AllInstanceTypes.ROTATING, Models.partial(large
				? TemporaryStressModel.shaftlessLargeCogwheel(blockEntity)
				: TemporaryStressModel.shaftlessCogwheel(blockEntity)))
			.createInstance();
		rotatingModel.setup(blockEntity)
			.setPosition(getVisualPosition())
			.rotateToFace(rotationAxis())
			.setChanged();

		rotatingTopShaft = null;
		rotatingBottomShaft = null;
		Block block = blockState.getBlock();
		if (!(block instanceof IRotate rotate))
			return;

		for (Direction direction : Iterate.directionsInAxis(rotationAxis())) {
			if (!rotate.hasShaftTowards(blockEntity.getLevel(), blockEntity.getBlockPos(), blockState, direction))
				continue;

			RotatingInstance shaft = instancerProvider().instancer(AllInstanceTypes.ROTATING,
					Models.partial(TemporaryStressModel.shaftHalf(blockEntity)))
				.createInstance();
			shaft.setup(blockEntity)
				.setPosition(getVisualPosition())
				.rotateToFace(Direction.SOUTH, direction)
				.setChanged();
			if (large)
				shaft.setRotationOffset(BracketedKineticBlockEntityRenderer.getShaftAngleOffset(rotationAxis(), pos));

			if (direction.getAxisDirection() == Direction.AxisDirection.POSITIVE)
				rotatingTopShaft = shaft;
			else
				rotatingBottomShaft = shaft;
		}
	}
}
