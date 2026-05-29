package com.iridium126.createtricks.mixin;

import org.spongepowered.asm.mixin.Mixin;

import com.iridium126.createtricks.content.kinetics.TemporaryStress;
import com.iridium126.createtricks.content.kinetics.TemporaryStressProvider;
import com.simibubi.create.content.kinetics.base.KineticBlockEntity;

@Mixin(value = KineticBlockEntity.class, remap = false)
public class KineticBlockEntityMixin implements TemporaryStressProvider {
	@Override
	public float createtricks$calculateStressApplied() {
		KineticBlockEntity be = (KineticBlockEntity) (Object) this;
		return be.calculateStressApplied() + TemporaryStress.getStress(be);
	}
}
