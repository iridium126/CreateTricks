package com.iridium126.createmanaindustry.mixin.basin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import com.iridium126.createmanaindustry.content.burner.AllayBurnerBlock;
import com.iridium126.createmanaindustry.content.fluids.mist.MistFieldStore;
import com.iridium126.createmanaindustry.content.recipes.CMIHeatConditions;
import com.iridium126.createmanaindustry.content.recipes.MistRecipe;
import com.iridium126.createmanaindustry.content.recipes.MistRequirement;
import com.simibubi.create.content.processing.basin.BasinBlockEntity;
import com.simibubi.create.content.processing.basin.BasinRecipe;
import com.simibubi.create.content.processing.burner.BlazeBurnerBlock;
import com.simibubi.create.content.processing.recipe.HeatCondition;

import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.crafting.Recipe;
import net.minecraft.world.level.block.state.BlockState;

/**
 * Basin recipe matching extensions:
 * <ul>
 *   <li><b>Mist requirement</b> — a recipe with a {@code mist_requirement} only
 *       matches when that mist is present at the basin position.</li>
 *   <li><b>Allay heat gate</b> — a recipe declaring
 *       {@code "heat_requirement": "allayheated"} only matches when the basin's
 *       below block is an Allay Burner in the ALLAYHEATED state. All other heat
 *       conditions keep Create's exact gate
 *       ({@link HeatCondition#testBlazeBurner}).</li>
 * </ul>
 */
@Mixin(value = BasinRecipe.class, remap = false)
public class BasinRecipeMixin {

    @Inject(method = "apply(Lcom/simibubi/create/content/processing/basin/BasinBlockEntity;Lnet/minecraft/world/item/crafting/Recipe;Z)Z",
            at = @At("HEAD"), cancellable = true)
    private static void createmanaindustry$checkMistRequirement(BasinBlockEntity basin, Recipe<?> recipe,
            boolean test, CallbackInfoReturnable<Boolean> cir) {
        if (!(recipe instanceof MistRecipe mistRecipe))
            return;

        MistRequirement req = mistRecipe.getMistRequirement();
        if (req == null)
            return;

        ResourceLocation presentFluid = MistFieldStore.getFluidType(basin.getLevel(), basin.getBlockPos());
        float conc = MistFieldStore.getConcentration(basin.getLevel(), basin.getBlockPos());

        if (!req.fluidId().equals(presentFluid) || conc < req.minConcentration()) {
            cir.setReturnValue(false);
        }
    }

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
