package com.iridium126.createmanaindustry.mixin.kinetics;

import java.util.List;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import com.iridium126.createmanaindustry.CMIRecipeTypes;
import com.iridium126.createmanaindustry.CreateManaIndustry;
import com.iridium126.createmanaindustry.compat.trickster.TricksterManaAccess;
import com.iridium126.createmanaindustry.content.recipes.HexItemDataTransfer;
import com.simibubi.create.content.kinetics.press.MechanicalPressBlockEntity;
import com.simibubi.create.foundation.recipe.RecipeApplier;

import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.Recipe;
import net.minecraft.world.item.crafting.RecipeHolder;
import net.minecraft.world.level.Level;

/**
 * Mechanical Press extensions:
 * <ul>
 *   <li>Accepts {@code heated_compacting} and {@code mist_compacting} recipes
 *       in the static recipe filters.</li>
 *   <li>Post-processes recipe outputs to transfer data from incomplete items to
 *       final items — both Trickster knot mana and Hexcasting hex-item data
 *       from a single set of {@code @Redirect} injections (Mixin only allows
 *       one redirect per target method).</li>
 * </ul>
 */
@Mixin(value = MechanicalPressBlockEntity.class, remap = false)
public class MechanicalPressBlockEntityMixin {

    @Inject(method = "matchStaticFilters", at = @At("RETURN"), cancellable = true)
    private void createmanaindustry$matchCustomCompacting(RecipeHolder<? extends Recipe<?>> recipe,
            CallbackInfoReturnable<Boolean> cir) {
        if (cir.getReturnValue())
            return;
        var type = recipe.value().getType();
        if (type == CMIRecipeTypes.HEATED_COMPACTING.getType()
                || type == CMIRecipeTypes.MIST_COMPACTING.getType()) {
            cir.setReturnValue(true);
        }
    }

    @Redirect(method = "tryProcessInWorld",
            at = @At(value = "INVOKE",
                    target = "Lcom/simibubi/create/foundation/recipe/RecipeApplier;applyRecipeOn(Lnet/minecraft/world/entity/item/ItemEntity;Lnet/minecraft/world/item/crafting/Recipe;Z)V"))
    private void createmanaindustry$transferOnEntityPress(ItemEntity entity, Recipe<?> recipe,
            boolean respectChances) {
        ItemStack inputCopy = entity.getItem().copy();
        RecipeApplier.applyRecipeOn(entity, recipe, respectChances);
        ItemStack result = entity.getItem();

        // Knot mana transfer (only when Trickster is present)
        if (CreateManaIndustry.TRICKSTER_ACTIVE) {
            ItemStack knotResult = TricksterManaAccess.applyKnotTransfer(entity.level(), inputCopy, result);
            if (knotResult != result) {
                entity.setItem(knotResult);
            }
        }

        // Hex item data transfer (only when Hexcasting is present)
        if (CreateManaIndustry.HEX_ACTIVE) {
            ItemStack hexResult = HexItemDataTransfer.applyPressTransfer(inputCopy, result);
            if (hexResult != result) {
                entity.setItem(hexResult);
            }
        }
    }

    @Redirect(method = {"tryProcessInWorld", "tryProcessOnBelt"},
            at = @At(value = "INVOKE",
                    target = "Lcom/simibubi/create/foundation/recipe/RecipeApplier;applyRecipeOn(Lnet/minecraft/world/level/Level;Lnet/minecraft/world/item/ItemStack;Lnet/minecraft/world/item/crafting/Recipe;Z)Ljava/util/List;"))
    private List<ItemStack> createmanaindustry$transferOnListPress(Level level, ItemStack stack, Recipe<?> recipe,
            boolean respectChances) {
        ItemStack inputCopy = stack.copy();
        List<ItemStack> results = RecipeApplier.applyRecipeOn(level, stack, recipe, respectChances);
        for (int i = 0; i < results.size(); i++) {
            ItemStack result = results.get(i);

            // Knot mana transfer (only when Trickster is present)
            if (CreateManaIndustry.TRICKSTER_ACTIVE) {
                ItemStack knotResult = TricksterManaAccess.applyKnotTransfer(level, inputCopy, result);
                if (knotResult != result) {
                    result = knotResult;
                }
            }

            // Hex item data transfer (only when Hexcasting is present)
            if (CreateManaIndustry.HEX_ACTIVE) {
                ItemStack hexResult = HexItemDataTransfer.applyPressTransfer(inputCopy, result);
                if (hexResult != result) {
                    result = hexResult;
                }
            }

            if (result != results.get(i)) {
                results.set(i, result);
            }
        }
        return results;
    }
}
