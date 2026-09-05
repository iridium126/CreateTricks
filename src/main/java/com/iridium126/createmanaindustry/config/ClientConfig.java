package com.iridium126.createmanaindustry.config;

import com.iridium126.createmanaindustry.CreateManaIndustry;

import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.fml.event.config.ModConfigEvent;
import net.neoforged.neoforge.common.ModConfigSpec;

/**
 * Client-only rendering config ({@code createmanaindustry-client.toml}).
 * <p>
 * Not loaded on dedicated servers; these values are only read from client-side
 * render code ({@code MistClientHandler}, {@code MistIrisHook}).
 */
@EventBusSubscriber(modid = CreateManaIndustry.MODID)
public final class ClientConfig {

    private static final ModConfigSpec.Builder BUILDER = new ModConfigSpec.Builder();

    // ---- rendering ---------------------------------------------------------

    private static ModConfigSpec.DoubleValue MIST_GLOW_STRENGTH;
    private static ModConfigSpec.BooleanValue MIST_DEBUG_SHADOW;
    private static ModConfigSpec.DoubleValue FUEL_ROD_BLOOM_RING_STRENGTH;

    // ---- particles -------------------------------------------------------

    private static ModConfigSpec.BooleanValue PARTICLE_ENABLED;
    private static ModConfigSpec.IntValue PARTICLE_MAX_COUNT;
    private static ModConfigSpec.DoubleValue PARTICLE_BUDGET_MS;
    private static ModConfigSpec.BooleanValue PARTICLE_AUTO_THROTTLE;
    private static ModConfigSpec.IntValue PARTICLE_FADE_DISTANCE;
    private static ModConfigSpec.BooleanValue PARTICLE_SHADER_PACK_INTEGRATION;
    private static ModConfigSpec.BooleanValue PARTICLE_HEX_SPRAY_REDIRECT;

    // ---- allay dimension (ALLVR) -------------------------------------------

    private static ModConfigSpec.BooleanValue ALLVR_GPU_PIPELINE;
    private static ModConfigSpec.BooleanValue ALLVR_IRIS_INTEGRATION;
    private static ModConfigSpec.BooleanValue ALLVR_IRIS_SHADOW_PASS;

