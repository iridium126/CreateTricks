package com.iridium126.createmanaindustry.config;

import com.iridium126.createmanaindustry.CreateManaIndustry;

import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.fml.event.config.ModConfigEvent;
import net.neoforged.neoforge.common.ModConfigSpec;

@EventBusSubscriber(modid = CreateManaIndustry.MODID)
public final class Config {
    private static final ModConfigSpec.Builder BUILDER = new ModConfigSpec.Builder();

    private static final ModConfigSpec.DoubleValue MANA_PER_STRESS = BUILDER
            .comment("Mana added per stress unit consumed each tick when converting kinetic stress into knot mana.")
            .defineInRange("manaPerStress", 0.001, 0.0, 1000.0);

    private static final ModConfigSpec.IntValue MANA_PER_BUCKET = BUILDER
            .comment("The amount of mana contained in one bucket (1000mB) of Liquid Mana.")
            .defineInRange("manaPerBucket", 2048, 2048, 81920);

    private static final ModConfigSpec.IntValue MEDIA_PER_BUCKET = BUILDER
            .comment("The amount of media contained in one bucket (1000mB) of Liquid Media.")
            .defineInRange("mediaPerBucket", 400000, 1000, Integer.MAX_VALUE);

    private static final ModConfigSpec.IntValue MEDIA_CONSUMED_PER_TICK = BUILDER
            .comment("Media consumed per tick while the Allay Burner is burning. Drives the burn duration of all fuels: an item worth N media burns N / mediaConsumedPerTick ticks, and 1 mB of Liquid Media burns (mediaPerBucket / 1000) / mediaConsumedPerTick ticks.")
            .defineInRange("mediaConsumedPerTick", 50, 1, 10000);

    private static final ModConfigSpec.IntValue SOURCE_PER_BUCKET = BUILDER
            .comment("The amount of source contained in one bucket (1000mB) of Liquid Source.")
            .defineInRange("sourcePerBucket", 1000, 1, 1000000);

    private static final ModConfigSpec.DoubleValue KINETIC_STRESS_TRICK_MANA_MULTIPLIER = BUILDER
            .comment("The multiplier applied to the kinetic stress mana trick when costing mana.")
            .defineInRange("kineticStressTrickManaMultiplier", 2.0, 0.0, 1000.0);

    private static final ModConfigSpec.IntValue MIST_MAX_RADIUS = BUILDER
            .comment("Maximum Euclidean radius (in blocks) of the mist field around an active Kinetic Atomizer.")
            .defineInRange("mistMaxRadius", 16, 1, 32);

    private static final ModConfigSpec.IntValue MIST_FLUID_PER_TICK = BUILDER
            .comment("Base fluid amount (mB) consumed per tick when the atomizer is running at 256 RPM. Scales linearly with actual speed.")
            .defineInRange("mistFluidPerTick", 8, 1, 1000);

    private static final ModConfigSpec.DoubleValue MIST_BASE_CONCENTRATION = BUILDER
            .comment("Base concentration at distance 0 from the atomizer. Used in the formula: concentration = base * (1 - distance / radius).")
            .defineInRange("mistBaseConcentration", 1.0, 0.0, 1000.0);

    private static final ModConfigSpec.DoubleValue CONDENSE_EFFICIENCY = BUILDER
            .comment("Base amount (mB/tick) of mist fluid condensed per unit of concentration when water flows through a Condenser.")
            .defineInRange("condenseEfficiency", 5.0, 0.0, 1000.0);

    private static final ModConfigSpec.LongValue CYPHER_MAX_MEDIA = BUILDER
            .comment("Maximum media capacity for incomplete cyphers (in Hexcasting dust units, 1 dust = 10,000).")
            .defineInRange("cypherMaxMedia", 6400000L, 10000L, Long.MAX_VALUE);

    private static final ModConfigSpec.LongValue TRINKET_MAX_MEDIA = BUILDER
            .comment("Maximum media capacity for incomplete trinkets (in Hexcasting dust units, 1 dust = 10,000).")
            .defineInRange("trinketMaxMedia", 64000000L, 10000L, Long.MAX_VALUE);

    private static final ModConfigSpec.LongValue ARTIFACT_MAX_MEDIA = BUILDER
            .comment("Maximum media capacity for incomplete artifacts (in Hexcasting dust units, 1 dust = 10,000).")
            .defineInRange("artifactMaxMedia", 640000000L, 10000L, Long.MAX_VALUE);

    private static final ModConfigSpec.LongValue BATTERY_MAX_MEDIA = BUILDER
            .comment("Maximum media capacity for incomplete media batteries (in Hexcasting dust units, 1 dust = 10,000).")
            .defineInRange("batteryMaxMedia", 640000000L, 10000L, Long.MAX_VALUE);

    public static final ModConfigSpec SPEC = BUILDER.build();

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
    public static long cypherMaxMedia = 6400000L;
    public static long trinketMaxMedia = 64000000L;
    public static long artifactMaxMedia = 640000000L;
    public static long batteryMaxMedia = 640000000L;

    private Config() {}

    @SubscribeEvent
    static void onLoad(ModConfigEvent event) {
        if (event.getConfig().getSpec() == SPEC) {
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
            cypherMaxMedia = CYPHER_MAX_MEDIA.get();
            trinketMaxMedia = TRINKET_MAX_MEDIA.get();
            artifactMaxMedia = ARTIFACT_MAX_MEDIA.get();
            batteryMaxMedia = BATTERY_MAX_MEDIA.get();
        }
    }
}
