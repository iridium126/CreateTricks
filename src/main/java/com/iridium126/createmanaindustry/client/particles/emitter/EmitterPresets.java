package com.iridium126.createmanaindustry.client.particles.emitter;

/**
 * Built-in emitter blueprints exposed to {@code /cmip spawn|stream|bench} and
 * reusable by gameplay code later. The values are tuned for additive blending
 * (moderate peak brightness so overlapping particles do not wash out).
 */
public final class EmitterPresets {

    private EmitterPresets() {
    }

    /** Cool cyan/blue mana sparks — the signature magic-machine effect. */
    public static final EmitterSpec MANA_SPARK = EmitterSpec.builder()
            .shape(EmitterShape.POINT)
            .speed(1.0, 2.6)
            .life(0.6, 1.3)
            .sizeOverLife(0.09, 0.02, 1.6)
            .drag(2.2)
            .glow(1.1)
            .color(0.45f, 0.92f, 1.00f, 1f)
            .color(0.20f, 0.55f, 1.00f, 1f)
            .build();

    /** Rising orange/red embers with gravity (campfire sparks). */
    public static final EmitterSpec EMBER = EmitterSpec.builder()
            .shape(EmitterShape.SPHERE)
            .size(0.15)
            .speed(0.4, 1.4)
            .life(1.2, 3.0)
            .sizeOverLife(0.07, 0.012, 2.0)
            .gravity(0, -2.8, 0)
            .drag(1.6)
            .rotation(0)
            .glow(0.9)
            .color(1.00f, 0.95f, 0.60f, 1f)
            .color(1.00f, 0.45f, 0.10f, 1f)
            .color(0.60f, 0.10f, 0.05f, 1f)
            .build();

    /** Slow grey ash drifting in a wide box (snow/ash fall). */
    public static final EmitterSpec ASH = EmitterSpec.builder()
            .shape(EmitterShape.BOX)
            .size(3.0)
            .speed(0.2, 0.8)
            .life(3.0, 6.0)
            .sizeOverLife(0.05, 0.16, 1.0)
            .acceleration(0, 1.1, 0)      // buoyancy
            .wind(0.6, 0.3, 0.05, 0.1)
            .drag(3.2)
            .glow(0.6)
            .color(0.55f, 0.57f, 0.62f, 0.55f)
            .color(0.25f, 0.26f, 0.30f, 0.35f)
            .build();

    /** Soul-flame cone spray (allay burner); cone axis = up. */
    public static final EmitterSpec SOUL_FLAME = EmitterSpec.builder()
            .shape(EmitterShape.CONE)
            .size(1.4)
            .cone(0.42f)                  // ~23 degrees half angle
            .wind(0, 0, 1, 0)             // axis = up
            .speed(0.4, 1.6)
            .life(0.7, 1.8)
            .sizeOverLife(0.22, 0.04, 1.2)
            .acceleration(0, 1.6, 0)
            .drag(2.0)
            .glow(1.4)
            .color(0.35f, 0.92f, 1.00f, 1f)
            .color(0.25f, 0.55f, 0.92f, 1f)
            .color(0.12f, 0.22f, 0.35f, 1f)
            .build();

    /** Fast whiter-blue explosion/burst. */
    public static final EmitterSpec MANA_BURST = EmitterSpec.builder()
            .shape(EmitterShape.SPHERE)
            .size(0.3)
            .speed(2.2, 5.0)
            .life(0.35, 0.9)
            .sizeOverLife(0.12, 0.01, 1.8)
            .drag(3.0)
            .glow(1.2)
            .color(1.00f, 1.00f, 1.00f, 1f)
            .color(0.45f, 0.85f, 1.00f, 1f)
            .build();

    /**
     * Vanilla {@code minecraft:cherry_leaves} particle, faithfully replicated.
     * <p>
     * Matches {@code CherryParticle}: lifetime 300 ticks (= 15 s of our real
     * seconds), gravity 7.5e-4 blocks/tick² (= 0.3 blocks/s²), spawn velocity 0,
     * random pick of 12 atlas frames, a life-gated spiral flutter (amplitude 2.0
     * blocks at full life, growing as age^1.25) and a random billboard spin
     * (velocity ±30°/t, acceleration ±5°/t², both derived per-particle from the
     * seed). Removed on ground contact just like vanilla (DIE_ON_GROUND).
     * The sprites are pure 0/255 alpha, so the emitter uses the OPAQUE
     * material (cutout + depth write) and never enters the sorted path.
     */
    public static final EmitterSpec CHERRY_LEAVES = EmitterSpec.builder()
            .shape(EmitterShape.POINT)
            .speed(0, 0)
            .life(15.0, 15.0)
            .sizeOverLife(0.075, 0.075, 1.0)
            .gravity(0, -0.3, 0)
            .drag(0)
            .material(EmitterSpec.Material.OPAQUE)
            .collide(EmitterSpec.CollideMode.DIE_ON_GROUND)
            .flutter(2.0)
            .spin(true)
            .spriteCount(12)
            .glow(1.0)
            .color(1f, 1f, 1f, 1f)
            .build();

