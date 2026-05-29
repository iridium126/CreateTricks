package com.iridium126.createtricks.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyArgs;
import org.spongepowered.asm.mixin.injection.invoke.arg.Args;

import com.iridium126.createtricks.content.kinetics.TemporaryStressModel;
import com.simibubi.create.content.kinetics.simpleRelays.BracketedKineticBlockEntity;
import com.simibubi.create.content.kinetics.simpleRelays.BracketedKineticBlockEntityRenderer;

@Mixin(value = BracketedKineticBlockEntityRenderer.class, remap = false)
public class BracketedKineticBlockEntityRendererMixin {
	@ModifyArgs(method = "renderSafe", at = @At(value = "INVOKE", target = "Lnet/createmod/catnip/render/CachedBuffers;partialFacingVertical(Ldev/engine_room/flywheel/lib/model/baked/PartialModel;Lnet/minecraft/world/level/block/state/BlockState;Lnet/minecraft/core/Direction;)Lnet/createmod/catnip/render/SuperByteBuffer;", ordinal = 0))
	private void createtricks$largeCogModel(Args args) {
		BracketedKineticBlockEntity be = (BracketedKineticBlockEntity) (Object) this;
		args.set(0, TemporaryStressModel.shaftlessLargeCogwheel(be));
	}

	@ModifyArgs(method = "renderSafe", at = @At(value = "INVOKE", target = "Lnet/createmod/catnip/render/CachedBuffers;partialFacingVertical(Ldev/engine_room/flywheel/lib/model/baked/PartialModel;Lnet/minecraft/world/level/block/state/BlockState;Lnet/minecraft/core/Direction;)Lnet/createmod/catnip/render/SuperByteBuffer;", ordinal = 1))
	private void createtricks$largeCogShaftModel(Args args) {
		BracketedKineticBlockEntity be = (BracketedKineticBlockEntity) (Object) this;
		args.set(0, TemporaryStressModel.cogwheelShaft(be));
	}
}
