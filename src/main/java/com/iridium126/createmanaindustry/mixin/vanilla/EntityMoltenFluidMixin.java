package com.iridium126.createmanaindustry.mixin.vanilla;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import com.iridium126.createmanaindustry.CMITags;
import com.iridium126.createmanaindustry.content.fluids.mist.MoltenFluidMistHelper;

import it.unimi.dsi.fastutil.objects.Object2DoubleMap;
import net.minecraft.core.Holder;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.level.material.Fluid;
import net.neoforged.neoforge.fluids.FluidType;

/**
 * Makes entities treat this mod's molten fluids (and any other fluid in the
 * {@code molten_fluid} tag) like lava/fire: {@code isInLava()} returns true in
 * the fluid, and the {@code Entity.move} fire-tick reset is skipped while in the
 * mist (mirror of the fire block's bounding-box presence), so
 * {@link LivingEntityMoltenFluidMistMixin}'s +1 ignition accumulates.
 * <p>
 * The {@code move} redirect targets {@code setRemainingFireTicks} ordinal 0 —
 * fragile if another mod changes those calls. The {@code instanceof
 * LivingEntity} guard leaves non-living/non-mist entities untouched.
 */
@Mixin(Entity.class)
public abstract class EntityMoltenFluidMixin {

    @Shadow
    protected Object2DoubleMap<FluidType> forgeFluidTypeHeight;

    /** Vanilla {@code isInLava()} only matches the shared lava type; extend it to the molten_fluid tag. */
    @Inject(method = "isInLava", at = @At("RETURN"), cancellable = true)
    private void cmi$treatMoltenFluidAsLava(CallbackInfoReturnable<Boolean> cir) {
        if (cir.getReturnValue())
            return;
        for (Holder<Fluid> holder : BuiltInRegistries.FLUID.getTagOrEmpty(CMITags.MOLTEN_FLUID)) {
            if (this.forgeFluidTypeHeight.getDouble(holder.value().getFluidType()) > 0.0D) {
                cir.setReturnValue(true);
                return;
            }
        }
    }

    /**
     * Mirrors the fire block's presence in the bounding box: skip the reset while
     * in the mist so the +1 ignition accumulates. If fireTicks is 0 (a dead zone
     * the {@code == 0} ignite check can't reach), normalize to -1.
     */
    @Redirect(method = "move",
            at = @At(value = "INVOKE",
                    target = "Lnet/minecraft/world/entity/Entity;setRemainingFireTicks(I)V",
                    ordinal = 0))
    private void cmi$skipFireResetInMist(Entity self, int ticks) {
        if (self instanceof LivingEntity le && !le.fireImmune()
                && MoltenFluidMistHelper.isInMoltenFluidMist(self.level(), self.blockPosition())) {
            if (self.getRemainingFireTicks() == 0)
                self.setRemainingFireTicks(-1);
            return;
        }
        self.setRemainingFireTicks(ticks);
    }
}
