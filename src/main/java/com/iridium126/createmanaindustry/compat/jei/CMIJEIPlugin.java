package com.iridium126.createmanaindustry.compat.jei;

import java.util.ArrayList;
import java.util.List;

import javax.annotation.ParametersAreNonnullByDefault;

import org.jetbrains.annotations.NotNull;

import com.iridium126.createmanaindustry.CMIBlocks;
import com.iridium126.createmanaindustry.CMIRecipeTypes;
import com.iridium126.createmanaindustry.CreateManaIndustry;
import com.iridium126.createmanaindustry.compat.jei.category.HeatedCompactingCategory;
import com.iridium126.createmanaindustry.compat.jei.category.MistCompactingCategory;
import com.iridium126.createmanaindustry.compat.jei.category.MistMixingCategory;
import com.simibubi.create.AllBlocks;
import com.simibubi.create.compat.jei.DoubleItemIcon;
import com.simibubi.create.compat.jei.EmptyBackground;
import com.simibubi.create.compat.jei.category.CreateRecipeCategory;
import com.simibubi.create.content.processing.basin.BasinRecipe;

import mezz.jei.api.IModPlugin;
import mezz.jei.api.JeiPlugin;
import mezz.jei.api.gui.drawable.IDrawable;
import mezz.jei.api.recipe.RecipeType;
import mezz.jei.api.recipe.category.IRecipeCategory;
import mezz.jei.api.registration.IRecipeCatalystRegistration;
import mezz.jei.api.registration.IRecipeCategoryRegistration;
import mezz.jei.api.registration.IRecipeRegistration;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.RecipeHolder;

@JeiPlugin
@ParametersAreNonnullByDefault
public class CMIJEIPlugin implements IModPlugin {

    private static final ResourceLocation ID = CreateManaIndustry.modLoc("jei_plugin");

    // ---- JEI RecipeType constants (using correct createmanaindustry namespace) ----

    public static final RecipeType<RecipeHolder<BasinRecipe>> HEATED_COMPACTING_TYPE =
            RecipeType.createRecipeHolderType(CreateManaIndustry.modLoc("heated_compacting"));

    public static final RecipeType<RecipeHolder<BasinRecipe>> MIST_COMPACTING_TYPE =
            RecipeType.createRecipeHolderType(CreateManaIndustry.modLoc("mist_compacting"));

    public static final RecipeType<RecipeHolder<BasinRecipe>> MIST_MIXING_TYPE =
            RecipeType.createRecipeHolderType(CreateManaIndustry.modLoc("mist_mixing"));

    private final List<CreateRecipeCategory<BasinRecipe>> allCategories = new ArrayList<>();

    @Override
    @NotNull
    public ResourceLocation getPluginUid() {
        return ID;
    }