    static {
        BUILDER.comment("Volumetric mist rendering options.").push("rendering");
        MIST_GLOW_STRENGTH = BUILDER
                .comment("Global multiplier for the glow of volumetric mist produced by glowing fluids (per-fluid glow derives from the fluid's light level).")
                .defineInRange("mistGlowStrength", 0.5, 0.0, 100.0);
        MIST_DEBUG_SHADOW = BUILDER
                .comment("DEBUG: visualize the Tyndall shadow-map sampling as mist color (green = lit, red = occluded). Temporary diagnostic.")
                .define("mistDebugShadow", false);
        FUEL_ROD_BLOOM_RING_STRENGTH = BUILDER
                .comment("Global multiplier for the glowing ring above a formed fuel rod (ring radius diffuses from maxRadius to 2x maxRadius while pulsing).")
                .defineInRange("fuelRodBloomRingStrength", 1.0, 0.0, 100.0);
        BUILDER.pop();

        BUILDER.comment("GPU particle engine options.").push("particles");
        PARTICLE_ENABLED = BUILDER
                .comment("Master switch for the GPU particle engine (self-hosted GL, no Veil needed). "
                        + "Turning it off drops all live particles immediately.")
                .define("enabled", true);
        PARTICLE_MAX_COUNT = BUILDER
                .comment("Maximum live particles allocated in GPU memory (64 bytes each, double-buffered). "
                        + "Also capped by the GPU's max shader-storage-block size.")
                .defineInRange("maxParticles", 2_000_000, 1_000, 4_000_000);
        PARTICLE_BUDGET_MS = BUILDER
                .comment("Frame-time budget (ms) for particle update+draw; the engine auto-scales emission to stay under it.")
                .defineInRange("frameBudgetMs", 16.6, 1.0, 50.0);
        PARTICLE_AUTO_THROTTLE = BUILDER
                .comment("Automatically reduce emission rate when the frame budget is exceeded.")
                .define("autoThrottle", true);
        PARTICLE_FADE_DISTANCE = BUILDER
                .comment("Distance in blocks at which particles start fading out; they are fully "
                        + "invisible 24 blocks further. Raise to see particles farther away — "
                        + "the alpha sort range adapts automatically. Note: particles do not "
                        + "match vanilla fog, so very high values with a short render distance "
                        + "can look out of place.")
                .defineInRange("fadeDistance", 96, 16, 256);
        PARTICLE_SHADER_PACK_INTEGRATION = BUILDER
                .comment("When a shader pack is active, route MODEL (allay) particle drawing through the")
                .comment("pack's own lighting pipeline via iris-veil-compat's world render hook, so the")
                .comment("models receive pack fog, tone mapping and surface lighting. Sprite particles are")
                .comment("unaffected and keep the self-drawn path. Falls back automatically when no pack")
                .comment("is in use or the merged program fails to build. true = auto-enable when possible.")
                .define("shaderPackIntegration", true);
        PARTICLE_HEX_SPRAY_REDIRECT = BUILDER
                .comment("Redirect Hexcasting's cast/conjure particle sprays (ParticleSpray -> MsgCastParticleS2C) "
                        + "to the GPU particle engine's conjure replication (additive hexagonal wisps, pigment "
                        + "colors preserved via spawn-time sampling). Falls back to the vanilla particle path "
                        + "automatically when the engine is unavailable. Default true.")
                .define("hexSprayRedirect", true);
        BUILDER.pop();

        BUILDER.comment("Allay dimension (ALLVR) terrain renderer options.").push("allvr");
        ALLVR_GPU_PIPELINE = BUILDER
                .comment("Use the GPU-driven terrain pipeline (node tree + compute frustum cull + MDI command "
                        + "generation + glMultiDrawElementsIndirectCount) instead of the CPU per-cube path. "
                        + "Falls back to the CPU path automatically when the GL capability probe or shader "
                        + "compile fails. Stage 4a slice: frustum culling only (HiZ occlusion and LOD arrive "
                        + "in 4b/4c).")
                .define("gpuPipeline", false);
        ALLVR_IRIS_INTEGRATION = BUILDER
                .comment("ALLVR iris shader-pack integration (voxy contract): when the active shader pack "
                        + "ships a voxy.json adaptation (Photon, Complementary, ...), allay-dimension terrain "
                        + "renders through the pack's own colortex targets and lighting patch instead of the "
                        + "unlit post-composite fallback. Packs without voxy.json keep the fallback. Shared "
                        + "shader surfaces (VOXY define, vx* uniforms/samplers, extended colortex set) are "
                        + "yielded to the voxy mod while it is installed (coexistence).")
                .define("irisIntegration", false);
        ALLVR_IRIS_SHADOW_PASS = BUILDER
                .comment("Render allay-dimension terrain into the shader pack's shadow map (depth-only MDI): "
                        + "entities/particles receive island cast shadows, and packs whose deferred lighting "
                        + "shadow-samples the gbuffer (Photon) get real terrain self-shadowing. Requires "
                        + "irisIntegration and an active pack with a shadow pass.")
                .define("irisShadowPass", true);
        BUILDER.pop();
    }

    public static final ModConfigSpec SPEC = BUILDER.build();

    public static double mistGlowStrength = 0.5;
    public static boolean mistDebugShadow = false;
    public static double fuelRodBloomRingStrength = 1.0;
    public static boolean particleEnabled = true;
    public static int particleMaxCount = 2_000_000;
    public static double particleBudgetMs = 16.6;
    public static boolean particleAutoThrottle = true;
    public static int particleFadeDistance = 96;
    public static boolean shaderPackIntegration = true;
    public static boolean hexSprayRedirect = true;
    public static boolean allvrGpuPipeline = false;
    public static boolean allvrIrisIntegration = false;
    public static boolean allvrIrisShadowPass = true;

    private ClientConfig() {}

    @SubscribeEvent
    static void onLoad(ModConfigEvent event) {
        if (event.getConfig().getSpec() == SPEC
                && (event instanceof ModConfigEvent.Loading || event instanceof ModConfigEvent.Reloading)) {
            mistGlowStrength = MIST_GLOW_STRENGTH.get();
            mistDebugShadow = MIST_DEBUG_SHADOW.get();
            fuelRodBloomRingStrength = FUEL_ROD_BLOOM_RING_STRENGTH.get();
            particleEnabled = PARTICLE_ENABLED.get();
            particleMaxCount = PARTICLE_MAX_COUNT.get();
            particleBudgetMs = PARTICLE_BUDGET_MS.get();
            particleAutoThrottle = PARTICLE_AUTO_THROTTLE.get();
            particleFadeDistance = PARTICLE_FADE_DISTANCE.get();
            shaderPackIntegration = PARTICLE_SHADER_PACK_INTEGRATION.get();
            hexSprayRedirect = PARTICLE_HEX_SPRAY_REDIRECT.get();
            allvrGpuPipeline = ALLVR_GPU_PIPELINE.get();
            allvrIrisIntegration = ALLVR_IRIS_INTEGRATION.get();
            allvrIrisShadowPass = ALLVR_IRIS_SHADOW_PASS.get();
        }
    }
}