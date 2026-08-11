package com.iridium126.createmanaindustry.config;

import java.util.HashMap;
import java.util.Map;
import java.util.function.DoubleSupplier;

import org.jetbrains.annotations.Nullable;

import com.iridium126.createmanaindustry.CreateManaIndustry;
import com.tterrag.registrate.builders.BlockBuilder;
import com.tterrag.registrate.util.nullness.NonNullUnaryOperator;

import it.unimi.dsi.fastutil.objects.Object2DoubleMap;
import it.unimi.dsi.fastutil.objects.Object2DoubleOpenHashMap;
import net.createmod.catnip.registry.RegisteredObjectsHelper;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.block.Block;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.fml.event.config.ModConfigEvent;
import net.neoforged.neoforge.common.ModConfigSpec;
import net.neoforged.neoforge.common.ModConfigSpec.ConfigValue;

/**
 * Server-authoritative gameplay config ({@code createmanaindustry-server.toml}),
 * synced to clients by NeoForge so every player sees the server's values.
 * <p>
 * Grouped by subsystem. The {@code impact}/{@code capacity} stress groups are
 * built lazily by {@link #build()} because their entries are fed by
 * {@link #setImpact}/{@link #setCapacity} during block registration — a
 * {@code static final} spec would be built too early and come out empty.
 */
@EventBusSubscriber(modid = CreateManaIndustry.MODID)
public final class ServerConfig {

    private static final ModConfigSpec.Builder BUILDER = new ModConfigSpec.Builder();

    // ---- fluid -------------------------------------------------------------

    private static ModConfigSpec.IntValue MANA_PER_BUCKET;
    private static ModConfigSpec.IntValue MEDIA_PER_BUCKET;
    private static ModConfigSpec.IntValue SOURCE_PER_BUCKET;

    // ---- kinetics ----------------------------------------------------------

    private static ModConfigSpec.DoubleValue MANA_PER_STRESS;
    private static ModConfigSpec.DoubleValue KINETIC_STRESS_TRICK_MANA_MULTIPLIER;

    // ---- mist --------------------------------------------------------------

    private static ModConfigSpec.IntValue MIST_MAX_RADIUS;
    private static ModConfigSpec.IntValue MIST_FLUID_PER_TICK;
    private static ModConfigSpec.DoubleValue MIST_BASE_CONCENTRATION;
    private static ModConfigSpec.DoubleValue CONDENSE_EFFICIENCY;

    // ---- allay_burner ------------------------------------------------------

    private static ModConfigSpec.IntValue MEDIA_CONSUMED_PER_TICK;
    private static ModConfigSpec.IntValue ALLAY_BURNER_MIST_RADIUS;
    private static ModConfigSpec.IntValue ALLAY_BURNER_MIST_PER_TICK;

    // ---- hexcasting --------------------------------------------------------

    private static ModConfigSpec.LongValue CYPHER_MAX_MEDIA;
    private static ModConfigSpec.LongValue TRINKET_MAX_MEDIA;
    private static ModConfigSpec.LongValue ARTIFACT_MAX_MEDIA;
    private static ModConfigSpec.LongValue BATTERY_MAX_MEDIA;

