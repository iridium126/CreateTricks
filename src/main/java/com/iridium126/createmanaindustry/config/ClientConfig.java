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
    }

    public static final ModConfigSpec SPEC = BUILDER.build();

    public static double mistGlowStrength = 0.5;
    public static boolean mistDebugShadow = false;
    public static double fuelRodBloomRingStrength = 1.0;

    private ClientConfig() {}

    @SubscribeEvent
    static void onLoad(ModConfigEvent event) {
        if (event.getConfig().getSpec() == SPEC
                && (event instanceof ModConfigEvent.Loading || event instanceof ModConfigEvent.Reloading)) {
            mistGlowStrength = MIST_GLOW_STRENGTH.get();
            mistDebugShadow = MIST_DEBUG_SHADOW.get();
            fuelRodBloomRingStrength = FUEL_ROD_BLOOM_RING_STRENGTH.get();
        }
    }
}
