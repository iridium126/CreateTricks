package com.iridium126.createmanaindustry;

import net.minecraft.core.registries.Registries;
import net.minecraft.tags.TagKey;
import net.minecraft.world.level.material.Fluid;

/**
 * Registry tags used by Create: Mana Industry.
 */
public final class CMITags {

    /**
     * Fluids that behave like this mod's molten fluids (Molten Rose Quartz,
     * Molten Prismarine Quartz, …) plus vanilla lava. Entities standing in them
     * are treated as in lava, the Kinetic Atomizer refuses to atomize them, and
     * mist of them ignites living entities exactly like a fire block. Add future
     * molten fluids here — both the source and flowing variant.
     */
    public static final TagKey<Fluid> MOLTEN_FLUID =
            TagKey.create(Registries.FLUID, CreateManaIndustry.modLoc("molten_fluid"));

    private CMITags() {}
}
