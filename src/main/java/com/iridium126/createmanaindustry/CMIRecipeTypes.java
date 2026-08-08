package com.iridium126.createmanaindustry;

import java.util.function.Supplier;

import com.iridium126.createmanaindustry.content.recipes.HeatedCompactingRecipe;
import com.iridium126.createmanaindustry.content.recipes.MistCompactingRecipe;
import com.iridium126.createmanaindustry.content.recipes.MistMixingRecipe;
import com.iridium126.createmanaindustry.content.recipes.MistRecipeParams;
import com.iridium126.createmanaindustry.content.recipes.VaporDepositionRecipe;
import com.mojang.serialization.MapCodec;
import com.simibubi.create.content.processing.recipe.ProcessingRecipe;
import com.simibubi.create.content.processing.recipe.ProcessingRecipeParams;
import com.simibubi.create.content.processing.recipe.StandardProcessingRecipe;
import com.simibubi.create.foundation.recipe.IRecipeTypeInfo;

import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.crafting.Recipe;
import net.minecraft.world.item.crafting.RecipeInput;
import net.minecraft.world.item.crafting.RecipeSerializer;
import net.minecraft.world.item.crafting.RecipeType;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.registries.DeferredRegister;

public final class CMIRecipeTypes {

    private static final DeferredRegister<RecipeSerializer<?>> SERIALIZER_REGISTER =
            DeferredRegister.create(BuiltInRegistries.RECIPE_SERIALIZER, CreateManaIndustry.MODID);
    private static final DeferredRegister<RecipeType<?>> TYPE_REGISTER =
            DeferredRegister.create(Registries.RECIPE_TYPE, CreateManaIndustry.MODID);

    // ---- HEATED_COMPACTING ---------------------------------------------------

    public static final ResourceLocation HEATED_COMPACTING_ID =
            CreateManaIndustry.modLoc("heated_compacting");

    private static final Supplier<RecipeSerializer<?>> HEATED_COMPACTING_SERIALIZER =
            SERIALIZER_REGISTER.register("heated_compacting",
                    () -> new StandardProcessingRecipe.Serializer<>(HeatedCompactingRecipe::new));

    private static final Supplier<RecipeType<?>> HEATED_COMPACTING_TYPE =
            TYPE_REGISTER.register("heated_compacting",
                    () -> RecipeType.simple(HEATED_COMPACTING_ID));

    public static final IRecipeTypeInfo HEATED_COMPACTING = new IRecipeTypeInfo() {
        @Override
        public ResourceLocation getId() { return HEATED_COMPACTING_ID; }

        @SuppressWarnings("unchecked")
        @Override
        public <T extends RecipeSerializer<?>> T getSerializer() {
            return (T) HEATED_COMPACTING_SERIALIZER.get();
        }

        @SuppressWarnings("unchecked")
        @Override
        public <I extends RecipeInput, R extends Recipe<I>> RecipeType<R> getType() {
            return (RecipeType<R>) HEATED_COMPACTING_TYPE.get();
        }
    };

    // ---- MIST_COMPACTING ----------------------------------------------------

    public static final ResourceLocation MIST_COMPACTING_ID =
            CreateManaIndustry.modLoc("mist_compacting");

    private static final Supplier<RecipeSerializer<?>> MIST_COMPACTING_SERIALIZER =
            SERIALIZER_REGISTER.register("mist_compacting",
                    () -> new MistCompactingSerializer());

    private static final Supplier<RecipeType<?>> MIST_COMPACTING_TYPE =
            TYPE_REGISTER.register("mist_compacting",
                    () -> RecipeType.simple(MIST_COMPACTING_ID));

    public static final IRecipeTypeInfo MIST_COMPACTING = new IRecipeTypeInfo() {
        @Override
        public ResourceLocation getId() { return MIST_COMPACTING_ID; }

        @SuppressWarnings("unchecked")
        @Override
        public <T extends RecipeSerializer<?>> T getSerializer() {
            return (T) MIST_COMPACTING_SERIALIZER.get();
        }

        @SuppressWarnings("unchecked")
        @Override
        public <I extends RecipeInput, R extends Recipe<I>> RecipeType<R> getType() {
            return (RecipeType<R>) MIST_COMPACTING_TYPE.get();
        }
    };

    // ---- MIST_MIXING -------------------------------------------------------

    public static final ResourceLocation MIST_MIXING_ID =
            CreateManaIndustry.modLoc("mist_mixing");

    private static final Supplier<RecipeSerializer<?>> MIST_MIXING_SERIALIZER =
            SERIALIZER_REGISTER.register("mist_mixing",
                    () -> new MistMixingSerializer());

    private static final Supplier<RecipeType<?>> MIST_MIXING_TYPE =
            TYPE_REGISTER.register("mist_mixing",
                    () -> RecipeType.simple(MIST_MIXING_ID));

    public static final IRecipeTypeInfo MIST_MIXING = new IRecipeTypeInfo() {
        @Override
        public ResourceLocation getId() { return MIST_MIXING_ID; }

        @SuppressWarnings("unchecked")
        @Override
        public <T extends RecipeSerializer<?>> T getSerializer() {
            return (T) MIST_MIXING_SERIALIZER.get();
        }

        @SuppressWarnings("unchecked")
        @Override
        public <I extends RecipeInput, R extends Recipe<I>> RecipeType<R> getType() {
            return (RecipeType<R>) MIST_MIXING_TYPE.get();
        }
    };