    @Override
    public void registerCategories(IRecipeCategoryRegistration registration) {
        allCategories.clear();

        // Heated Compacting
        {
            IDrawable icon = new DoubleItemIcon(
                    () -> new ItemStack(AllBlocks.MECHANICAL_PRESS.get()),
                    () -> new ItemStack(AllBlocks.BLAZE_BURNER.get()));
            IDrawable background = new EmptyBackground(177, 103);
            List<RecipeHolder<BasinRecipe>> recipes = new ArrayList<>();
            List<java.util.function.Supplier<? extends ItemStack>> catalysts = new ArrayList<>();
            catalysts.add(() -> new ItemStack(AllBlocks.MECHANICAL_PRESS.get()));
            catalysts.add(() -> new ItemStack(AllBlocks.BASIN.get()));

            var info = new CreateRecipeCategory.Info<>(
                    HEATED_COMPACTING_TYPE,
                    Component.translatable("createmanaindustry.recipe.heated_compacting"),
                    background,
                    icon,
                    () -> recipes,
                    catalysts);
            allCategories.add(new HeatedCompactingCategory(info));
        }

        // Mist Compacting
        {
            IDrawable icon = new DoubleItemIcon(
                    () -> new ItemStack(AllBlocks.MECHANICAL_PRESS.get()),
                    () -> new ItemStack(CMIBlocks.KINETIC_ATOMIZER.get()));
            IDrawable background = new EmptyBackground(177, 103);
            List<RecipeHolder<BasinRecipe>> recipes = new ArrayList<>();
            List<java.util.function.Supplier<? extends ItemStack>> catalysts = new ArrayList<>();
            catalysts.add(() -> new ItemStack(AllBlocks.MECHANICAL_PRESS.get()));
            catalysts.add(() -> new ItemStack(AllBlocks.BASIN.get()));
            catalysts.add(() -> new ItemStack(CMIBlocks.KINETIC_ATOMIZER.get()));

            var info = new CreateRecipeCategory.Info<>(
                    MIST_COMPACTING_TYPE,
                    Component.translatable("createmanaindustry.recipe.mist_compacting"),
                    background,
                    icon,
                    () -> recipes,
                    catalysts);
            allCategories.add(new MistCompactingCategory(info));
        }

        // Mist Mixing
        {
            IDrawable icon = new DoubleItemIcon(
                    () -> new ItemStack(AllBlocks.MECHANICAL_MIXER.get()),
                    () -> new ItemStack(CMIBlocks.KINETIC_ATOMIZER.get()));
            IDrawable background = new EmptyBackground(177, 103);
            List<RecipeHolder<BasinRecipe>> recipes = new ArrayList<>();
            List<java.util.function.Supplier<? extends ItemStack>> catalysts = new ArrayList<>();
            catalysts.add(() -> new ItemStack(AllBlocks.MECHANICAL_MIXER.get()));
            catalysts.add(() -> new ItemStack(AllBlocks.BASIN.get()));
            catalysts.add(() -> new ItemStack(CMIBlocks.KINETIC_ATOMIZER.get()));

            var info = new CreateRecipeCategory.Info<>(
                    MIST_MIXING_TYPE,
                    Component.translatable("createmanaindustry.recipe.mist_mixing"),
                    background,
                    icon,
                    () -> recipes,
                    catalysts);
            allCategories.add(new MistMixingCategory(info));
        }

        registration.addRecipeCategories(allCategories.toArray(IRecipeCategory[]::new));
    }

    @Override
    @SuppressWarnings({ "unchecked", "rawtypes" })
    public void registerRecipes(IRecipeRegistration registration) {
        var level = Minecraft.getInstance().level;
        if (level == null)
            return;

        var recipeManager = level.getRecipeManager();

        List<RecipeHolder<BasinRecipe>> heatedCompacting = (List) recipeManager
                .getAllRecipesFor((net.minecraft.world.item.crafting.RecipeType) CMIRecipeTypes.HEATED_COMPACTING.getType());
        if (!heatedCompacting.isEmpty())
            registration.addRecipes(HEATED_COMPACTING_TYPE, heatedCompacting);

        List<RecipeHolder<BasinRecipe>> mistCompacting = (List) recipeManager
                .getAllRecipesFor((net.minecraft.world.item.crafting.RecipeType) CMIRecipeTypes.MIST_COMPACTING.getType());
        if (!mistCompacting.isEmpty())
            registration.addRecipes(MIST_COMPACTING_TYPE, mistCompacting);

        List<RecipeHolder<BasinRecipe>> mistMixing = (List) recipeManager
                .getAllRecipesFor((net.minecraft.world.item.crafting.RecipeType) CMIRecipeTypes.MIST_MIXING.getType());
        if (!mistMixing.isEmpty())
            registration.addRecipes(MIST_MIXING_TYPE, mistMixing);
    }

    @Override
    public void registerRecipeCatalysts(IRecipeCatalystRegistration registration) {
        registration.addRecipeCatalyst(new ItemStack(AllBlocks.MECHANICAL_PRESS.get()),
                HEATED_COMPACTING_TYPE, MIST_COMPACTING_TYPE);
        registration.addRecipeCatalyst(new ItemStack(AllBlocks.BASIN.get()),
                HEATED_COMPACTING_TYPE, MIST_COMPACTING_TYPE, MIST_MIXING_TYPE);
        registration.addRecipeCatalyst(new ItemStack(AllBlocks.MECHANICAL_MIXER.get()),
                MIST_MIXING_TYPE);
        registration.addRecipeCatalyst(new ItemStack(AllBlocks.BLAZE_BURNER.get()),
                HEATED_COMPACTING_TYPE);
        registration.addRecipeCatalyst(new ItemStack(CMIBlocks.KINETIC_ATOMIZER.get()),
                MIST_COMPACTING_TYPE, MIST_MIXING_TYPE);
    }

}
