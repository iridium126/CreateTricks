package com.iridium126.createtricks.mixin;

import java.util.List;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import com.iridium126.createtricks.content.kinetics.TemporaryStress;
import com.simibubi.create.content.kinetics.base.KineticBlockEntity;

import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.world.level.block.entity.BlockEntity;

@Mixin(value = KineticBlockEntity.class, remap = false)
public class KineticBlockEntityMixin {
	@Inject(method = "getGeneratedSpeed", at = @At("RETURN"), cancellable = true)
	private void createtricks$addTemporaryGeneratedSpeed(CallbackInfoReturnable<Float> cir) {
		KineticBlockEntity be = (KineticBlockEntity) (Object) this;
		float speed = TemporaryStress.getSpeed(be);
		if (speed != 0)
			cir.setReturnValue(speed);
	}

	@Inject(method = "calculateAddedStressCapacity", at = @At("RETURN"), cancellable = true)
	private void createtricks$addTemporaryStressCapacity(CallbackInfoReturnable<Float> cir) {
		KineticBlockEntity be = (KineticBlockEntity) (Object) this;
		cir.setReturnValue(cir.getReturnValueF() + TemporaryStress.getStress(be));
	}

	@Inject(method = "isSource", at = @At("RETURN"), cancellable = true)
	private void createtricks$useTemporarySource(CallbackInfoReturnable<Boolean> cir) {
		KineticBlockEntity be = (KineticBlockEntity) (Object) this;
		if (TemporaryStress.isSource(be))
			cir.setReturnValue(true);
	}

	@Inject(method = "removeSource", at = @At("HEAD"))
	private void createtricks$rememberTemporarySource(CallbackInfo ci) {
		KineticBlockEntity be = (KineticBlockEntity) (Object) this;
		TemporaryStress.removeSource(be);
	}

	@Inject(method = "setSource", at = @At("RETURN"))
	private void createtricks$updateTemporaryReactivation(BlockPos source, CallbackInfo ci) {
		KineticBlockEntity be = (KineticBlockEntity) (Object) this;
		if (source == null || be.getLevel() == null)
			return;
		BlockEntity sourceBE = be.getLevel()
			.getBlockEntity(source);
		TemporaryStress.setSource(be, sourceBE);
	}

	@Inject(method = "tick", at = @At("RETURN"))
	private void createtricks$tickTemporarySource(CallbackInfo ci) {
		TemporaryStress.tickBlockEntity((KineticBlockEntity) (Object) this);
	}

	@Inject(method = "addToGoggleTooltip", at = @At("RETURN"), cancellable = true)
	private void createtricks$addTemporaryGeneratorStats(List<Component> tooltip, boolean isPlayerSneaking,
			CallbackInfoReturnable<Boolean> cir) {
		KineticBlockEntity be = (KineticBlockEntity) (Object) this;
		if (TemporaryStress.addToGoggleTooltip(be, tooltip))
			cir.setReturnValue(true);
	}
}
