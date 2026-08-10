package com.iridium126.createmanaindustry.mixin.vanilla;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import com.iridium126.createmanaindustry.CreateManaIndustry;
import com.iridium126.createmanaindustry.content.fluids.mist.MistFieldStore;

import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.level.Level;

/**
 * Ignites living entities standing in Molten Rose Quartz mist, exactly mirroring
 * vanilla {@code BaseFireBlock.entityInside}: while in the mist each tick the
 * fire ticks are nudged +1 and, crossing 0 (the "ready-to-ignite" state), the
 * entity ignites for 8 seconds. Fire-immune entities are never ignited.
 * <p>
 * Burning — the countdown, the 1.0 onFire damage per second, and the 4x faster
 * burn-out for fire-immune entities — is entirely left to vanilla
 * {@code Entity.baseTick}. The check runs at the end of {@code LivingEntity.tick}
 * (after baseTick), mirroring the fire block's {@code entityInside} timing.
 * <p>
 * The dominant mist at the entity's position is used, matching the query the
 * condenser and mana cogwheel use ({@code MistFieldStore.getFluidType}).
 */
@Mixin(LivingEntity.class)
public abstract class LivingEntityMoltenRoseQuartzMistMixin {

    private static final ResourceLocation MOLTEN_ROSE_QUARTZ_ID =
            CreateManaIndustry.modLoc("molten_rose_quartz");

    @Inject(method = "tick", at = @At("RETURN"))
    private void cmi$igniteInMoltenRoseQuartzMist(CallbackInfo ci) {
        LivingEntity self = (LivingEntity) (Object) this;
        Level level = self.level();
        if (level.isClientSide || self.fireImmune() || !self.isAlive())
            return;
        if (!MOLTEN_ROSE_QUARTZ_ID.equals(MistFieldStore.getFluidType(level, self.blockPosition())))
            return;
        self.setRemainingFireTicks(self.getRemainingFireTicks() + 1);
        if (self.getRemainingFireTicks() == 0)
            self.igniteForSeconds(8.0F);
    }
}
