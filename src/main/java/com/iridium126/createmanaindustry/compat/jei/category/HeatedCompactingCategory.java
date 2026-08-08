package com.iridium126.createmanaindustry.compat.jei.category;

import javax.annotation.ParametersAreNonnullByDefault;

import com.simibubi.create.compat.jei.category.CreateRecipeCategory;
import com.simibubi.create.compat.jei.category.animations.AnimatedPress;
import com.simibubi.create.content.processing.basin.BasinRecipe;

import mezz.jei.api.gui.ingredient.IRecipeSlotsView;
import net.minecraft.client.gui.GuiGraphics;

@ParametersAreNonnullByDefault
public class HeatedCompactingCategory extends CMIHeatedBasinCategory {

    private final AnimatedPress press = new AnimatedPress(true);

    public HeatedCompactingCategory(CreateRecipeCategory.Info<BasinRecipe> info) {
        super(info);
    }

    @Override
    public void draw(BasinRecipe recipe, IRecipeSlotsView recipeSlotsView, GuiGraphics graphics,
                     double mouseX, double mouseY) {
        super.draw(recipe, recipeSlotsView, graphics, mouseX, mouseY);

        drawHeater(recipe, graphics);
        press.draw(graphics, getBackground().getWidth() / 2 + 3, 34);
    }

}
