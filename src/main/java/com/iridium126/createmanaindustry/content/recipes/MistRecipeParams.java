package com.iridium126.createmanaindustry.content.recipes;

import java.util.Optional;
import java.util.function.Supplier;

import com.mojang.serialization.Codec;
import com.mojang.serialization.MapCodec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import com.simibubi.create.content.processing.recipe.HeatCondition;
import com.simibubi.create.content.processing.recipe.ProcessingOutput;
import com.simibubi.create.content.processing.recipe.ProcessingRecipeParams;
import com.simibubi.create.foundation.codec.CreateCodecs;

import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.resources.ResourceLocation;

public class MistRecipeParams extends ProcessingRecipeParams {

    protected MistOutput mistResult;
    protected MistRequirement mistRequirement;

    public static final MapCodec<MistRecipeParams> MIST_CODEC = createCodec(MistRecipeParams::new);
    public static final StreamCodec<RegistryFriendlyByteBuf, MistRecipeParams> MIST_STREAM_CODEC =
            createStreamCodec(MistRecipeParams::new);

    protected MistRecipeParams() {
        super();
        this.mistResult = null;
        this.mistRequirement = null;
    }

    public MistOutput getMistResult() { return mistResult; }
    public MistRequirement getMistRequirement() { return mistRequirement; }

    private static <P extends MistRecipeParams> MapCodec<P> createCodec(Supplier<P> factory) {
        return RecordCodecBuilder.<P>mapCodec(instance -> instance.group(
                Codec.either(CreateCodecs.FLAT_SIZED_FLUID_INGREDIENT_WITH_TYPE,
                        net.minecraft.world.item.crafting.Ingredient.CODEC)
                        .listOf().fieldOf("ingredients")
                        .forGetter(p -> p.ingredients()),
                Codec.either(net.neoforged.neoforge.fluids.FluidStack.CODEC,
                        ProcessingOutput.CODEC_NEW)
                        .listOf().fieldOf("results")
                        .forGetter(p -> p.results()),
                Codec.INT.optionalFieldOf("processing_time", 0)
                        .forGetter(p -> p.processingDuration),
                HeatCondition.CODEC.optionalFieldOf("heat_requirement", HeatCondition.NONE)
                        .forGetter(p -> p.requiredHeat),
                MistOutput.CODEC.optionalFieldOf("mist_result")
                        .forGetter(p -> Optional.ofNullable(p.mistResult)),
                MistRequirement.CODEC.optionalFieldOf("mist_requirement")
                        .forGetter(p -> Optional.ofNullable(p.mistRequirement))
        ).apply(instance,
                (ingredients, results, processingDuration, requiredHeat, mistOpt, mistReqOpt) -> {
                    P params = factory.get();
                    ingredients.forEach(either -> either
                            .ifRight(params.ingredients::add)
                            .ifLeft(params.fluidIngredients::add));
                    results.forEach(either -> either
                            .ifRight(params.results::add)
                            .ifLeft(params.fluidResults::add));
                    params.processingDuration = processingDuration;
                    params.requiredHeat = requiredHeat;
                    params.mistResult = mistOpt.orElse(null);
                    params.mistRequirement = mistReqOpt.orElse(null);
                    return params;
                }));
    }

    private static <P extends MistRecipeParams> StreamCodec<RegistryFriendlyByteBuf, P>
            createStreamCodec(Supplier<P> factory) {
        return StreamCodec.of(
                (buffer, params) -> params.encode(buffer),
                buffer -> {
                    P params = factory.get();
                    params.decode(buffer);
                    return params;
                });
    }

    @Override
    protected void encode(RegistryFriendlyByteBuf buffer) {
        super.encode(buffer);
        buffer.writeBoolean(mistResult != null);
        if (mistResult != null) {
            ResourceLocation.STREAM_CODEC.encode(buffer, mistResult.fluidId());
            ByteBufCodecs.VAR_INT.encode(buffer, mistResult.radius());
            ByteBufCodecs.VAR_INT.encode(buffer, mistResult.amount());
            ByteBufCodecs.VAR_INT.encode(buffer, mistResult.duration());
        }
        buffer.writeBoolean(mistRequirement != null);
        if (mistRequirement != null) {
            ResourceLocation.STREAM_CODEC.encode(buffer, mistRequirement.fluidId());
            buffer.writeDouble(mistRequirement.minConcentration());
            ByteBufCodecs.VAR_INT.encode(buffer, mistRequirement.amount());
        }
    }

    @Override
    protected void decode(RegistryFriendlyByteBuf buffer) {
        super.decode(buffer);
        if (buffer.readBoolean())
            mistResult = new MistOutput(
                    ResourceLocation.STREAM_CODEC.decode(buffer),
                    ByteBufCodecs.VAR_INT.decode(buffer),
                    ByteBufCodecs.VAR_INT.decode(buffer),
                    ByteBufCodecs.VAR_INT.decode(buffer));
        if (buffer.readBoolean())
            mistRequirement = new MistRequirement(
                    ResourceLocation.STREAM_CODEC.decode(buffer),
                    buffer.readDouble(),
                    ByteBufCodecs.VAR_INT.decode(buffer));
    }
}
