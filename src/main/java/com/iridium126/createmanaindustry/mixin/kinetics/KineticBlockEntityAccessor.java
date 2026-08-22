package com.iridium126.createmanaindustry.mixin.kinetics;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

import com.simibubi.create.content.kinetics.base.KineticBlockEntity;
import com.simibubi.create.content.kinetics.base.KineticEffectHandler;

/**
 * Exposes {@link KineticBlockEntity} internals to the temporary kinetics
 * system. Must stay an interface: applied mixin classes are marked invalid by
 * the Mixin framework and cannot be loaded as regular classes at runtime, so
 * external code has to access generated methods through the interface.
 */
@Mixin(value = KineticBlockEntity.class, remap = false)
public interface KineticBlockEntityAccessor {
    @Accessor("effects")
    KineticEffectHandler createmanaindustry$getEffects();

    @Accessor("source")
    net.minecraft.core.BlockPos createmanaindustry$getSource();
}
