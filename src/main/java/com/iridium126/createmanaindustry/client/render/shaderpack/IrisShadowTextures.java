package com.iridium126.createmanaindustry.client.render.shaderpack;

/**
 * Bridges iris-side shadow values captured by the {@code ShadowRendererAccessor}
 * mixin to the client renderers. A plain holder avoids the mixin-merging pitfall
 * where a {@code @Unique} static field on the mixin class and the field merged
 * into the target class are two distinct copies.
 * <p>
 * Only holds values that must be captured from iris internals at render time:
 * the shadow depth texture id (for the mist Tyndall sampling) and the pack's
 * {@code sunPathRotation} (for the corrected sun direction). The shadow-map
 * distortion parameters are resolved separately by {@link ShadowDistortionRegistry}.
 * <p>
 * Holds no iris references, so the class is safe to load in any environment.
 */
public final class IrisShadowTextures {

    /** shadowtex0 GL texture ID, or {@code -1} when no shadow renderer is up. */
    private static volatile int shadowDepthTextureId = -1;

    /** Photon's shipped default — also the no-iris fallback. */
    public static final float DEFAULT_SUN_PATH_ROTATION = -35.0F;

    /**
     * The active shaderpack's {@code sunPathRotation} (degrees). Falls back to
     * {@link #DEFAULT_SUN_PATH_ROTATION} when no iris shadow renderer is up, so
     * the mist sun direction stays consistent with Photon (which ships
     * {@code sunPathRotation = -35}) even without a pack.
     */
    private static volatile float sunPathRotation = DEFAULT_SUN_PATH_ROTATION;

    private IrisShadowTextures() {}

    /** Called by {@code ShadowRendererAccessor} whenever iris builds a shadow renderer. */
    public static void setShadowDepthTextureId(int id) {
        shadowDepthTextureId = id;
    }

    /** Current shadowtex0 GL texture ID, or {@code -1} if unavailable. */
    public static int getShadowDepthTextureId() {
        return shadowDepthTextureId;
    }

    /** Called by {@code ShadowRendererAccessor} whenever iris builds a shadow renderer. */
    public static void setSunPathRotation(float rotation) {
        sunPathRotation = rotation;
    }

    /** The active pack's sun path rotation in degrees (Photon default if none). */
    public static float getSunPathRotation() {
        return sunPathRotation;
    }
}