    /** Dense slow-drifting cloud for capacity benchmarking (ignores motion). */
    public static final EmitterSpec FLOOD = EmitterSpec.builder()
            .shape(EmitterShape.BOX)
            .size(9.0)
            .speed(0, 0)
            .life(6.0, 10.0)
            .sizeOverLife(1.0, 0.8, 1.0)
            .glow(0.7)
            .color(0.80f, 0.88f, 1.00f, 0.5f)
            .build();

    /**
     * Allay-model particle (MODEL material): the vanilla allay rendered as an
     * instanced 3D model, fullbright cutout with depth writes. Total body
     * height = 2 x size above the feet (0.60 blocks at size 0.30 = exactly the
     * vanilla allay's visual height). MODEL particles carry vanilla hit
     * points (20.0) in the maxLife slot and are immortal: the header lifetime
     * is unused, death happens only when a player's melee attack drains the
     * HP (the corpse then plays the vanilla death animation for one second).
     * The three presets differ only in animation/motion; {@code /cmip anim
     * <preset> <anim>} switches the animation of live particles at runtime.
     */
    public static final EmitterSpec ALLAY_FLY = EmitterSpec.builder()
            .shape(EmitterShape.POINT)
            .speed(0.6, 1.4)
            .life(10.0, 18.0)
            .sizeOverLife(0.30, 0.30, 1.0)
            .gravity(0, 0.35, 0)       // gentle rise
            .drag(1.2)
            .material(EmitterSpec.Material.MODEL)
            .animation(EmitterSpec.Animation.FLY)
            .glow(1.0)
            .color(1f, 1f, 1f, 1f)
            .build();

    /**
     * Vanilla-complete jukebox dance: body/head roll WITH the periodic burst
     * spin ({@code isDancing()} always carries the {@code isSpinning()} rhythm,
     * including its post-window wobble-suppression decay), no travel.
     */
    public static final EmitterSpec ALLAY_DANCE = EmitterSpec.builder()
            .shape(EmitterShape.POINT)
            .speed(0.05, 0.2)
            .life(20.0, 30.0)
            .sizeOverLife(0.30, 0.30, 1.0)
            .gravity(0, 0.05, 0)
            .drag(2.0)
            .material(EmitterSpec.Material.MODEL)
            .animation(EmitterSpec.Animation.DANCE)
            .glow(1.0)
            .color(1f, 1f, 1f, 1f)
            .build();

    /**
     * Allay drifting with raised arms, holding a diamond sword — the vanilla
     * hand-anchor render's validation surface: spawn this beside a real
     * {@code /summon allay} given the same item and compare transforms
     * frame-by-frame (docs/allay-particle-vanilla-alignment.md, held items).
     */
    public static final EmitterSpec ALLAY_HOLD = EmitterSpec.builder()
            .shape(EmitterShape.POINT)
            .speed(0.4, 0.9)
            .life(8.0, 14.0)
            .sizeOverLife(0.30, 0.30, 1.0)
            .gravity(0, 0.25, 0)
            .drag(1.0)
            .material(EmitterSpec.Material.MODEL)
            .animation(EmitterSpec.Animation.HOLD)
            .heldItem(EmitterSpec.HeldItem.DIAMOND_SWORD)
            .glow(1.0)
            .color(1f, 1f, 1f, 1f)
            .build();

    /** Looks up a preset by its command name, or returns null. */
    public static EmitterSpec byName(String name) {
        return switch (name) {
            case "mana_spark" -> MANA_SPARK;
            case "ember" -> EMBER;
            case "ash" -> ASH;
            case "soul_flame" -> SOUL_FLAME;
            case "mana_burst" -> MANA_BURST;
            case "cherry_leaves" -> CHERRY_LEAVES;
            case "flood" -> FLOOD;
            case "allay_fly" -> ALLAY_FLY;
            case "allay_dance" -> ALLAY_DANCE;
            case "allay_hold" -> ALLAY_HOLD;
            default -> null;
        };
    }

    /** Names registered for the command argument suggestions. */
    public static String[] names() {
        return new String[] { "mana_spark", "ember", "ash", "soul_flame", "mana_burst", "cherry_leaves", "flood",
                "allay_fly", "allay_dance", "allay_hold" };
    }

    /** Animation names for the /cmip anim argument suggestions. */
    public static String[] animationNames() {
        return new String[] { "fly", "dance", "hold" };
    }
}
