package com.iridium126.createmanaindustry.client.dimension.iris;

import java.util.function.IntSupplier;

import org.joml.Matrix4f;

import com.iridium126.createmanaindustry.mixin.allvriris.accessor.AllvrIrisRenderingPipelineAccessor;

/**
 * Session-wide binding between the allay dimension's iris pipeline and the
 * draw-mounting slice: the {@link AllvrIrisPipelineMixin} publishes the latest
 * built allay-dimension pipeline data here, and {@code AllvrRenderer} consumes
 * it without ever touching an iris type itself.
 * <p>
 * This class is the iris-class isolation boundary for the render package: it
 * loads without iris (the iris-typed members below resolve lazily and are only
 * executed under the renderer's pack-in-use gates) and every accessor returns
 * plain ints, {@link Matrix4f} copies or our own data object. The shadow
 * helpers read iris's public static {@code ShadowRenderer} state, which holds
 * the values of the shadow pass that ran earlier in the same frame.
 */
public final class AllvrIrisDataHolder {

    /** The owning {@code IrisRenderingPipeline}, held as {@link Object} so this
     *  class never triggers an iris class load through field types. */
    private static volatile Object owner;
    private static volatile AllvrIrisPipelineData data;

    private AllvrIrisDataHolder() {}

    /**
     * Publishes the allay dimension's pipeline data. Called from the pipeline
     * mixin for every allay-dimension pipeline build — including the
     * no-voxy.json case ({@code null}), which clears a stale entry from a
     * previously loaded pack instead of leaving dead draw targets behind.
     */
    public static void set(Object pipeline, AllvrIrisPipelineData pipelineData) {
        owner = pipeline;
        data = pipelineData;
    }

    /** The allay dimension's patch data, or null (no pack / no voxy.json / not built yet). */
    public static AllvrIrisPipelineData current() {
        return data;
    }

    /** The iris render-target pool's main depth texture id (depthtex0), for the
     *  terrain FBO's depth attachment. Throws nothing; callers gate on iris. */
    public static IntSupplier depthSupplier() {
        Object pipeline = owner;
        return () -> ((AllvrIrisRenderingPipelineAccessor) pipeline).getRenderTargets().getDepthTexture();
    }

    // ---- shadow pass (iris renders shadows earlier in the same frame; we
    //      depth-only draw our terrain on top of that content) ---------------

    /** Copy of the shadow modelview, or null when no shadow pass ran yet. */
    public static Matrix4f shadowModelView() {
        var mv = net.irisshaders.iris.shadows.ShadowRenderer.MODELVIEW;
        return mv == null ? null : new Matrix4f(mv);
    }

    /** Copy of the shadow projection, or null when no shadow pass ran yet. */
    public static Matrix4f shadowProjection() {
        var p = net.irisshaders.iris.shadows.ShadowRenderer.PROJECTION;
        return p == null ? null : new Matrix4f(p);
    }

    /** The shadow map's depth texture (shadowtex0), or -1 when shadows are off. */
    public static int shadowDepthTexture() {
        var srt = shadowTargets();
        return srt == null ? -1 : srt.getDepthTexture().getTextureId();
    }

    /** shadowMapResolution, or -1 when shadows are off. */
    public static int shadowResolution() {
        var srt = shadowTargets();
        return srt == null ? -1 : srt.getResolution();
    }

    private static net.irisshaders.iris.shadows.ShadowRenderTargets shadowTargets() {
        Object pipeline = owner;
        if (pipeline == null) {
            return null;
        }
        return ((AllvrIrisRenderingPipelineAccessor) pipeline).getShadowRenderTargets();
    }
}
