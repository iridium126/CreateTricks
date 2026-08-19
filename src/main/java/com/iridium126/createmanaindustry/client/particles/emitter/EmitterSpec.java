package com.iridium126.createmanaindustry.client.particles.emitter;

import java.util.Arrays;
import java.util.Objects;

import net.minecraft.world.phys.Vec3;

/**
 * Immutable, data-driven description of one particle emitter behaviour.
 * <p>
 * Instances are packed into a 16-vec4 (256 byte) GPU header block per distinct
 * spec (used by the compute update/emit passes and the additive vertex shader),
 * so specs with identical fields collapse to one header. Origin is deliberately
 * NOT part of the spec — the spawn position is supplied per call/command.
 */
public final class EmitterSpec {

    /** Max keyframe colours carried to the shader (RGBA each). */
    public static final int MAX_COLORS = 8;

    public final EmitterShape shape;
    /** Shape parameter: box half-extent, sphere radius, or cone height (blocks). */
    public final double size;
    /** Reserved for per-axis/asymmetric shapes; 0 otherwise. */
    public final double radius;
    public final double speedMin;
    public final double speedMax;
    public final double lifeMin;
    public final double lifeMax;
    public final double sizeStart;
    public final double sizeEnd;
    /** Exponent applied to normalised life before size interpolation (ease). */
    public final double sizeEase;
    public final Vec3 gravity;       // blocks/s^2
    public final double drag;        // exponential damping, 1/s
    public final Vec3 acceleration;  // constant blocks/s^2
    public final double windStrength;// blocks/s^2 along windDirection
    public final Vec3 windDirection; // unit-ish; also the cone spray axis
    public final double rotation;    // initial billboard roll, radians (per-particle randomised around it)
    public final double coneTanHalf; // tan(half opening angle) for CONE
    /** Keyframe colours as a flat RGBA array; {@code colors.length/4} entries (2..8). */
    public final float[] colors;
    /** Global additive glow multiplier for this emitter. */
    public final double glow;

    private final float[] packed;

    private EmitterSpec(Builder b) {
        this.shape = Objects.requireNonNull(b.shape, "shape");
        this.size = b.size;
        this.radius = b.radius;
        this.speedMin = b.speedMin;
        this.speedMax = b.speedMax;
        this.lifeMin = b.lifeMin;
        this.lifeMax = b.lifeMax;
        this.sizeStart = b.sizeStart;
        this.sizeEnd = b.sizeEnd;
        this.sizeEase = b.sizeEase;
        this.gravity = b.gravity;
        this.drag = b.drag;
        this.acceleration = b.acceleration;
        this.windStrength = b.windStrength;
        this.windDirection = b.windDirection;
        this.rotation = b.rotation;
        this.coneTanHalf = b.coneTanHalf;
        this.colors = b.colors.length == 0
                ? new float[] { 1f, 1f, 1f, 1f }
                : b.colors.clone();
        this.glow = b.glow;
        this.packed = pack();
    }

    public static Builder builder() {
        return new Builder();
    }

    /**
     * Packs this spec into the 16-vec4 (64 float) GPU header layout shared with
     * the shaders. Origin is kept zero (supplied per call):
     * <pre>
     *   0:  (0,0,0)                         size
     *   1:  shape, speedMin, speedMax,      radius
     *   2:  gravity.xyz                     drag
     *   3:  acceleration.xyz                windStrength
     *   4:  windDirection.xyz               rotation
     *   5:  lifeMin, lifeMax, sizeStart,    sizeEnd
     *   6:  sizeEase, coneTanHalf,          colorCount, glow
     *   7:  reserved
     *   8..15: colour keyframes RGBA (padded with the last colour)
     * </pre>
     */
    private float[] pack() {
        float[] f = new float[64];
        f[0 * 4 + 3] = (float) size;
        f[1 * 4 + 0] = shape.index();
        f[1 * 4 + 1] = (float) speedMin;
        f[1 * 4 + 2] = (float) speedMax;
        f[1 * 4 + 3] = (float) radius;
        f[2 * 4 + 0] = (float) gravity.x;
        f[2 * 4 + 1] = (float) gravity.y;
        f[2 * 4 + 2] = (float) gravity.z;
        f[2 * 4 + 3] = (float) drag;
        f[3 * 4 + 0] = (float) acceleration.x;
        f[3 * 4 + 1] = (float) acceleration.y;
        f[3 * 4 + 2] = (float) acceleration.z;
        f[3 * 4 + 3] = (float) windStrength;
        float wl = (float) windDirection.length();
        f[4 * 4 + 0] = wl > 1e-6f ? (float) (windDirection.x / wl) : 0f;
        f[4 * 4 + 1] = wl > 1e-6f ? (float) (windDirection.y / wl) : 1f;
        f[4 * 4 + 2] = wl > 1e-6f ? (float) (windDirection.z / wl) : 0f;
        f[4 * 4 + 3] = (float) rotation;
        f[5 * 4 + 0] = (float) lifeMin;
        f[5 * 4 + 1] = (float) lifeMax;
        f[5 * 4 + 2] = (float) sizeStart;
        f[5 * 4 + 3] = (float) sizeEnd;
        f[6 * 4 + 0] = (float) sizeEase;
        f[6 * 4 + 1] = (float) coneTanHalf;
        int count = Math.max(2, Math.min(MAX_COLORS, colors.length / 4));
        f[6 * 4 + 2] = count;
        f[6 * 4 + 3] = (float) glow;
        // keyframe colours
        for (int i = 0; i < MAX_COLORS; i++) {
            int src = Math.min(i, count - 1) * 4;
            f[(8 + i) * 4 + 0] = colors[src + 0];
            f[(8 + i) * 4 + 1] = colors[src + 1];
            f[(8 + i) * 4 + 2] = colors[src + 2];
            f[(8 + i) * 4 + 3] = colors[src + 3];
        }
        return f;
    }

