package com.iridium126.createmanaindustry.compat.jei.category;

import javax.annotation.ParametersAreNonnullByDefault;

import com.simibubi.create.compat.jei.category.BasinCategory;
import com.simibubi.create.compat.jei.category.CreateRecipeCategory;
import com.simibubi.create.compat.jei.category.animations.AnimatedBlazeBurner;
import com.simibubi.create.compat.jei.category.animations.AnimatedPress;
import com.simibubi.create.content.processing.basin.BasinRecipe;
import com.simibubi.create.content.processing.recipe.HeatCondition;

import mezz.jei.api.gui.ingredient.IRecipeSlotsView;
import net.minecraft.client.gui.GuiGraphics;

@ParametersAreNonnullByDefault
public class HeatedCompactingCategory extends BasinCategory {

    private final AnimatedPress press = new AnimatedPress(true);
    private final AnimatedBlazeBurner heater = new AnimatedBlazeBurner();

    public HeatedCompactingCategory(CreateRecipeCategory.Info<BasinRecipe> info) {
        super(info, true);
    }

    @Override
    public void draw(BasinRecipe recipe, IRecipeSlotsView recipeSlotsView, GuiGraphics graphics,
                     double mouseX, double mouseY) {
        super.draw(recipe, recipeSlotsView, graphics, mouseX, mouseY);

        HeatCondition requiredHeat = recipe.getRequiredHeat();
        if (requiredHeat != HeatCondition.NONE)
            heater.withHeat(requiredHeat.visualizeAsBlazeBurner())
                .draw(graphics, getBackground().getWidth() / 2 + 3, 55);
        press.draw(graphics, getBackground().getWidth() / 2 + 3, 34);
    }

}
