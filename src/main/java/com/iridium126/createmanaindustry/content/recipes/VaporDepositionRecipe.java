package com.iridium126.createmanaindustry.content.recipes;

import com.iridium126.createmanaindustry.CMIRecipeTypes;
import com.simibubi.create.content.processing.basin.BasinRecipe;
import com.simibubi.create.content.processing.recipe.ProcessingRecipeParams;

/**
 * Vapor deposition — a basin recipe executed by the {@code DepositionLidBlockEntity}
 * operator when the basin is sealed by a closed framed-glass trapdoor.
 * <p>
 * Heat ({@code heat_requirement}) and mist ({@code mist_requirement}) are declared
 * per-recipe through {@link MistRecipeParams}. The mist gate and the
 * {@code allayheated} heat gate are enforced inside {@link BasinRecipe} by
 * {@code BasinRecipeMixin}, so this recipe inherits them for free.
 */
public class VaporDepositionRecipe extends BasinRecipe implements MistRecipe {

    public VaporDepositionRecipe(ProcessingRecipeParams params) {
        super(CMIRecipeTypes.VAPOR_DEPOSITION, params);
    }

    /** Returns the mist result config, or null if this recipe has no mist byproduct. */
    @Override
    public MistOutput getMistResult() {
        if (getParams() instanceof MistRecipeParams mistParams)
            return mistParams.getMistResult();
        return null;
    }

    /** Returns the mist requirement, or null if this recipe has no mist condition. */
    @Override
    public MistRequirement getMistRequirement() {
        if (getParams() instanceof MistRecipeParams mistParams)
            return mistParams.getMistRequirement();
        return null;
    }
}