    // ---- VAPOR_DEPOSITION --------------------------------------------------

    public static final ResourceLocation VAPOR_DEPOSITION_ID =
            CreateManaIndustry.modLoc("vapor_deposition");

    private static final Supplier<RecipeSerializer<?>> VAPOR_DEPOSITION_SERIALIZER =
            SERIALIZER_REGISTER.register("vapor_deposition",
                    () -> new VaporDepositionSerializer());

    private static final Supplier<RecipeType<?>> VAPOR_DEPOSITION_TYPE =
            TYPE_REGISTER.register("vapor_deposition",
                    () -> RecipeType.simple(VAPOR_DEPOSITION_ID));

    public static final IRecipeTypeInfo VAPOR_DEPOSITION = new IRecipeTypeInfo() {
        @Override
        public ResourceLocation getId() { return VAPOR_DEPOSITION_ID; }

        @SuppressWarnings("unchecked")
        @Override
        public <T extends RecipeSerializer<?>> T getSerializer() {
            return (T) VAPOR_DEPOSITION_SERIALIZER.get();
        }

        @SuppressWarnings("unchecked")
        @Override
        public <I extends RecipeInput, R extends Recipe<I>> RecipeType<R> getType() {
            return (RecipeType<R>) VAPOR_DEPOSITION_TYPE.get();
        }
    };

    // ---- registration -------------------------------------------------------

    private CMIRecipeTypes() {}

    public static void register(IEventBus modEventBus) {
        SERIALIZER_REGISTER.register(modEventBus);
        TYPE_REGISTER.register(modEventBus);
    }

    // ---- custom serializer for mist recipes ----------------------------------

    @SuppressWarnings({ "unchecked", "rawtypes" })
    private static final class MistCompactingSerializer implements RecipeSerializer<MistCompactingRecipe> {
        private final MapCodec<MistCompactingRecipe> codec;
        private final StreamCodec<RegistryFriendlyByteBuf, MistCompactingRecipe> streamCodec;

        MistCompactingSerializer() {
            var rawCodec = ProcessingRecipe.codec(
                    (ProcessingRecipe.Factory) (ProcessingRecipeParams p) -> new MistCompactingRecipe(p),
                    (MapCodec) MistRecipeParams.MIST_CODEC);
            this.codec = rawCodec;
            var rawStream = ProcessingRecipe.streamCodec(
                    (ProcessingRecipe.Factory) (ProcessingRecipeParams p) -> new MistCompactingRecipe(p),
                    (StreamCodec) MistRecipeParams.MIST_STREAM_CODEC);
            this.streamCodec = rawStream;
        }

        @Override
        public MapCodec<MistCompactingRecipe> codec() { return codec; }

        @Override
        public StreamCodec<RegistryFriendlyByteBuf, MistCompactingRecipe> streamCodec() {
            return streamCodec;
        }
    }

    @SuppressWarnings({ "unchecked", "rawtypes" })
    private static final class MistMixingSerializer implements RecipeSerializer<MistMixingRecipe> {
        private final MapCodec<MistMixingRecipe> codec;
        private final StreamCodec<RegistryFriendlyByteBuf, MistMixingRecipe> streamCodec;

        MistMixingSerializer() {
            var rawCodec = ProcessingRecipe.codec(
                    (ProcessingRecipe.Factory) (ProcessingRecipeParams p) -> new MistMixingRecipe(p),
                    (MapCodec) MistRecipeParams.MIST_CODEC);
            this.codec = rawCodec;
            var rawStream = ProcessingRecipe.streamCodec(
                    (ProcessingRecipe.Factory) (ProcessingRecipeParams p) -> new MistMixingRecipe(p),
                    (StreamCodec) MistRecipeParams.MIST_STREAM_CODEC);
            this.streamCodec = rawStream;
        }

        @Override
        public MapCodec<MistMixingRecipe> codec() { return codec; }

        @Override
        public StreamCodec<RegistryFriendlyByteBuf, MistMixingRecipe> streamCodec() {
            return streamCodec;
        }
    }

    @SuppressWarnings({ "unchecked", "rawtypes" })
    private static final class VaporDepositionSerializer implements RecipeSerializer<VaporDepositionRecipe> {
        private final MapCodec<VaporDepositionRecipe> codec;
        private final StreamCodec<RegistryFriendlyByteBuf, VaporDepositionRecipe> streamCodec;

        VaporDepositionSerializer() {
            var rawCodec = ProcessingRecipe.codec(
                    (ProcessingRecipe.Factory) (ProcessingRecipeParams p) -> new VaporDepositionRecipe(p),
                    (MapCodec) MistRecipeParams.MIST_CODEC);
            this.codec = rawCodec;
            var rawStream = ProcessingRecipe.streamCodec(
                    (ProcessingRecipe.Factory) (ProcessingRecipeParams p) -> new VaporDepositionRecipe(p),
                    (StreamCodec) MistRecipeParams.MIST_STREAM_CODEC);
            this.streamCodec = rawStream;
        }

        @Override
        public MapCodec<VaporDepositionRecipe> codec() { return codec; }

        @Override
        public StreamCodec<RegistryFriendlyByteBuf, VaporDepositionRecipe> streamCodec() {
            return streamCodec;
        }
    }
}
