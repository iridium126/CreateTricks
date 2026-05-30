package com.iridium126.createtricks.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

import com.simibubi.create.content.kinetics.base.KineticBlockEntity;
import com.simibubi.create.content.kinetics.base.KineticEffectHandler;

@Mixin(value = KineticBlockEntity.class, remap = false)
public interface KineticBlockEntityAccessor {
	@Accessor("effects")
	KineticEffectHandler createtricks$getEffects();
}
