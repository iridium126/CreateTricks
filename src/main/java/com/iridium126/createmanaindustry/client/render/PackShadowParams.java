package com.iridium126.createmanaindustry.client.render;

/**
 * Resolved shadow-map distortion parameters for the active shaderpack, uploaded
 * to the mist shader as uniforms. {@code glslMode} selects the radial-length
 * function in {@code distort_shadow_space}; {@code bias} is the distortion
 * amount and {@code depthScale} the shadow-depth remap (both recognised packs
 * compress the depth by {@code z * 0.2}).
 * <p>
 * The logarithmic convention (mode 3, Bliss) additionally carries its three
 * curve parameters {@code logK}, {@code logA} and {@code logB} so the shader can
 * evaluate {@code distortFactor = log(len * b + a) * k} exactly like the pack;
 * they are zero for every other mode. {@code bias} doubles as {@code k} in that
 * mode so logging stays meaningful.
 */
public record PackShadowParams(int glslMode, float bias, float depthScale,
        float logK, float logA, float logB) {

    /** Identity — a pack with no shadow-map distortion. */
    public static final PackShadowParams NONE =
            new PackShadowParams(0, 1.0F, 1.0F, 0.0F, 0.0F, 0.0F);

    /** Convenience constructor for the length-compression conventions (modes 1/2). */
    public static PackShadowParams radial(int glslMode, float bias, float depthScale) {
        return new PackShadowParams(glslMode, bias, depthScale, 0.0F, 0.0F, 0.0F);
    }
}