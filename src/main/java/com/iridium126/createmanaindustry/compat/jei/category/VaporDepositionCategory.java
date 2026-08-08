package com.iridium126.createmanaindustry.compat.jei.category;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import javax.annotation.ParametersAreNonnullByDefault;

import com.iridium126.createmanaindustry.compat.jei.VaporDepositionStationElement;
import com.iridium126.createmanaindustry.content.recipes.MistOutput;
import com.iridium126.createmanaindustry.content.recipes.MistRecipe;
import com.iridium126.createmanaindustry.content.recipes.MistRequirement;
import com.simibubi.create.compat.jei.category.CreateRecipeCategory;
import com.simibubi.create.content.processing.basin.BasinRecipe;

import mezz.jei.api.gui.ingredient.IRecipeSlotsView;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.material.Fluid;

/**
 * JEI category for {@code vapor_deposition} recipes. The recipe runs in a basin
 * sealed by a closed framed-glass trapdoor — the station drawn here is the
 * deposition lid resting on the basin.
 */
@ParametersAreNonnullByDefault
public class VaporDepositionCategory extends CMIHeatedBasinCategory {

    private final VaporDepositionStationElement station = new VaporDepositionStationElement();

    public VaporDepositionCategory(CreateRecipeCategory.Info<BasinRecipe> info) {
        super(info);
    }

    @Override
    public void draw(BasinRecipe recipe, IRecipeSlotsView recipeSlotsView, GuiGraphics graphics,
                     double mouseX, double mouseY) {
        super.draw(recipe, recipeSlotsView, graphics, mouseX, mouseY);

        drawHeater(recipe, graphics);
        station.draw(graphics, getBackground().getWidth() / 2 - 5, 47);
    }

    @Override
    public List<Component> getTooltipStrings(BasinRecipe recipe, IRecipeSlotsView recipeSlotsView,
                                             double mouseX, double mouseY) {
        List<Component> tooltips = new ArrayList<>();
        if (recipe instanceof MistRecipe mistRecipe) {
            MistRequirement requirement = mistRecipe.getMistRequirement();
            if (requirement != null) {
                tooltips.add(Component.translatable(
                    "createmanaindustry.jei.mist_requirement",
                    getFluidDisplayName(requirement.fluidId()),
                    String.format("%.0f%%", requirement.minConcentration() * 100)));
            }
            MistOutput output = mistRecipe.getMistOutput();
            if (output != null) {
                tooltips.add(Component.translatable(
                    "createmanaindustry.jei.mist_output",
                    getFluidDisplayName(output.fluidId()),
                    output.radius(), output.amount(), output.duration()));
            }
        }
        tooltips.add(Component.translatable("createmanaindustry.jei.vapor_deposition.sealed"));
        return tooltips;
    }

    private static String getFluidDisplayName(ResourceLocation fluidId) {
        Optional<Fluid> fluid = BuiltInRegistries.FLUID.getOptional(fluidId);
        if (fluid.isPresent()) {
            return fluid.get().getFluidType().getDescription().getString();
        }
        // Fallback: extract name from the path part of the ResourceLocation
        String path = fluidId.getPath();
        if (path.contains("/")) {
            path = path.substring(path.lastIndexOf('/') + 1);
        }
        return path;
    }
}
