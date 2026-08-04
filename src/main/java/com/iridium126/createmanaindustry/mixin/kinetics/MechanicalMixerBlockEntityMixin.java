package com.iridium126.createmanaindustry.mixin.kinetics;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import com.iridium126.createmanaindustry.CMIRecipeTypes;
import com.simibubi.create.content.kinetics.mixer.MechanicalMixerBlockEntity;

import net.minecraft.world.item.crafting.Recipe;
import net.minecraft.world.item.crafting.RecipeHolder;

/**
 * Allows the Mechanical Mixer to accept {@code createmanaindustry:mist_mixing}
 * recipes in addition to the vanilla {@code create:mixing} type.
 */
@Mixin(value = MechanicalMixerBlockEntity.class, remap = false)
public class MechanicalMixerBlockEntityMixin {

    @Inject(method = "matchStaticFilters", at = @At("RETURN"), cancellable = true)
    private void createmanaindustry$matchMistMixing(RecipeHolder<? extends Recipe<?>> recipe,
            CallbackInfoReturnable<Boolean> cir) {
        if (cir.getReturnValue())
            return;
        if (recipe.value().getType() == CMIRecipeTypes.MIST_MIXING.getType()) {
            cir.setReturnValue(true);
        }
    }
}