    static {
        BUILDER.comment("Fluid conversion ratios — how much mana/media/source one bucket holds.").push("fluid");
        MANA_PER_BUCKET = BUILDER
                .comment("The amount of mana contained in one bucket (1000mB) of Liquid Mana.")
                .defineInRange("manaPerBucket", 2048, 2048, 81920);
        MEDIA_PER_BUCKET = BUILDER
                .comment("The amount of media contained in one bucket (1000mB) of Liquid Media.")
                .defineInRange("mediaPerBucket", 400000, 1000, Integer.MAX_VALUE);
        SOURCE_PER_BUCKET = BUILDER
                .comment("The amount of source contained in one bucket (1000mB) of Liquid Source.")
                .defineInRange("sourcePerBucket", 1000, 100, 1000000);
        BUILDER.pop();

        BUILDER.comment("Kinetic-to-mana conversion and the kinetic stress trick.").push("kinetics");
        MANA_PER_STRESS = BUILDER
                .comment("Mana added per stress unit consumed each tick when converting kinetic stress into knot mana.")
                .defineInRange("manaPerStress", 0.001, 0.0, 1000.0);
        KINETIC_STRESS_TRICK_MANA_MULTIPLIER = BUILDER
                .comment("The multiplier applied to the kinetic stress mana trick when costing mana.")
                .defineInRange("kineticStressTrickManaMultiplier", 2.0, 0.0, 1000.0);
        BUILDER.pop();

        BUILDER.comment("Volumetric mist field behaviour (atomizer, condenser, burner).").push("mist");
        MIST_MAX_RADIUS = BUILDER
                .comment("Maximum Euclidean radius (in blocks) of the mist field around an active Kinetic Atomizer.")
                .defineInRange("mistMaxRadius", 16, 1, 32);
        MIST_FLUID_PER_TICK = BUILDER
                .comment("Base fluid amount (mB) consumed per tick when the atomizer is running at 256 RPM. Scales linearly with actual speed.")
                .defineInRange("mistFluidPerTick", 8, 1, 1000);
        MIST_BASE_CONCENTRATION = BUILDER
                .comment("Base concentration at distance 0 from the atomizer. Used in the formula: concentration = base * (1 - distance / radius).")
                .defineInRange("mistBaseConcentration", 1.0, 0.0, 1000.0);
        CONDENSE_EFFICIENCY = BUILDER
                .comment("Base amount (mB/tick) of mist fluid condensed per unit of concentration when water flows through a Condenser.")
                .defineInRange("condenseEfficiency", 5.0, 0.0, 1000.0);
        BUILDER.pop();

        BUILDER.comment("Allay Burner fuel consumption and its Liquid Soul mist.").push("allay_burner");
        MEDIA_CONSUMED_PER_TICK = BUILDER
                .comment("Media consumed per tick while the Allay Burner is burning. Drives the burn duration of all fuels: an item worth N media burns N / mediaConsumedPerTick ticks, and 1 mB of Liquid Media burns (mediaPerBucket / 1000) / mediaConsumedPerTick ticks.")
                .defineInRange("mediaConsumedPerTick", 50, 1, 10000);
        ALLAY_BURNER_MIST_RADIUS = BUILDER
                .comment("Radius (in blocks) of the Liquid Soul mist field emitted while the Allay Burner is burning.")
                .defineInRange("allayBurnerMistRadius", 4, 1, 32);
        ALLAY_BURNER_MIST_PER_TICK = BUILDER
                .comment("Liquid Soul mist capacity (mB) added per tick by a burning Allay Burner.")
                .defineInRange("allayBurnerMistPerTick", 1, 1, 1000);
        BUILDER.pop();

        BUILDER.comment("Incomplete Hexcasting item media capacities (in Hexcasting dust units, 1 dust = 10,000).").push("hexcasting");
        CYPHER_MAX_MEDIA = BUILDER
                .comment("Maximum media capacity for incomplete cyphers.")
                .defineInRange("cypherMaxMedia", 6400000L, 10000L, Long.MAX_VALUE);
        TRINKET_MAX_MEDIA = BUILDER
                .comment("Maximum media capacity for incomplete trinkets.")
                .defineInRange("trinketMaxMedia", 64000000L, 10000L, Long.MAX_VALUE);
        ARTIFACT_MAX_MEDIA = BUILDER
                .comment("Maximum media capacity for incomplete artifacts.")
                .defineInRange("artifactMaxMedia", 640000000L, 10000L, Long.MAX_VALUE);
        BATTERY_MAX_MEDIA = BUILDER
                .comment("Maximum media capacity for incomplete media batteries.")
                .defineInRange("batteryMaxMedia", 640000000L, 10000L, Long.MAX_VALUE);
        BUILDER.pop();
    }

    // ---- stress values (formerly CMIStress) --------------------------------

    // IDs need to be used since configs load before block registration
    private static final Object2DoubleMap<ResourceLocation> DEFAULT_IMPACTS = new Object2DoubleOpenHashMap<>();
    private static final Object2DoubleMap<ResourceLocation> DEFAULT_CAPACITIES = new Object2DoubleOpenHashMap<>();

    private static final Map<ResourceLocation, ConfigValue<Double>> impacts = new HashMap<>();
    private static final Map<ResourceLocation, ConfigValue<Double>> capacities = new HashMap<>();

    /** Built lazily by {@link #build()} once block registration has populated the stress defaults. */
    public static ModConfigSpec SPEC;

    private ServerConfig() {}

    /**
     * Builds the config spec. Must be called after block registration so the
     * {@code impact}/{@code capacity} entries (fed by {@link #setImpact} /
     * {@link #setCapacity}) are present.
     */
    public static void build() {
        BUILDER.comment(".", SU, IMPACT_COMMENT).push("impact");
        DEFAULT_IMPACTS.forEach((id, value) -> impacts.put(id, BUILDER.define(id.getPath(), value)));
        BUILDER.pop();

        BUILDER.comment(".", SU, CAPACITY_COMMENT).push("capacity");
        DEFAULT_CAPACITIES.forEach((id, value) -> capacities.put(id, BUILDER.define(id.getPath(), value)));
        BUILDER.pop();

        SPEC = BUILDER.build();
    }

