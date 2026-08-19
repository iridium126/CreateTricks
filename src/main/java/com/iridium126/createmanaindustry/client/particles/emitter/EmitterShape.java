package com.iridium126.createmanaindustry.client.particles.emitter;

/**
 * Emission shapes used by the GPU particle engine's spawn pass.
 * <p>
 * The numeric order matches the compute shader's {@code shape} switch
 * (POINT=0, BOX=1, SPHERE=2, CONE=3) packed into the emitter header.
 */
public enum EmitterShape {
    /** Bare point origin. */
    POINT,
    /** Cube of half-extent {@code size}. */
    BOX,
    /** Ball of radius {@code size}. */
    SPHERE,
    /** Directional spray: height {@code size}, axis = windDirection, half-angle tan = coneTanHalf. */
    CONE;

    public float index() {
        return this.ordinal();
    }
}
