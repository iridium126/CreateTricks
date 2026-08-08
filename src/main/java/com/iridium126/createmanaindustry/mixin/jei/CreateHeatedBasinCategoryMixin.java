package com.iridium126.createmanaindustry.mixin.jei;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import com.iridium126.createmanaindustry.compat.jei.category.animations.AnimatedAllayBurner;
import com.iridium126.createmanaindustry.content.recipes.CMIHeatConditions;
import com.simibubi.create.compat.jei.category.MixingCategory;
import com.simibubi.create.compat.jei.category.PackingCategory;
import com.simibubi.create.compat.jei.category.animations.AnimatedBlazeBurner;
import com.simibubi.create.content.processing.basin.BasinRecipe;
import com.simibubi.create.content.processing.recipe.HeatCondition;

import mezz.jei.api.gui.ingredient.IRecipeSlotsView;
import net.minecraft.client.gui.GuiGraphics;

/**
 * Renders the animated allay burner instead of the animated blaze burner in
 * Create's own heated basin categories when the recipe is {@code allayheated}.
 * Both {@link MixingCategory} and {@link PackingCategory} have the identical
 * burner draw block:
 * {@code if (requiredHeat != NONE) heater.withHeat(...).draw(...);}
 * <p>
 * The {@code @Redirect} of {@code AnimatedBlazeBurner.draw} can't see the
 * recipe (only the receiver and the draw coordinates), so the recipe is
 * captured into a {@code @Unique} field by a HEAD inject first. A cancellable
 * inject at the draw call would return from the whole method and skip the
 * mixer/press draw, hence the redirect.
 * <p>
 * The {@code withHeat} call is left untouched: for non-allay recipes the blaze
 * burner's heat is already set when {@code draw} runs, so the redirect just
 * forwards to {@code heater.draw}. The static slots are handled globally by
 * {@code BasinCategoryMixin}.
 */
@Mixin(value = { MixingCategory.class, PackingCategory.class }, remap = false)
public abstract class CreateHeatedBasinCategoryMixin {

    private static final String DRAW =
        "draw(Lcom/simibubi/create/content/processing/basin/BasinRecipe;Lmezz/jei/api/gui/ingredient/IRecipeSlotsView;Lnet/minecraft/client/gui/GuiGraphics;DD)V";

    @Unique
    private boolean createmanaindustry$allayHeated;

    @Unique
    private AnimatedAllayBurner createmanaindustry$allayBurner;

    @Inject(method = DRAW, at = @At("HEAD"))
    private void createmanaindustry$captureAllayHeated(BasinRecipe recipe, IRecipeSlotsView slotsView,
            GuiGraphics graphics, double mouseX, double mouseY, CallbackInfo ci) {
        HeatCondition allayHeated = CMIHeatConditions.ALLAY_HEATED;
        createmanaindustry$allayHeated = allayHeated != null && recipe.getRequiredHeat() == allayHeated;
    }

    @Redirect(method = DRAW, at = @At(value = "INVOKE",
            target = "Lcom/simibubi/create/compat/jei/category/animations/AnimatedBlazeBurner;draw(Lnet/minecraft/client/gui/GuiGraphics;II)V"))
    private void createmanaindustry$drawHeater(AnimatedBlazeBurner heater, GuiGraphics graphics, int x, int y) {
        if (createmanaindustry$allayHeated)
            createmanaindustry$allayBurner().draw(graphics, x, y);
        else
            heater.draw(graphics, x, y);
    }

    @Unique
    private AnimatedAllayBurner createmanaindustry$allayBurner() {
        AnimatedAllayBurner burner = createmanaindustry$allayBurner;
        if (burner == null)
            burner = createmanaindustry$allayBurner = new AnimatedAllayBurner();
        return burner;
    }
}
