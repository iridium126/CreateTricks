package com.iridium126.createmanaindustry.content.recipes;

/**
 * Common interface for all mist-producing or mist-requiring basin recipes.
 * <p>
 * Implemented by recipe types such as {@link MistCompactingRecipe} (press)
 * and {@link MistMixingRecipe} (mixer) so that mixins can check a single
 * type rather than enumerating each concrete class.
 */
public interface MistRecipe {

    /** Returns the mist result config, or null if this recipe has no mist byproduct. */
    MistOutput getMistResult();

    /** Returns the mist requirement, or null if this recipe has no mist condition. */
    MistRequirement getMistRequirement();
}