    // ---- runtime values (server-side authority, synced to clients) ----------

    public static double manaPerStress = 0.001;
    public static int manaPerBucket = 2048;
    public static int mediaPerBucket = 400000;
    public static int mediaConsumedPerTick = 50;
    public static int sourcePerBucket = 1000;
    public static double kineticStressTrickManaMultiplier = 2.0;
    public static int mistMaxRadius = 16;
    public static int mistFluidPerTick = 8;
    public static double mistBaseConcentration = 1.0;
    public static double condenseEfficiency = 5.0;
    public static int allayBurnerMistRadius = 4;
    public static int allayBurnerMistPerTick = 1;
    public static long cypherMaxMedia = 6400000L;
    public static long trinketMaxMedia = 64000000L;
    public static long artifactMaxMedia = 640000000L;
    public static long batteryMaxMedia = 640000000L;

    // ---- stress accessors (BlockStressValues providers) --------------------

    @Nullable
    public static DoubleSupplier getImpact(Block block) {
        ResourceLocation id = RegisteredObjectsHelper.getKeyOrThrow(block);
        ConfigValue<Double> value = impacts.get(id);
        return value == null ? null : value::get;
    }

    @Nullable
    public static DoubleSupplier getCapacity(Block block) {
        ResourceLocation id = RegisteredObjectsHelper.getKeyOrThrow(block);
        ConfigValue<Double> value = capacities.get(id);
        return value == null ? null : value::get;
    }

    // ---- static helpers for block registration -----------------------------

    public static <B extends Block, P> NonNullUnaryOperator<BlockBuilder<B, P>> setNoImpact() {
        return setImpact(0.0);
    }

    public static <B extends Block, P> NonNullUnaryOperator<BlockBuilder<B, P>> setImpact(double value) {
        return builder -> {
            assertFromCMI(builder);
            DEFAULT_IMPACTS.put(CreateManaIndustry.modLoc(builder.getName()), value);
            return builder;
        };
    }

    public static <B extends Block, P> NonNullUnaryOperator<BlockBuilder<B, P>> setCapacity(double value) {
        return builder -> {
            assertFromCMI(builder);
            DEFAULT_CAPACITIES.put(CreateManaIndustry.modLoc(builder.getName()), value);
            return builder;
        };
    }

    private static void assertFromCMI(BlockBuilder<?, ?> builder) {
        if (!builder.getOwner().getModid().equals(CreateManaIndustry.MODID)) {
            throw new IllegalStateException(
                    "Non-" + CreateManaIndustry.MODID + " blocks cannot be added to CMI's config.");
        }
    }

    private static final String SU = "[in Stress Units]";
    private static final String IMPACT_COMMENT =
            "Configure the individual stress impact of mechanical blocks. Note that this cost is doubled for every speed increase it receives.";
    private static final String CAPACITY_COMMENT = "Configure how much stress a source can accommodate for.";

    @SubscribeEvent
    static void onLoad(ModConfigEvent event) {
        // Only Loading/Reloading have a loaded backing config; Unloading would throw
        // "Cannot get config value before config is loaded" on every server stop.
        if (event.getConfig().getSpec() == SPEC
                && (event instanceof ModConfigEvent.Loading || event instanceof ModConfigEvent.Reloading)) {
            manaPerStress = MANA_PER_STRESS.get();
            manaPerBucket = MANA_PER_BUCKET.get();
            mediaPerBucket = MEDIA_PER_BUCKET.get();
            mediaConsumedPerTick = MEDIA_CONSUMED_PER_TICK.get();
            sourcePerBucket = SOURCE_PER_BUCKET.get();
            kineticStressTrickManaMultiplier = KINETIC_STRESS_TRICK_MANA_MULTIPLIER.get();
            mistMaxRadius = MIST_MAX_RADIUS.get();
            mistFluidPerTick = MIST_FLUID_PER_TICK.get();
            mistBaseConcentration = MIST_BASE_CONCENTRATION.get();
            condenseEfficiency = CONDENSE_EFFICIENCY.get();
            allayBurnerMistRadius = ALLAY_BURNER_MIST_RADIUS.get();
            allayBurnerMistPerTick = ALLAY_BURNER_MIST_PER_TICK.get();
            cypherMaxMedia = CYPHER_MAX_MEDIA.get();
            trinketMaxMedia = TRINKET_MAX_MEDIA.get();
            artifactMaxMedia = ARTIFACT_MAX_MEDIA.get();
            batteryMaxMedia = BATTERY_MAX_MEDIA.get();
        }
    }
}
