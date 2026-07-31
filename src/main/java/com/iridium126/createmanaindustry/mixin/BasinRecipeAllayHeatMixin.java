package com.iridium126.createmanaindustry.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

import com.iridium126.createmanaindustry.content.burner.AllayBurnerBlock;
import com.iridium126.createmanaindustry.content.recipes.CMIHeatConditions;
import com.simibubi.create.content.processing.basin.BasinBlockEntity;
import com.simibubi.create.content.processing.basin.BasinRecipe;
import com.simibubi.create.content.processing.burner.BlazeBurnerBlock;
import com.simibubi.create.content.processing.recipe.HeatCondition;

import net.minecraft.world.item.crafting.Recipe;
import net.minecraft.world.level.block.state.BlockState;

/**
 * Gates the {@code allayheated} heat requirement: a recipe declaring
 * {@code "heat_requirement": "allayheated"} only matches when the basin's
 * below block is an Allay Burner in the ALLAYHEATED state. All other heat
 * conditions keep Create's exact gate ({@link HeatCondition#testBlazeBurner}).
 * <p>
 * The heat check lives inside the static {@code BasinRecipe.apply} method
 * (same call site {@link BasinRecipeMistMixin} targets).
 */
@Mixin(value = BasinRecipe.class, remap = false)
public class BasinRecipeAllayHeatMixin {

    @Redirect(method = "apply(Lcom/simibubi/create/content/processing/basin/BasinBlockEntity;Lnet/minecraft/world/item/crafting/Recipe;Z)Z",
              at = @At(value = "INVOKE",
                       target = "Lcom/simibubi/create/content/processing/recipe/HeatCondition;testBlazeBurner(Lcom/simibubi/create/content/processing/burner/BlazeBurnerBlock$HeatLevel;)Z"))
    private static boolean createmanaindustry$allayHeatedGate(HeatCondition condition,
            BlazeBurnerBlock.HeatLevel level, BasinBlockEntity basin, Recipe<?> recipe, boolean test) {
        HeatCondition allayHeated = CMIHeatConditions.ALLAY_HEATED;
        if (allayHeated != null && condition == allayHeated) {
            BlockState below = basin.getLevel().getBlockState(basin.getBlockPos().below(1));
            return below.getBlock() instanceof AllayBurnerBlock
                    && below.getValue(AllayBurnerBlock.HEAT_LEVEL) == AllayBurnerBlock.HeatLevel.ALLAYHEATED;
        }
        return condition.testBlazeBurner(level);
    }
}
