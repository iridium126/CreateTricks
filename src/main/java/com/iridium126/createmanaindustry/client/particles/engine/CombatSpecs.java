package com.iridium126.createmanaindustry.client.particles.engine;

import com.iridium126.createmanaindustry.client.particles.emitter.EmitterSpec;

/**
 * Engine-private emitter specs for the vanilla combat particle set — crit
 * stars, enchanted-hit stars, damage-indicator hearts and death poof — drawn
 * by the GPU engine when the local player melee-attacks a MODEL (allay)
 * particle. Vanilla does NOT render particles for allays (they are not real
 * entities), so nothing here intercepts the vanilla particle engine; the only
 * spawn source is {@code CMIParticleEngine.syntheticAttack} and the GPU
 * death-chain in update.comp.
 * <p>
 * All motion constants are CONTINUOUS-CONVERSION equivalents of the vanilla
 * 20 Hz tick physics (cherry precedent), derived from the 1.21.1 sources
 * ({@code CritParticle}, {@code ExplodeParticle}, {@code Particle.tick},
 * {@code TrackingEmitter}):
 * <ul>
 *   <li>velocity: vanilla {@code Particle} velocities are blocks/TICK —
 *       blocks/s = ×20; accelerations b/tick² → b/s² = ×400;</li>
 *   <li>gravity: {@code Particle.tick} applies {@code yd -= 0.04 * gravity}
 *       per tick, so the vanilla gravity FIELD is a multiplier of 0.04 b/tick²
 *       → engine gravity b/s² = 0.04 × field × 400 = 16 × field;</li>
 *   <li>friction f per tick → engine drag = −ln(f)·20 1/s (the engine's
 *       {@code vel *= 1 - drag*dt} approximates e^(-drag·t));</li>
 *   <li>lifetime ticks → seconds ×0.05; the random lifetime SHAPE collapses to
 *       a uniform between the vanilla min/max (documented deviation);</li>
 *   <li>per-tick colour evolution (gCol·0.96, bCol·0.9 per tick) → exponential
 *       decays 0.96^20 = e^-0.816/s and 0.9^20 = e^-2.107/s, applied in
 *       textured.vsh from the particle seed + age (no extra state).</li>
 * </ul>
 * <p>
 * Reserved header slots this feature consumes (storm owns 18/19; specs stay
 * position-free):
 * <pre>
 *  16.x  deathEmitId  (MODEL only): emitter id whose poof spawns at corpse
 *        expiry (update.comp death chain); 0 = none
 *  16.z  growIn: size ramps 0→1 over the first 1/32 of life (vanilla
 *        CritParticle.getQuadSize clamp(age/lifetime·32))
 *  16.w  spriteAnim: sprite frame = frameBase + floor(life·spriteCount)
 *        (vanilla setSpriteFromAge) instead of the seed-random pick
 *  17.x  colorMode (non-MODEL): 0 none | 1 crit gray→red | 2 enchanted |
 *        3 poof constant gray — always 0 for existing presets (FLY index)
 *  17.y  spawnStyle: 0 default shape-based emit | 1 tracking crit star |
 *        2 damage-indicator heart
 *  17.z  lightMode: p2.w carries blockLight + 16·skyLight sampled at spawn
 *        (textured.vsh samples the real lightmap with it); 0 = legacy intensity
 *  17.w  frameBase: fixed atlas frame for single-frame combat sprites (0 = the
 *        legacy random-pick path used by cherry)
 * </pre>
 */
final class CombatSpecs {

    // ---- header slot indices (float offsets into the 20-vec4 header) ----
    public static final int HDR_DEATH_EMIT = 16 * 4 + 0;
    public static final int HDR_GROW_IN = 16 * 4 + 2;
    public static final int HDR_SPRITE_ANIM = 16 * 4 + 3;
    public static final int HDR_COLOR_MODE = 17 * 4 + 0;
    public static final int HDR_SPAWN_STYLE = 17 * 4 + 1;
    public static final int HDR_LIGHT_MODE = 17 * 4 + 2;
    public static final int HDR_FRAME_BASE = 17 * 4 + 3;

