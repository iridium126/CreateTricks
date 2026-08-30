package com.iridium126.createmanaindustry.client.particles.allaystorm;

import com.iridium126.createmanaindustry.client.particles.emitter.EmitterSpec;
import com.iridium126.createmanaindustry.client.particles.emitter.EmitterShape;

import net.minecraft.world.phys.Vec3;

/**
 * All-in-one static home of the Allay Storm's emitter spec and its per-id
 * header packing. Extracted from {@code CMIParticleEngine} so the engine file
 * keeps only the storm's RUNTIME state (active latch, anchor, derived omega,
 * trickle-in counter); everything that describes WHAT a storm member is —
 * the spec, its population/speed caps and the reserved header slots
 * 18/19 layout — lives here.
 * <p>
 * The storm spec is deliberately unique (lifetime/speed/drag differ from
 * every preset) so spec dedupe hands it its own emitter id; the per-id
 * header then carries the ANCHOR in slots 18/19, which specs themselves
 * never do (they stay position-free so one spec can serve many sites).
 * Slot layout (consumed by update.comp / allay_pose.glsl):
 * <pre>
 *  18: motion mode (1 ball | 2 vortex), storm radius, wander radius, max speed
 *  19: anchor.xyz, 0
 * </pre>
 */
public final class AllayStormSpec {

    /** Motion modes carried in header vec4 #18.x. */
    public static final int MODE_BALL = 1;
    public static final int MODE_VORTEX = 2;
    /** Stress-test ceiling (user-set): 2^17 members. */
    public static final int MAX_COUNT = 131072;
    /** Client-side mirror of the server's 24-bit seed mask (StormData.SEED_MASK). */
    public static final int SEED_MASK_CLIENT = (1 << 24) - 1;
    /** Wandering-centre radius packed into header #18.z (blocks). */
    public static final float WANDER_RADIUS = 12.0f;
    /** Steering speed cap packed into header #18.w (blocks/s). */
    public static final float MAX_SPEED = 6.0f;

    /**
     * The storm spec: immortal MODEL members with REST collision (boids
     * steering alone lets members shear straight through walls), animated FLY
     * — the per-site anchor rides header #19, never inside the spec.
     */
    public static final EmitterSpec SPEC = EmitterSpec.builder()
            .shape(EmitterShape.POINT)
            .speed(2.5, 4.5)
            .life(3600, 3600) // immortal while active; stop expires them via uKillEmit
            .sizeOverLife(0.30, 0.30, 1.0)
            .gravity(0, 0, 0)
            .drag(0.6)
            .material(EmitterSpec.Material.MODEL)
            .animation(EmitterSpec.Animation.FLY)
            .collide(EmitterSpec.CollideMode.REST)
            .glow(1.0)
            .color(1f, 1f, 1f, 1f)
            .build();

    private AllayStormSpec() {
    }

    /**
     * Packs the storm's per-id header: the spec fields with the FLY animation
     * pinned, the member spawn style 3 (server-synced analytic orbit placement
     * — see emit.comp), plus the motion parameters and anchor in the reserved
     * slots 18/19. The motion mode is explicit (the spin rate is now a
     * per-frame growth-law integral, not a header constant).
     */
    public static float[] packedHeader(int mode, double radius, Vec3 anchor) {
        float[] h = SPEC.packedWithAnimation(EmitterSpec.Animation.FLY.index());
        h[17 * 4 + 1] = 3.0f; // spawnStyle 3: storm member (identity + analytic spawn)
        h[18 * 4 + 0] = (float) mode;
        h[18 * 4 + 1] = (float) radius;
        h[18 * 4 + 2] = WANDER_RADIUS;
        h[18 * 4 + 3] = MAX_SPEED;
        h[19 * 4 + 0] = (float) anchor.x;
        h[19 * 4 + 1] = (float) anchor.y;
        h[19 * 4 + 2] = (float) anchor.z;
        h[19 * 4 + 3] = 0f;
        return h;
    }
}
