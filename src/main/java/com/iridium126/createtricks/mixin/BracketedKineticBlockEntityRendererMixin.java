package com.iridium126.createtricks.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.ModifyArgs;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.invoke.arg.Args;

import com.mojang.blaze3d.vertex.PoseStack;
import com.iridium126.createtricks.content.kinetics.TemporaryStressModel;
import com.simibubi.create.content.kinetics.simpleRelays.BracketedKineticBlockEntity;
import com.simibubi.create.content.kinetics.simpleRelays.BracketedKineticBlockEntityRenderer;

import net.minecraft.client.renderer.MultiBufferSource;

@Mixin(value = BracketedKineticBlockEntityRenderer.class, remap = false)
public class BracketedKineticBlockEntityRendererMixin {
	@Unique
	private BracketedKineticBlockEntity createtricks$renderedBlockEntity;

	@Inject(method = "renderSafe(Lcom/simibubi/create/content/kinetics/simpleRelays/BracketedKineticBlockEntity;FLcom/mojang/blaze3d/vertex/PoseStack;Lnet/minecraft/client/renderer/MultiBufferSource;II)V", at = @At("HEAD"))
	private void createtricks$captureBlockEntity(BracketedKineticBlockEntity be, float partialTicks, PoseStack ms,
			MultiBufferSource buffer, int light, int overlay, CallbackInfo ci) {
		createtricks$renderedBlockEntity = be;
	}

	@Inject(method = "renderSafe(Lcom/simibubi/create/content/kinetics/simpleRelays/BracketedKineticBlockEntity;FLcom/mojang/blaze3d/vertex/PoseStack;Lnet/minecraft/client/renderer/MultiBufferSource;II)V", at = @At("RETURN"))
	private void createtricks$clearBlockEntity(BracketedKineticBlockEntity be, float partialTicks, PoseStack ms,
			MultiBufferSource buffer, int light, int overlay, CallbackInfo ci) {
		createtricks$renderedBlockEntity = null;
	}

	@ModifyArgs(method = "renderSafe", at = @At(value = "INVOKE", target = "Lnet/createmod/catnip/render/CachedBuffers;partialFacingVertical(Ldev/engine_room/flywheel/lib/model/baked/PartialModel;Lnet/minecraft/world/level/block/state/BlockState;Lnet/minecraft/core/Direction;)Lnet/createmod/catnip/render/SuperByteBuffer;", ordinal = 0))
	private void createtricks$largeCogModel(Args args) {
		BracketedKineticBlockEntity be = createtricks$renderedBlockEntity;
		if (be == null)
			return;
		args.set(0, TemporaryStressModel.shaftlessLargeCogwheel(be));
	}

	@ModifyArgs(method = "renderSafe", at = @At(value = "INVOKE", target = "Lnet/createmod/catnip/render/CachedBuffers;partialFacingVertical(Ldev/engine_room/flywheel/lib/model/baked/PartialModel;Lnet/minecraft/world/level/block/state/BlockState;Lnet/minecraft/core/Direction;)Lnet/createmod/catnip/render/SuperByteBuffer;", ordinal = 1))
	private void createtricks$largeCogShaftModel(Args args) {
		BracketedKineticBlockEntity be = createtricks$renderedBlockEntity;
		if (be == null)
			return;
		args.set(0, TemporaryStressModel.cogwheelShaft(be));
	}
}