    // ---- colorMode values (header 17.x, non-MODEL emitters) ----
    public static final float COLOR_NONE = 0f;
    /** CritParticle gray (0.6..0.9) with per-life reddening (g·0.96, b·0.9 per tick). */
    public static final float COLOR_CRIT = 1f;
    /** MagicProvider: r·0.3, g·0.8 on top of the crit evolution (purple → red). */
    public static final float COLOR_MAGIC = 2f;
    /** ExplodeParticle constant gray (0.7..1.0). */
    public static final float COLOR_POOF = 3f;

    // ---- spawnStyle values (header 17.y) ----
    public static final float STYLE_DEFAULT = 0f;
    /** TrackingEmitter crit star: rejection-sampled point in the source allay's core box, vel = (d, d1+0.2, d2)·8 b/s. */
    public static final float STYLE_TRACK_CRIT = 1f;
    /** Damage-indicator heart: gaussian ±0.1 offset at the allay's mid-height, vel = (g·1.6, g·1.6+8, g·1.6) b/s. */
    public static final float STYLE_HEART = 2f;

    // ---- sprite atlas frame bases (sprite atlas: cherry 0..11, combat 12..22) ----
    public static final int FRAME_CRIT = 12;
    public static final int FRAME_MAGIC = 13;
    public static final int FRAME_HEART = 14;
    public static final int FRAME_POOF = 15;

    // ---- continuous-conversion constants ----
    /** CritParticle friction 0.7/tick → −ln(0.7)·20 1/s. */
    private static final double CRIT_DRAG = -Math.log(0.7) * 20.0;
    /** CritParticle gravity field 0.5 → 0.04 · 0.5 b/tick² · 400. */
    private static final double CRIT_GRAVITY = 0.04 * 0.5 * 400.0;
    /** CritParticle lifetime 6/(r·0.8+0.6) ticks → 4.3..10 ticks → 0.21..0.5 s (uniform shape approx). */
    private static final double CRIT_LIFE_MIN = 0.21;
    private static final double CRIT_LIFE_MAX = 0.5;
    /** CritParticle quadSize 0.1·(0.5+0.5r)·2·0.75 half-extent → mean 0.1125 (engine p0.w jitter 0.7..1.3 spans the vanilla range). */
    private static final double CRIT_SIZE = 0.1125;
    /** ExplodeParticle friction 0.9/tick → −ln(0.9)·20 1/s. */
    private static final double POOF_DRAG = -Math.log(0.9) * 20.0;
    /** ExplodeParticle gravity field −0.1 → gentle upward 1.6 b/s². */
    private static final double POOF_GRAVITY = 0.04 * -0.1 * 400.0;
    /** ExplodeParticle lifetime 16/(r·0.8+0.2)+2 ticks → 18..82 ticks → 0.9..4.1 s. */
    private static final double POOF_LIFE_MIN = 0.9;
    private static final double POOF_LIFE_MAX = 4.1;
    /** ExplodeParticle quadSize 0.1·(r²·6+1) → 0.1..0.7 half-extent, mean 0.3 (death chain writes p0.w 0.333+2u for the range). */
    private static final double POOF_SIZE = 0.30;

    /** Vanilla crit star ({@code ParticleTypes.CRIT}). */
    public static final EmitterSpec CRIT = critBuild(CRIT_LIFE_MIN, CRIT_LIFE_MAX, 1f);

    /**
     * Vanilla enchanted-hit star ({@code ParticleTypes.ENCHANTED_HIT},
     * MagicProvider tint). The keyframe alpha differs from CRIT by 0.001 ONLY
     * so spec dedupe ({@code EmitterSpec.equals} hashes the packed header)
     * keeps them as separate emitter ids — CRIT/MAGIC differ solely in
     * header-patched fields (frameBase/colorMode), and the colour keyframe is
     * overridden by colorMode in textured.vsh anyway.
     */
    public static final EmitterSpec MAGIC = critBuild(CRIT_LIFE_MIN, CRIT_LIFE_MAX, 0.999f);

