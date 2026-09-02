package com.iridium126.createmanaindustry.client.particles.engine;

import java.util.Random;
import java.util.UUID;

import com.iridium126.createmanaindustry.client.particles.emitter.EmitterShape;
import com.iridium126.createmanaindustry.client.particles.emitter.EmitterSpec;

import at.petrak.hexcasting.api.addldata.ADPigment;
import at.petrak.hexcasting.api.pigment.ColorProvider;
import at.petrak.hexcasting.client.ClientTickCounter;
import net.minecraft.util.FastColor;
import net.minecraft.util.Mth;
import net.minecraft.world.phys.Vec3;

/**
 * Engine-private emitter spec for the Hexcasting conjure spray (spawnStyle 4)
 * — a faithful GPU port of {@code ConjureParticle} + {@code ParticleSpray}:
 * additive fullbright hexagonal soft-glow billboards, linear alpha fade from
 * 0.3, exact exponential shrink (quadSize ×= 0.96/tick), fixed random roll,
 * no collision, friction 0.96 → drag, gravity field −0.01 → upward drift,
 * lifetime 64/((r+3)·0.25) ticks, quadSize [0.1, 0.2) × 0.9.
 * <p>
 * All motion constants are CONTINUOUS-CONVERSION equivalents of the vanilla
 * 20 Hz tick physics (cherry/combat precedent). The pigment COLOR rides the
 * emitter header's 8 keyframe slots interpreted as a WHEEL by colorMode 4
 * (additive.vsh): colors are sampled from the caster's real
 * {@link ColorProvider} at spray time over 8 directions spanning the fixed
 * gradient axis — the vanilla handler freezes each particle's color at its
 * spawn instant, so within one spray direction is the only live dimension,
 * and consecutive sprays differ because their samples run at a later time.
 * The minimum-luminance correction rides along inside {@code getColor}.
 * <p>
 * Reserved header slots (storm owns 18/19; specs stay position-free):
 * <pre>
 *  17.x  colorMode 4: keyframes = pigment wheel (this spec)
 *  17.y  spawnStyle 4: hex spray — emit command c slots carry
 *        {vel.xyz b/s, intBitsToFloat(fuzz16 | spread16<<16)}
 * </pre>
 */
public final class HexSpecs {

    // ---- header slot indices (float offsets into the 20-vec4 header) ----
    public static final int HDR_COLOR_MODE = 17 * 4 + 0;
    public static final int HDR_SPAWN_STYLE = 17 * 4 + 1;

    // ---- colorMode values (header 17.x) ----
    public static final float COLOR_NONE = 0f;
    public static final float COLOR_PIGMENT = 4f;
    // spawnStyle (header 17.y)
    public static final float STYLE_HEX_SPRAY = 4f;

    /**
     * The fixed pigment gradient axis — MUST match
     * {@code normalize(vec3(0.3, 0.8, 0.5))} hardcoded in additive.vsh. The
     * wheel is sampled over 8 directions whose projection on this axis spans
     * [-1, 1] (cell centres), and the vsh phases each particle by the same
     * projection of its velocity direction.
     */
    public static final Vec3 GRADIENT_DIR = new Vec3(0.3, 0.8, 0.5).normalize();

    /** ConjureParticle friction 0.96/tick → −ln(0.96)·20 1/s. */
    private static final double CONJURE_DRAG = -Math.log(0.96) * 20.0;
    /**
     * ConjureParticle gravity field −0.01 — SIGN CONVENTION (combat precedent):
     * vanilla applies {@code yd -= 0.04·gravity} per tick, so a NEGATIVE field
     * drifts UP; the engine's gravity vector is negative-Y-down, so the engine
     * value is +0.04·0.01·400 = +0.16 b/s².
     */
    private static final double CONJURE_GRAVITY = 0.16;
    /** quadSize mean: SingleQuadParticle default [0.1, 0.2) × 0.9 → 0.135 (the emit-path [2/3, 4/3] p0.w range spans [0.09, 0.18) exactly). */
    private static final double CONJURE_SIZE = 0.135;
    /** ConjureParticle lifetime 256/(r+3) ticks (= 64/((r+3)·0.25)) → (3.2, 4.267] s (continuous, no integer-tick cast — poof precedent). */
    private static final double CONJURE_LIFE_MIN = 256.0 / 4.0 / 20.0;
    private static final double CONJURE_LIFE_MAX = 256.0 / 3.0 / 20.0;

    // /cmip spray pigment presets
    public enum Pigment {
        /** Vanilla amethyst pigment: solid {@code #ab65eb}. */
        AMETHYST,
        /** Vanilla UUID pigment: two HSV colors scrungled from the owner's UUID. */
        UUID,
        /** CMI extra: full 8-hue rainbow wheel cycling with time. */
        RAINBOW
    }

