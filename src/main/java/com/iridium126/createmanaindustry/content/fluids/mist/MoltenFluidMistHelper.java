package com.iridium126.createmanaindustry.content.fluids.mist;

import com.iridium126.createmanaindustry.CMITags;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.material.Fluid;

/**
 * Shared molten-fluid mist detection for the mixins that make the mist behave
 * like fire/lava for entities. Matches any fluid in the {@code molten_fluid}
 * tag, so future molten fluids get the same treatment automatically.
 * <p>
 * Lives outside the {@code ...mixin} package — Mixin forbids direct references
 * to unregistered classes inside a configured mixin package.
 * <p>
 * Mist sources store the <i>source</i> fluid key (e.g. {@code molten_rose_quartz}),
 * while {@code CMIFluids.MOLTEN_ROSE_QUARTZ.getId()} returns the <i>flowing</i>
 * key ({@code flowing_molten_rose_quartz}) — so tag membership is resolved from
 * the stored key via the fluid registry rather than compared to an id literal.
 */
public final class MoltenFluidMistHelper {

    private MoltenFluidMistHelper() {}

    /** True when the dominant mist at {@code pos} belongs to the molten_fluid tag. */
    public static boolean isInMoltenFluidMist(Level level, BlockPos pos) {
        if (level == null || pos == null)
            return false;
        ResourceLocation fluidId = MistFieldStore.getFluidType(level, pos);
        if (fluidId == null)
            return false;
        // Fluid#is(TagKey) is deprecated; resolve the registry holder instead.
        Holder.Reference<Fluid> holder = BuiltInRegistries.FLUID.getHolder(fluidId).orElse(null);
        return holder != null && holder.is(CMITags.MOLTEN_FLUID);
    }
}
