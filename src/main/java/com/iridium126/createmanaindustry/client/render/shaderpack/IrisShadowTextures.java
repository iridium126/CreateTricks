package com.iridium126.createmanaindustry.client.render.shaderpack;

/**
 * Bridges iris-side shadow values captured by the {@code ShadowRendererAccessor}
 * mixin to the client renderers. A plain holder avoids the mixin-merging pitfall
 * where a {@code @Unique} static field on the mixin class and the field merged
 * into the target class are two distinct copies.
 * <p>
 * Holds the values that must be captured from iris internals at render time:
 * the shadow depth texture ids ({@code shadowtex0} for the mist Tyndall
 * sampling, {@code shadowtex1} for its opaque-only stage), the pack's
 * {@code sunPathRotation} (for the corrected sun direction), and a live handle
 * on iris' shadow render targets so lazily-created color textures
 * ({@code shadowcolor0}) can be resolved per frame. The distortion parameters
 * themselves are resolved separately by {@link ShadowDistortionRegistry}.
 * <p>
 * The captured scalars are plain values, so this class stays safe to load in
 * any environment; the {@code targets} handle is kept as an opaque
 * {@code Object} and must only be resolved under an active iris (the mist hook
 * runs only when IRISVEIL_ACTIVE implies iris).
 */
public final class IrisShadowTextures {

    /** shadowtex0 GL texture ID (includes translucent casters), or {@code -1}. */
    private static volatile int shadowDepthTextureId = -1;

    /** shadowtex1 GL texture ID (opaque-only depth), or {@code -1}. */
    private static volatile int opaqueDepthTextureId = -1;

    /**
     * Live handle on iris' {@code ShadowRenderTargets}, held opaquely so this
     * class keeps loading without iris; {@code null} when no renderer is up.
     */
    private static volatile Object shadowTargets;

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
    public static void setOpaqueDepthTextureId(int id) {
        opaqueDepthTextureId = id;
    }

    /** Current shadowtex1 GL texture ID (opaque-only), or {@code -1} if unavailable. */
    public static int getOpaqueDepthTextureId() {
        return opaqueDepthTextureId;
    }

    /** Called by {@code ShadowRendererAccessor}; holds an opaque iris-side object. */
    public static void setShadowTargets(Object targets) {
        shadowTargets = targets;
    }

    /** The live iris shadow render targets, or {@code null} when unavailable. */
    public static Object getShadowTargets() {
        return shadowTargets;
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
