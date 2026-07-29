package com.iridium126.createmanaindustry.mixin;

import java.util.Comparator;
import java.util.List;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import com.iridium126.createmanaindustry.CMIRecipeTypes;
import com.simibubi.create.content.processing.basin.BasinOperatingBlockEntity;

import net.minecraft.world.item.crafting.Recipe;

@Mixin(value = BasinOperatingBlockEntity.class, remap = false)
public class BasinOperatingBlockEntityMixin {

    @Inject(method = "getMatchingRecipes", at = @At("RETURN"))
    private void createmanaindustry$prioritizeHeatedCompacting(CallbackInfoReturnable<List<Recipe<?>>> cir) {
        List<Recipe<?>> list = cir.getReturnValue();
        if (list.size() <= 1)
            return;
        // heated_compacting always takes priority over all other recipe types,
        // regardless of ingredient count. Among the rest, more ingredients first.
        var heatedType = CMIRecipeTypes.HEATED_COMPACTING.getType();
        list.sort(
                Comparator.<Recipe<?>, Boolean>comparing(r -> r.getType() != heatedType)
                        .thenComparing(Comparator.<Recipe<?>, Integer>comparing(
                                r -> r.getIngredients().size()).reversed()));
    }
}