    /** The packed 16-vec4 GPU header for this spec. */
    public float[] packed() {
        return packed;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o)
            return true;
        if (!(o instanceof EmitterSpec that))
            return false;
        return Arrays.equals(packed, that.packed);
    }

    @Override
    public int hashCode() {
        return Arrays.hashCode(packed);
    }

    @Override
    public String toString() {
        return "EmitterSpec{" + shape + ", size=" + size + ", life=" + lifeMin + ".." + lifeMax + '}';
    }

    public static final class Builder {
        private EmitterShape shape = EmitterShape.POINT;
        private double size = 0;
        private double radius = 0;
        private double speedMin = 0;
        private double speedMax = 0;
        private double lifeMin = 1;
        private double lifeMax = 2;
        private double sizeStart = 0.1;
        private double sizeEnd = 0.05;
        private double sizeEase = 1;
        private Vec3 gravity = Vec3.ZERO;
        private double drag = 0;
        private Vec3 acceleration = Vec3.ZERO;
        private double windStrength = 0;
        private Vec3 windDirection = new Vec3(0, 1, 0);
        private double rotation = 0;
        private double coneTanHalf = 0.577f; // ~30 degrees
        private float[] colors = new float[] { 1f, 1f, 1f, 1f };
        private double glow = 1;

        public Builder shape(EmitterShape v) { this.shape = v; return this; }
        public Builder size(double v) { this.size = v; return this; }
        public Builder radius(double v) { this.radius = v; return this; }
        public Builder speed(double min, double max) { this.speedMin = min; this.speedMax = max; return this; }
        public Builder life(double min, double max) { this.lifeMin = min; this.lifeMax = max; return this; }
        public Builder sizeOverLife(double start, double end, double ease) {
            this.sizeStart = start; this.sizeEnd = end; this.sizeEase = ease; return this;
        }
        public Builder gravity(double x, double y, double z) { this.gravity = new Vec3(x, y, z); return this; }
        public Builder drag(double v) { this.drag = v; return this; }
        public Builder acceleration(double x, double y, double z) { this.acceleration = new Vec3(x, y, z); return this; }
        public Builder wind(double strength, double x, double y, double z) {
            this.windStrength = strength; this.windDirection = new Vec3(x, y, z); return this;
        }
        public Builder rotation(double v) { this.rotation = v; return this; }
        public Builder cone(double tanHalfAngle) { this.coneTanHalf = tanHalfAngle; return this; }
        public Builder glow(double v) { this.glow = v; return this; }

        /** Appends one RGBA keyframe (values 0..1). Up to {@link #MAX_COLORS}. */
        public Builder color(double r, double g, double b, double a) {
            if (colors.length < MAX_COLORS * 4) {
                float[] next = Arrays.copyOf(colors, Math.min(MAX_COLORS * 4, colors.length + 4));
                next[colors.length] = (float) r;
                next[colors.length + 1] = (float) g;
                next[colors.length + 2] = (float) b;
                next[colors.length + 3] = (float) a;
                colors = next;
            }
            return this;
        }

        /** Replaces the keyframe list with the given RGBA frames. */
        public Builder colors(float[] rgba) {
            if (rgba != null && rgba.length >= 4) {
                int kept = Math.max(4, Math.min(MAX_COLORS * 4, rgba.length));
                this.colors = Arrays.copyOf(rgba, kept);
            }
            return this;
        }

        public EmitterSpec build() {
            return new EmitterSpec(this);
        }
    }
}
