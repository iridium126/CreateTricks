package com.iridium126.createmanaindustry.client.render;

import org.jetbrains.annotations.Nullable;

/**
 * One shaderpack's shadow-map distortion convention. The mist Tyndall sampling
 * must remap shadow clip coords exactly like the pack's own {@code shadow.vsh},
 * so each convention knows how to detect itself in the pack's shaders and
 * resolve its parameters.
 * <p>
 * To adapt a new pack, implement this interface and register the instance in
 * {@link ShadowDistortionRegistry}, then add a matching GLSL branch for the new
 * {@link #glslMode()} in {@code distort_shadow_space} (mist_volumetric_iris.fsh).
 */
public interface ShadowDistortionConvention {

    /** GLSL mode id consumed by {@code distort_shadow_space} in the shader. */
    int glslMode();

    /** Short human-readable name for logging. */
    String displayName();

    /**
     * Returns the resolved parameters if this pack uses the convention, or
     * {@code null} if the pack's shaders do not declare it.
     */
    @Nullable PackShadowParams tryResolve(PackShaderSource pack);
}
