package com.iridium126.createmanaindustry.content.allaystorm;

import com.iridium126.createmanaindustry.CreateManaIndustry;

import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.damagesource.DamageType;

/**
 * Data-driven damage types (1.21.1: JSON under
 * {@code data/createmanaindustry/damage_type/}, keys registered here for
 * {@code DamageSources.source} lookups).
 */
public final class CMIDamageTypes {

    /**
     * Dive-wave contact damage from the Allay Storm ({@code storm_peck.json}):
     * exhaustion and hurt effects live in the JSON; the death message keys are
     * {@code death.attack.storm_peck} / {@code death.attack.storm_peck.player}.
     */
    public static final ResourceKey<DamageType> STORM_PECK = ResourceKey.create(
            Registries.DAMAGE_TYPE, CreateManaIndustry.modLoc("storm_peck"));

    private CMIDamageTypes() {
    }
}
