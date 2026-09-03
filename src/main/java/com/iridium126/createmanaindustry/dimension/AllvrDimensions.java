package com.iridium126.createmanaindustry.dimension;

import com.iridium126.createmanaindustry.CreateManaIndustry;

import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.Level;

/**
 * Static identity constants for the allay_dimension cube dimension.
 * <p>
 * The dimension itself is fully data-driven (see
 * {@code data/createmanaindustry/dimension/allay_dimension.json}); this class
 * only carries the resource keys so server-side code can gate on it without
 * string comparisons. The "Allvr" prefix (as opposed to "Allay") keeps these
 * classes clear of the {@code Allay} entity family ({@code AllayBurner*},
 * {@code AllayStorm*}).
 */
public final class AllvrDimensions {

    /** ResourceKey of the dimension itself ({@code createmanaindustry:allay_dimension}). */
    public static final ResourceKey<Level> ALLAY_LEVEL = ResourceKey.create(Registries.DIMENSION,
        ResourceLocation.fromNamespaceAndPath(CreateManaIndustry.MODID, "allay_dimension"));

    /** ResourceKey of the dimension type ({@code createmanaindustry:allay}). */
    public static final ResourceKey<net.minecraft.world.level.dimension.DimensionType> ALLAY_DIMENSION_TYPE =
        ResourceKey.create(Registries.DIMENSION_TYPE,
            ResourceLocation.fromNamespaceAndPath(CreateManaIndustry.MODID, "allay"));

    private AllvrDimensions() {}
}
