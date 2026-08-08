package com.iridium126.createmanaindustry.compat.jei.category;

import javax.annotation.ParametersAreNonnullByDefault;

import com.iridium126.createmanaindustry.compat.jei.category.animations.AnimatedAllayBurner;
import com.iridium126.createmanaindustry.content.recipes.CMIHeatConditions;
import com.simibubi.create.compat.jei.category.BasinCategory;
import com.simibubi.create.compat.jei.category.CreateRecipeCategory;
import com.simibubi.create.compat.jei.category.animations.AnimatedBlazeBurner;
import com.simibubi.create.content.processing.basin.BasinRecipe;
import com.simibubi.create.content.processing.recipe.HeatCondition;

import net.minecraft.client.gui.GuiGraphics;

/**
 * Shared base for CMI's basin JEI categories. Holds both heat-source
 * animations and draws the right one per recipe: the animated allay burner for
 * {@code allayheated} recipes, the animated blaze burner otherwise.
 * <p>
 * The static item slots (blaze burner icon / blaze cake) are swapped globally
 * by {@code BasinCategoryMixin}, so this class deliberately does not override
 * {@link #setRecipe}.
 */
@ParametersAreNonnullByDefault
public abstract class CMIHeatedBasinCategory extends BasinCategory {

    private final AnimatedBlazeBurner blaze = new AnimatedBlazeBurner();
    private final AnimatedAllayBurner allay = new AnimatedAllayBurner();

    public CMIHeatedBasinCategory(CreateRecipeCategory.Info<BasinRecipe> info) {
        super(info, true);
    }

    /**
     * Draws the heat source at the standard position: the allay burner for
     * allayheated recipes, the animated blaze burner (lit per the recipe's heat
     * requirement) otherwise. Call from {@code draw()} right after
     * {@code super.draw(...)}.
     */
    protected void drawHeater(BasinRecipe recipe, GuiGraphics graphics) {
        HeatCondition heat = recipe.getRequiredHeat();
        if (heat == HeatCondition.NONE)
            return;
        int x = getBackground().getWidth() / 2 + 3;
        if (heat == CMIHeatConditions.ALLAY_HEATED)
            allay.draw(graphics, x, 55);
        else
            blaze.withHeat(heat.visualizeAsBlazeBurner())
                .draw(graphics, x, 55);
    }
}