    /**
     * Builds the spray spec for one pigment wheel. Each distinct wheel is a
     * distinct spec instance (the colors ride the packed header's keyframe
     * slots), so emitter dedupe collapses identical pigments automatically.
     */
    public static EmitterSpec specForWheel(float[] wheelRGBA) {
        return EmitterSpec.builder()
                .shape(EmitterShape.POINT)
                .speed(0, 0) // velocity comes from the spray command (c slots)
                .life(CONJURE_LIFE_MIN, CONJURE_LIFE_MAX)
                .sizeOverLife(CONJURE_SIZE, CONJURE_SIZE, 1.0)
                .gravity(0, CONJURE_GRAVITY, 0)
                .drag(CONJURE_DRAG)
                .material(EmitterSpec.Material.ADDITIVE)
                .collide(EmitterSpec.CollideMode.NONE) // ConjureParticle hasPhysics = false
                .colors(wheelRGBA)
                .glow(1.0)
                .build();
    }

    /**
     * Header clone with this feature's reserved-slot fields written. Must be
     * applied whenever a hex spec's header is (re)uploaded (the
     * {@code ensureEmitter} dedupe stores specs by packed equality, so the
     * style/colorMode patches live post-pack exactly like CombatSpecs).
     */
    public static float[] packedHeader(EmitterSpec spec) {
        float[] h = spec.packed().clone();
        h[HDR_COLOR_MODE] = COLOR_PIGMENT;
        h[HDR_SPAWN_STYLE] = STYLE_HEX_SPRAY;
        return h;
    }

    // ---- pigment wheels ----------------------------------------------------

    /**
     * Samples any pigment {@link ColorProvider} into the 8-entry wheel the GPU
     * colorMode-4 morphs over: 8 directions whose gradient-axis projection
     * spans [-1, 1] (cell centres), colored at the CURRENT client tick time.
     * Sampling through {@code getColor} (not raw) keeps the minimum-luminance
     * correction, and works for arbitrary third-party pigments.
     */
    public static float[] sampleWheel(ColorProvider provider) {
        float t = ClientTickCounter.getTotal();
        Vec3 g = GRADIENT_DIR;
        Vec3 e1 = orthonormal(g);
        float[] wheel = new float[8 * 4];
        for (int k = 0; k < 8; k++) {
            double d = 2.0 * (k + 0.5) / 8.0 - 1.0;
            Vec3 dir = g.scale(d).add(e1.scale(Math.sqrt(Math.max(0.0, 1.0 - d * d))));
            int c = provider.getColor(t, dir);
            wheel[k * 4 + 0] = FastColor.ARGB32.red(c) / 255f;
            wheel[k * 4 + 1] = FastColor.ARGB32.green(c) / 255f;
            wheel[k * 4 + 2] = FastColor.ARGB32.blue(c) / 255f;
            // alpha: constant 0.3 (ConjureParticle's start alpha); the linear
            // (1 - life) fade is the additive path's lifetime term
            wheel[k * 4 + 3] = 0.3f;
        }
        return wheel;
    }

    /** The {@code /cmip spray} pigment providers (vanilla-faithful where one exists). */
    public static ColorProvider pigment(Pigment p, UUID owner) {
        return switch (p) {
            case AMETHYST -> new ColorProvider() {
                @Override
                protected int getRawColor(float time, Vec3 position) {
                    return 0xff_ab65eb;
                }
            };
            // ItemUUIDPigment.MyColorProvider: two HSV colors hashed from the
            // UUID bits, morphed along (0.1, 0.1, 0.1) with time/400
            case UUID -> {
                var rand = new Random(owner.getLeastSignificantBits() ^ owner.getMostSignificantBits());
                float hue1 = rand.nextFloat();
                float sat1 = rand.nextFloat(0.4f, 0.8f);
                float bri1 = rand.nextFloat(0.7f, 1.0f);
                float hue2 = rand.nextFloat();
                float sat2 = rand.nextFloat(0.7f, 1.0f);
                float bri2 = rand.nextFloat(0.2f, 0.7f);
                int[] colors = {
                        Mth.hsvToRgb(hue1, sat1, bri1) | 0xff000000,
                        Mth.hsvToRgb(hue2, sat2, bri2) | 0xff000000 };
                yield new ColorProvider() {
                    @Override
                    protected int getRawColor(float time, Vec3 position) {
                        return ADPigment.morphBetweenColors(colors, new Vec3(0.1, 0.1, 0.1), time / 400, position);
                    }
                };
            }
            case RAINBOW -> new ColorProvider() {
                @Override
                protected int getRawColor(float time, Vec3 position) {
                    float hue = (float) Mth.positiveModulo(time / 200.0
                            + position.dot(new Vec3(0.1, 0.1, 0.1)), 1.0);
                    return Mth.hsvToRgb(hue, 0.75f, 1.0f) | 0xff000000;
                }
            };
        };
    }

    /** Any unit vector orthogonal to {@code v} (degenerate only for a zero vector). */
    private static Vec3 orthonormal(Vec3 v) {
        Vec3 a = Math.abs(v.x) < 0.9 ? new Vec3(1, 0, 0) : new Vec3(0, 1, 0);
        return v.cross(a).normalize();
    }

    private HexSpecs() {
    }
}
