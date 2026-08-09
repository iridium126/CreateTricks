package com.iridium126.createmanaindustry.content.recipes;

import com.iridium126.createmanaindustry.CMIRecipeTypes;
import com.simibubi.create.content.processing.basin.BasinRecipe;
import com.simibubi.create.content.processing.recipe.ProcessingRecipeParams;

public class MistMixingRecipe extends BasinRecipe implements MistRecipe {

    public MistMixingRecipe(ProcessingRecipeParams params) {
        super(CMIRecipeTypes.MIST_MIXING, params);
    }

    @Override
    public MistOutput getMistResult() {
        if (getParams() instanceof MistRecipeParams mistParams)
            return mistParams.getMistResult();
        return null;
    }

    @Override
    public MistRequirement getMistRequirement() {
        if (getParams() instanceof MistRecipeParams mistParams)
            return mistParams.getMistRequirement();
        return null;
    }
}
