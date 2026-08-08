package com.iridium126.createmanaindustry.client.render;

/**
 * Resolved shadow-map distortion parameters for the active shaderpack, uploaded
 * to the mist shader as uniforms. {@code glslMode} selects the radial-length
 * function in {@code distort_shadow_space}; {@code bias} is the distortion
 * amount and {@code depthScale} the shadow-depth remap (both recognised packs
 * compress the depth by {@code z * 0.2}).
 */
public record PackShadowParams(int glslMode, float bias, float depthScale) {

    /** Identity — a pack with no shadow-map distortion. */
    public static final PackShadowParams NONE = new PackShadowParams(0, 1.0F, 1.0F);
}
