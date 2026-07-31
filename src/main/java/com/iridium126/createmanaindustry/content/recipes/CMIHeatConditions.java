package com.iridium126.createmanaindustry.content.recipes;

import com.simibubi.create.content.processing.recipe.HeatCondition;

/**
 * Holds the reflectively-injected {@code ALLAYHEATED} constant of Create's
 * {@link HeatCondition}. Populated by
 * {@code HeatConditionAllayHeatedMixin}'s clinit injector; {@code null} if
 * injection failed. Kept in a plain class (not a mixin) because mixin classes
 * may not declare non-private static fields.
 */
public final class CMIHeatConditions {

    public static volatile HeatCondition ALLAY_HEATED;

    private CMIHeatConditions() {}
}
