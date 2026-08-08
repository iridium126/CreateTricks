package com.iridium126.createmanaindustry.mixin.jei;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.ModifyArg;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import com.iridium126.createmanaindustry.CMIBlocks;
import com.iridium126.createmanaindustry.content.recipes.CMIHeatConditions;
import com.simibubi.create.AllBlocks;
import com.simibubi.create.AllItems;
import com.simibubi.create.compat.jei.category.BasinCategory;
import com.simibubi.create.content.processing.basin.BasinRecipe;
import com.simibubi.create.content.processing.recipe.HeatCondition;

import mezz.jei.api.gui.builder.IRecipeLayoutBuilder;
import mezz.jei.api.recipe.IFocusGroup;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

/**
 * Swaps the static heat-slot items added by {@link BasinCategory#setRecipe}
 * when the recipe is {@code allayheated}, across every {@code BasinCategory}
 * subclass — Create's Mixing/Packing and CMI's own categories alike:
 * <ul>
 *   <li>the blaze burner icon (RENDER_ONLY slot) → the allay burner</li>
 *   <li>the blaze cake catalyst → an amethyst shard (the allay burner's real fuel)</li>
 * </ul>
 * Result stacks are left untouched: the swap only rewrites stacks that are the
 * blaze burner or blaze cake, so a recipe that legitimately outputs either is
 * unaffected. Because {@code ALLAYHEATED.testBlazeBurner} always returns false
 * (see {@code HeatConditionMixin}), Create's slot-present gates already show
 * both slots for allayheated recipes — only the items need replacing.
 */
@Mixin(value = BasinCategory.class, remap = false)
public abstract class BasinCategoryMixin {

    @Unique
    private boolean createmanaindustry$allayHeated;

    @Inject(method = "setRecipe", at = @At("HEAD"))
    private void createmanaindustry$captureAllayHeated(IRecipeLayoutBuilder builder, BasinRecipe recipe,
            IFocusGroup focuses, CallbackInfo ci) {
        HeatCondition allayHeated = CMIHeatConditions.ALLAY_HEATED;
        createmanaindustry$allayHeated = allayHeated != null && recipe.getRequiredHeat() == allayHeated;
    }

    @ModifyArg(method = "setRecipe", index = 0, at = @At(value = "INVOKE",
            target = "Lmezz/jei/api/gui/builder/IRecipeSlotBuilder;addItemStack(Lnet/minecraft/world/item/ItemStack;)Lmezz/jei/api/gui/builder/IIngredientAcceptor;"))
    private ItemStack createmanaindustry$swapHeatSlotItems(ItemStack stack) {
        if (!createmanaindustry$allayHeated)
            return stack;
        if (stack.is(AllBlocks.BLAZE_BURNER.get()
            .asItem()))
            return CMIBlocks.ALLAY_BURNER.asStack();
        if (stack.is(AllItems.BLAZE_CAKE.get()))
            return new ItemStack(Items.AMETHYST_SHARD);
        return stack;
    }
}
