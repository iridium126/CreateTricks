package com.iridium126.createtricks.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import com.iridium126.createtricks.content.kinetics.TemporaryStress;
import com.simibubi.create.content.kinetics.base.KineticBlockEntity;

@Mixin(value = KineticBlockEntity.class, remap = false)
public class KineticBlockEntityMixin {
	@Inject(method = "getGeneratedSpeed", at = @At("RETURN"), cancellable = true)
	private void createtricks$addTemporaryGeneratedSpeed(CallbackInfoReturnable<Float> cir) {
		KineticBlockEntity be = (KineticBlockEntity) (Object) this;
		cir.setReturnValue(Math.max(cir.getReturnValueF(), TemporaryStress.getSpeed(be)));
	}

	@Inject(method = "calculateAddedStressCapacity", at = @At("RETURN"), cancellable = true)
	private void createtricks$addTemporaryStressCapacity(CallbackInfoReturnable<Float> cir) {
		KineticBlockEntity be = (KineticBlockEntity) (Object) this;
		cir.setReturnValue(cir.getReturnValueF() + TemporaryStress.getStress(be));
	}
}
