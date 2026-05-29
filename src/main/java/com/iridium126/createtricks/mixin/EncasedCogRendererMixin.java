package com.iridium126.createtricks.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyArgs;
import org.spongepowered.asm.mixin.injection.invoke.arg.Args;

import com.iridium126.createtricks.content.kinetics.TemporaryStressModel;
import com.simibubi.create.content.kinetics.simpleRelays.ICogWheel;
import com.simibubi.create.content.kinetics.simpleRelays.SimpleKineticBlockEntity;
import com.simibubi.create.content.kinetics.simpleRelays.encased.EncasedCogRenderer;

@Mixin(value = EncasedCogRenderer.class, remap = false)
public class EncasedCogRendererMixin {
	@ModifyArgs(method = "renderSafe", at = @At(value = "INVOKE", target = "Lnet/createmod/catnip/render/CachedBuffers;partialFacing(Ldev/engine_room/flywheel/lib/model/baked/PartialModel;Lnet/minecraft/world/level/block/state/BlockState;Lnet/minecraft/core/Direction;)Lnet/createmod/catnip/render/SuperByteBuffer;"))
	private void createtricks$shaftModel(Args args) {
		SimpleKineticBlockEntity be = (SimpleKineticBlockEntity) (Object) this;
		args.set(0, TemporaryStressModel.shaftHalf(be));
	}

	@ModifyArgs(method = "getRotatedModel", at = @At(value = "INVOKE", target = "Lnet/createmod/catnip/render/CachedBuffers;partialFacingVertical(Ldev/engine_room/flywheel/lib/model/baked/PartialModel;Lnet/minecraft/world/level/block/state/BlockState;Lnet/minecraft/core/Direction;)Lnet/createmod/catnip/render/SuperByteBuffer;"))
	private void createtricks$cogModel(Args args) {
		SimpleKineticBlockEntity be = (SimpleKineticBlockEntity) (Object) this;
		args.set(0, ICogWheel.isLargeCog(be.getBlockState())
				? TemporaryStressModel.shaftlessLargeCogwheel(be)
				: TemporaryStressModel.shaftlessCogwheel(be));
	}
}
