package com.iridium126.createmanaindustry.mixin.vanilla;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import com.iridium126.createmanaindustry.CMIFluids;

import it.unimi.dsi.fastutil.objects.Object2DoubleMap;
import net.minecraft.world.entity.Entity;
import net.neoforged.neoforge.fluids.FluidType;

/**
 * Makes {@link Entity#isInLava()} also return {@code true} while the entity is
 * immersed in Molten Rose Quartz.
 * <p>
 * Vanilla's {@code isInLava()} checks the fluid type height of the shared
 * {@code NeoForgeMod.LAVA_TYPE} by identity, so a custom FluidType (needed to
 * render the rose-quartz textures) never triggers lava damage. This injection
 * extends the check to our fluid's type, so entities take the full vanilla
 * {@code lavaHurt()} treatment (ignite 15 s + 4 damage/tick).
 */
@Mixin(Entity.class)
public abstract class EntityIsInLavaMixin {

    @Shadow
    protected Object2DoubleMap<FluidType> forgeFluidTypeHeight;

    @Inject(method = "isInLava", at = @At("RETURN"), cancellable = true)
    private void cmi$treatMoltenRoseQuartzAsLava(CallbackInfoReturnable<Boolean> cir) {
        if (!cir.getReturnValue()
                && this.forgeFluidTypeHeight.getDouble(CMIFluids.MOLTEN_ROSE_QUARTZ.getType()) > 0.0D)
            cir.setReturnValue(true);
    }
}