    /** Vanilla damage-indicator heart ({@code ParticleTypes.DAMAGE_INDICATOR}, 20-tick lifetime = 1 s). */
    public static final EmitterSpec HEART = critBuild(1.0, 1.0, 1f);

    /** Vanilla death poof ({@code ParticleTypes.POOF}, 8-frame sprite animation). */
    public static final EmitterSpec POOF = EmitterSpec.builder()
            .shape(com.iridium126.createmanaindustry.client.particles.emitter.EmitterShape.POINT)
            .speed(0, 0) // velocity comes from the death chain (gaussian drift)
            .life(POOF_LIFE_MIN, POOF_LIFE_MAX)
            .sizeOverLife(POOF_SIZE, POOF_SIZE, 1.0)
            .gravity(0, POOF_GRAVITY, 0)
            .drag(POOF_DRAG)
            .material(EmitterSpec.Material.OPAQUE)
            .spriteCount(8)
            .glow(1.0)
            .color(1f, 1f, 1f, 1f)
            .build();

    private static EmitterSpec critBuild(double lifeMin, double lifeMax, float colorKeyAlpha) {
        return EmitterSpec.builder()
                .shape(com.iridium126.createmanaindustry.client.particles.emitter.EmitterShape.POINT)
                .speed(0, 0) // velocity comes from the spawn style (tracking/heart math)
                .life(lifeMin, lifeMax)
                .sizeOverLife(CRIT_SIZE, CRIT_SIZE, 1.0)
                .gravity(0, CRIT_GRAVITY, 0)
                .drag(CRIT_DRAG)
                .material(EmitterSpec.Material.OPAQUE)
                .spriteCount(1)
                .glow(1.0)
                .color(1f, 1f, 1f, colorKeyAlpha)
                .build();
    }

    private CombatSpecs() {
    }

    /**
     * Whether {@code spec} is one of the engine-private combat specs (their
     * sprites live in the combat frames of the shared sprite atlas).
     */
    public static boolean isCombat(EmitterSpec spec) {
        return spec == CRIT || spec == MAGIC || spec == HEART || spec == POOF;
    }

    /**
     * Header clone with this feature's reserved-slot fields written. Must be
     * applied by {@code CMIParticleEngine} whenever a combat spec's header is
     * (re)uploaded — {@code EmitterSpec.packed()} itself knows nothing about
     * them. {@code frameBase} rides 17.w; growIn/anim/color/light per spec.
     */
    public static float[] packedHeader(EmitterSpec spec) {
        float[] h = spec.packed().clone();
        if (spec == CRIT) {
            h[HDR_GROW_IN] = 1f;
            h[HDR_COLOR_MODE] = COLOR_CRIT;
            h[HDR_SPAWN_STYLE] = STYLE_TRACK_CRIT;
            h[HDR_LIGHT_MODE] = 1f;
            h[HDR_FRAME_BASE] = FRAME_CRIT;
        } else if (spec == MAGIC) {
            h[HDR_GROW_IN] = 1f;
            h[HDR_COLOR_MODE] = COLOR_MAGIC;
            h[HDR_SPAWN_STYLE] = STYLE_TRACK_CRIT;
            h[HDR_LIGHT_MODE] = 1f;
            h[HDR_FRAME_BASE] = FRAME_MAGIC;
        } else if (spec == HEART) {
            h[HDR_GROW_IN] = 1f;
            h[HDR_COLOR_MODE] = COLOR_CRIT;
            h[HDR_SPAWN_STYLE] = STYLE_HEART;
            h[HDR_LIGHT_MODE] = 1f;
            h[HDR_FRAME_BASE] = FRAME_HEART;
        } else if (spec == POOF) {
            h[HDR_SPRITE_ANIM] = 1f;
            h[HDR_COLOR_MODE] = COLOR_POOF;
            h[HDR_LIGHT_MODE] = 1f;
            h[HDR_FRAME_BASE] = FRAME_POOF;
        }
        return h;
    }
}
