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

import net.minecraft.world.item.crafting.Recipe;
import net.minecraft.world.level.block.state.BlockState;

/**
 * Basin recipe matching extensions:
 * <ul>
 *   <li><b>Mist requirement</b> — a recipe with a {@code mist_requirement} only
 *       matches when that mist is present at the basin position. When the
 *       requirement also declares {@code amount} &gt; 0, the recipe additionally
 *       only matches once the field physically holds that much capacity, and a
 *       reservation is registered so the condenser yields while it waits.</li>
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

        if (!MistFieldStore.hasMatchingMist(basin.getLevel(), basin.getBlockPos(),
                req.fluidId(), req.minConcentration())) {
            cir.setReturnValue(false);
            return;
        }

        // Full-or-nothing capacity gate (matching path only): a recipe that
        // consumes mist must not match until the field physically holds its
        // amount — mirroring how Create refuses to match a recipe whose item
        // ingredients are missing. While it waits, register a reservation so the
        // condenser yields and the field can accumulate. The completion path
        // (test=false) skips this: by then the reservation has been protecting
        // the capacity throughout processing.
        if (test && req.amount() > 0) {
            long available = MistFieldStore.availableCapacity(
                    basin.getLevel(), basin.getBlockPos(), req.fluidId(), false);
            if (available < req.amount()) {
                MistFieldStore.reserve(basin.getLevel(), basin.getBlockPos(), req.fluidId(), req.amount());
                cir.setReturnValue(false);
            }
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
