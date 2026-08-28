package com.iridium126.createmanaindustry.mixin.vanilla;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

import net.minecraft.world.entity.LivingEntity;

/**
 * Exposes the protected riptide spin-attack damage of {@link LivingEntity} so
 * the GPU-particle melee pipeline ({@code CMIParticleEngine.syntheticAttack})
 * can mirror {@code Player.attack}'s {@code isAutoSpinAttack() ?
 * autoSpinAttackDmg : ATTACK_DAMAGE} branch verbatim on the client. The field
 * lives on LivingEntity (Player inherits it), has no public accessor, and the
 * particle attack is computed client-side.
 */
@Mixin(LivingEntity.class)
public interface LivingEntityAccessor {

    @Accessor("autoSpinAttackDmg")
    float createmanaindustry$getAutoSpinAttackDmg();
}
