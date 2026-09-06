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

    // ---- fuel_tank ---------------------------------------------------------

    private static ModConfigSpec.IntValue FUEL_TANK_CAPACITY;
    private static ModConfigSpec.IntValue FUEL_TANK_MAX_BLOCKS;

    // ---- fuel_rod ----------------------------------------------------------

    private static ModConfigSpec.IntValue FUEL_ROD_MAX_RADIUS;
    private static ModConfigSpec.BooleanValue FUEL_ROD_STRICT_STACKING;

    // ---- allay storm -------------------------------------------------------

    private static ModConfigSpec.DoubleValue STORM_CORRECTION_HZ;
    private static ModConfigSpec.IntValue STORM_MAX_COUNT;
    private static ModConfigSpec.DoubleValue STORM_GROWTH_PER_SECOND;
    private static ModConfigSpec.DoubleValue STORM_WAVE_INTERVAL;
    private static ModConfigSpec.DoubleValue STORM_WAVE_FRACTION;
    private static ModConfigSpec.IntValue STORM_WAVE_MAX_SIZE;
    private static ModConfigSpec.DoubleValue STORM_WAVE_DAMAGE;
    private static ModConfigSpec.DoubleValue STORM_WAVE_RANGE;
    private static ModConfigSpec.IntValue STORM_CHASE_Y;

    // ---- hexcasting --------------------------------------------------------

    private static ModConfigSpec.LongValue CYPHER_MAX_MEDIA;
    private static ModConfigSpec.LongValue TRINKET_MAX_MEDIA;
    private static ModConfigSpec.LongValue ARTIFACT_MAX_MEDIA;
    private static ModConfigSpec.LongValue BATTERY_MAX_MEDIA;

    // ---- allvr_lod ---------------------------------------------------------

    private static ModConfigSpec.IntValue ALLVR_LOD_DISTANCE;
    private static ModConfigSpec.IntValue ALLVR_LOD_BUILD_THREADS;

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

        BUILDER.comment("Molten Salt Fuel Tank — multi-block fluid storage connecting in arbitrary shapes.").push("fuel_tank");
        // Bounds are coupled: blocks × capacity × 1000 must stay ≤ Integer.MAX_VALUE
        // (FluidTank stores mB as an int). 21,474 × 100 × 1000 = 2,147,400,000 ✓.
        FUEL_TANK_CAPACITY = BUILDER
                .comment("Fluid capacity of one fuel tank block, in buckets. Total capacity of a connected group = blocks × fuelTankCapacity × 1000 mB. Capped at 100 so blocks × capacity × 1000 never overflows the int tank capacity.")
                .defineInRange("fuelTankCapacity", 8, 1, 100);
        FUEL_TANK_MAX_BLOCKS = BUILDER
                .comment("Maximum number of blocks in one connected fuel tank group. Bounds the connectivity/basin recomputation cost. Capped at 21,474 (= 2,147,483 / 100) so blocks × fuelTankCapacity × 1000 never overflows the int tank capacity.")
                .defineInRange("fuelTankMaxBlocks", 4096, 1, 21474);
        BUILDER.pop();

        BUILDER.comment("Molten Salt Reactor Fuel Rod — a multi-block structure of fuel tanks and glass.").push("fuel_rod");
        // A layer of radius r holds 2(r-2)² + 2(r-2) + 1 tanks; r = 32 gives 1,861,
        // comfortably inside the fuelTankMaxBlocks group cap with several layers.
        FUEL_ROD_MAX_RADIUS = BUILDER
                .comment("Maximum horizontal radius (in blocks) of one fuel rod layer. A layer is a solid diamond of fuel tanks (Manhattan distance < radius - 1) outlined by glass (#minecraft:impermeable) at distance radius - 1; the minimum valid layer (radius 2) is one tank surrounded by four glass blocks.")
                .defineInRange("fuelRodMaxRadius", 5, 2, 32);
        FUEL_ROD_STRICT_STACKING = BUILDER
                .comment("Whether every fuel rod layer's radius must be less than or equal to the layer below it (a cone that widens downward). When false, layers may stack in any radius order as long as their centers align.")
                .define("fuelRodStrictStacking", true);
        BUILDER.pop();

        BUILDER.comment("Allay Storm — the GPU-driven boss swarm (persistence + network sync).").push("allay_storm");
        STORM_CORRECTION_HZ = BUILDER
                .comment("Authoritative-client position correction snapshots per second for storm members near players. Higher = tighter cross-player position agreement, more bandwidth (~4 KB/s per snapshot at the 256-member cap). Delivered to the authority client with its assignment; changes apply from the next snapshot.")
                .defineInRange("stormCorrectionHz", 5.0, 0.5, 20.0);
        STORM_MAX_COUNT = BUILDER
                .comment("Maximum generated member population of a storm. The command's count argument is the INITIAL population; the storm then grows toward this ceiling (members killed along the way stay dead and keep consuming budget — the total number of members a storm ever generates is capped here). Also hard-clamps the command's initial count. Hard engine ceiling is 131072.")
                .defineInRange("stormMaxCount", 65536, 1, 131072);
        STORM_GROWTH_PER_SECOND = BUILDER
                .comment("Members the storm generates per second while below stormMaxCount (growth runs on the server tick regardless of whether any player is in range). New members spawn on a ring just outside the visible envelope and fly to their storm positions. 0 disables growth (storms spawn at their initial count and never grow).")
                .defineInRange("stormGrowthPerSecond", 20.0, 0.0, 2048.0);
        STORM_WAVE_INTERVAL = BUILDER
                .comment("Seconds between dive-wave launches per player. Each player's cooldown fires independently; a wave launches only when the target is eligible (active, under open sky, within stormWaveRange of the chased center) and fewer than 4 waves are running. Waves are the storm's only offense: squad members break formation, follow a server-computed corridor to the target and deal stormWaveDamage on contact (self-reported by the target's client, server-validated).")
                .defineInRange("stormWaveInterval", 60.0, 5.0, 600.0);
        STORM_WAVE_FRACTION = BUILDER
                .comment("Squad size of a dive wave as a fraction of the ALIVE member population (membership is a deterministic per-member hash against this fraction, identical on every client and re-derivable server-side). Population attrition visibly shrinks the waves — the population IS the boss health bar.")
                .defineInRange("stormWaveFraction", 0.10, 0.001, 1.0);
        STORM_WAVE_MAX_SIZE = BUILDER
                .comment("Absolute cap on a dive-wave squad size (the fraction is clamped so alive * fraction never exceeds this). Bounds the separation-force cost of a converged dive.")
                .defineInRange("stormWaveMaxSize", 256, 1, 4096);
        STORM_WAVE_DAMAGE = BUILDER
                .comment("Damage per dive-wave contact. Fixed (no variance). Routed through player.hurt with the createmanaindustry:storm_peck damage type, so vanilla invulnerability frames, armor, absorption and totems all apply — concurrent divers cannot burst through the 10-tick i-frame window.")
                .defineInRange("stormWaveDamage", 7.0, 0.0, 40.0);
        STORM_WAVE_RANGE = BUILDER
                .comment("Maximum distance from the chased center for a player to be eligible as a wave target (keeps dives inside the client visibility envelope so every active client can render the attack).")
                .defineInRange("stormWaveRange", 96.0, 16.0, 120.0);
        STORM_CHASE_Y = BUILDER
                .comment("Fixed altitude of the chased storm center (the typhoon is a sky storm pinned to this Y; the command input Y is overridden). Lower it and the storm may clip taller terrain — the wave corridor pathfinding routes dives around obstacles regardless.")
                .defineInRange("stormChaseY", 128, 40, 300);
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

        BUILDER.comment("Allay dimension LOD pipeline (server-authoritative streaming extent).").push("allvr_lod");
        ALLVR_LOD_DISTANCE = BUILDER
                .comment("Far-terrain view distance for the allay dimension's LOD pipeline, in blocks. "
                        + "Beyond the full-resolution cube streaming radius (256 blocks) the server streams "
                        + "server-meshed LOD nodes out to this distance (fixed band table 256/512/1024/2048, "
                        + "the view distance only caps the outer edge). Applies to every player in the dimension.")
                .defineInRange("allvrLodDistance", 2048, 512, 4096);
        ALLVR_LOD_BUILD_THREADS = BUILDER
                .comment("Worker threads that build LOD node meshes (density-field math + greedy mesher, no world "
                        + "access). The server main thread only dispatches and captures edit overlays (<0.5ms/tick).")
                .defineInRange("allvrLodBuildThreads", 2, 1, 4);
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
    public static int fuelTankCapacity = 8;
    public static int fuelTankMaxBlocks = 4096;
    public static int fuelRodMaxRadius = 5;
    public static boolean fuelRodStrictStacking = true;
    public static double stormCorrectionHz = 5.0;
    public static int stormMaxCount = 65536;
    public static double stormGrowthPerSecond = 20.0;
    public static double stormWaveInterval = 60.0;
    public static double stormWaveFraction = 0.10;
    public static int stormWaveMaxSize = 256;
    public static double stormWaveDamage = 7.0;
    public static double stormWaveRange = 96.0;
    public static int stormChaseY = 128;
    public static long cypherMaxMedia = 6400000L;
    public static long trinketMaxMedia = 64000000L;
    public static long artifactMaxMedia = 640000000L;
    public static long batteryMaxMedia = 640000000L;
    public static int allvrLodDistance = 2048;
    public static int allvrLodBuildThreads = 2;

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
            fuelTankCapacity = FUEL_TANK_CAPACITY.get();
            fuelTankMaxBlocks = FUEL_TANK_MAX_BLOCKS.get();
            fuelRodMaxRadius = FUEL_ROD_MAX_RADIUS.get();
            fuelRodStrictStacking = FUEL_ROD_STRICT_STACKING.get();
            stormCorrectionHz = STORM_CORRECTION_HZ.get();
            stormMaxCount = STORM_MAX_COUNT.get();
            stormGrowthPerSecond = STORM_GROWTH_PER_SECOND.get();
            stormWaveInterval = STORM_WAVE_INTERVAL.get();
            stormWaveFraction = STORM_WAVE_FRACTION.get();
            stormWaveMaxSize = STORM_WAVE_MAX_SIZE.get();
            stormWaveDamage = STORM_WAVE_DAMAGE.get();
            stormWaveRange = STORM_WAVE_RANGE.get();
            stormChaseY = STORM_CHASE_Y.get();
            cypherMaxMedia = CYPHER_MAX_MEDIA.get();
            trinketMaxMedia = TRINKET_MAX_MEDIA.get();
            artifactMaxMedia = ARTIFACT_MAX_MEDIA.get();
            batteryMaxMedia = BATTERY_MAX_MEDIA.get();
            allvrLodDistance = ALLVR_LOD_DISTANCE.get();
            allvrLodBuildThreads = ALLVR_LOD_BUILD_THREADS.get();
        }
    }
}
