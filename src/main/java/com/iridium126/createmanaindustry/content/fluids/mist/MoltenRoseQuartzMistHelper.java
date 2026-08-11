package com.iridium126.createmanaindustry.content.fluids.mist;

import com.iridium126.createmanaindustry.CreateManaIndustry;

import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.Level;

/**
 * Shared molten rose quartz mist detection for the mixins that make the mist
 * behave like fire/lava for entities.
 * <p>
 * Lives outside the {@code ...mixin} package — Mixin forbids direct references
 * to unregistered classes inside a configured mixin package.
 * <p>
 * Uses {@code modLoc} rather than {@code FluidEntry.getId()}: mist sources store
 * the <i>source</i> fluid (registry key {@code molten_rose_quartz}), while
 * {@code CMIFluids.MOLTEN_ROSE_QUARTZ.getId()} returns the <i>flowing</i> key
 * ({@code flowing_molten_rose_quartz}).
 */
public final class MoltenRoseQuartzMistHelper {

    private static final ResourceLocation MOLTEN_ROSE_QUARTZ_ID =
            CreateManaIndustry.modLoc("molten_rose_quartz");

    private MoltenRoseQuartzMistHelper() {}

    /** True when the dominant mist at {@code pos} is molten rose quartz. */
    public static boolean isInMoltenRoseQuartzMist(Level level, BlockPos pos) {
        return level != null && pos != null
                && MOLTEN_ROSE_QUARTZ_ID.equals(MistFieldStore.getFluidType(level, pos));
    }
}
