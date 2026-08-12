package com.iridium126.createmanaindustry.mixin.vanilla;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import com.iridium126.createmanaindustry.content.fluids.mist.MoltenFluidMistHelper;

import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.level.Level;

/**
 * Makes mist of any {@code molten_fluid} tag member ignite living entities
 * exactly like a fire block ({@code BaseFireBlock.entityInside}): +1 fire ticks
 * each tick, ignite for 8 s on crossing 0, plus 1.0 inFire damage. Fire-immune
 * entities are never ignited. The reset-skip half lives in
 * {@link EntityMoltenFluidMixin}.
 */
@Mixin(LivingEntity.class)
public abstract class LivingEntityMoltenFluidMistMixin {

    @Inject(method = "tick", at = @At("RETURN"))
    private void cmi$igniteInMoltenFluidMist(CallbackInfo ci) {
        LivingEntity self = (LivingEntity) (Object) this;
        Level level = self.level();
        if (level.isClientSide || !self.isAlive())
            return;
        if (!MoltenFluidMistHelper.isInMoltenFluidMist(level, self.blockPosition()))
            return;
        if (!self.fireImmune()) {
            self.setRemainingFireTicks(self.getRemainingFireTicks() + 1);
            if (self.getRemainingFireTicks() == 0)
                self.igniteForSeconds(8.0F);
        }
        self.hurt(level.damageSources().inFire(), 1.0F);
    }
}
