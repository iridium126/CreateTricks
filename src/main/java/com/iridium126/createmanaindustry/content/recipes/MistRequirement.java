package com.iridium126.createmanaindustry.content.recipes;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;

import net.minecraft.resources.ResourceLocation;

/**
 * Specifies that a recipe requires mist of a certain fluid type to be
 * present at the basin before it can match.
 * <p>
 * When {@code amount} is greater than zero, the recipe additionally
 * <em>consumes</em> that many mB of mist capacity per completion: the recipe
 * will not match (full-or-nothing) until the field holds enough capacity, and
 * it reserves that capacity while waiting/processing so the condenser yields.
 *
 * @param fluidId          the required mist fluid
 * @param minConcentration minimum concentration (0.0–1.0) at the basin position
 * @param amount           mB of mist capacity consumed per recipe completion; 0 = gate-only
 */
public record MistRequirement(ResourceLocation fluidId, double minConcentration, int amount) {
    public static final Codec<MistRequirement> CODEC = RecordCodecBuilder.create(instance -> instance.group(
            ResourceLocation.CODEC.fieldOf("fluid").forGetter(MistRequirement::fluidId),
            Codec.DOUBLE.optionalFieldOf("min_concentration", 0.1).forGetter(MistRequirement::minConcentration),
            Codec.INT.optionalFieldOf("amount", 0).forGetter(MistRequirement::amount)
    ).apply(instance, MistRequirement::new));
}
