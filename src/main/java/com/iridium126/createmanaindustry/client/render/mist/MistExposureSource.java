package com.iridium126.createmanaindustry.client.render.mist;

import com.google.common.collect.ImmutableSet;

import com.iridium126.createmanaindustry.mixin.iris.IrisRenderingPipelineTargetsAccessor;

import net.irisshaders.iris.Iris;
import net.irisshaders.iris.gl.framebuffer.GlFramebuffer;
import net.irisshaders.iris.pipeline.IrisRenderingPipeline;
import net.irisshaders.iris.targets.RenderTargets;
import net.minecraft.client.Minecraft;

import org.lwjgl.opengl.GL30;

/**
 * Locates the active shaderpack's colortex4 GL texture so the translucent-layer
 * mist mode can read the pack's auto-exposure scalar. Bliss (and chocapic-family
 * packs generally) keep that scalar in colortex4 texel {@code (10, 37)} — the
 * same value their final composite multiplies the frame by.
 * <p>
 * colortex4 is written only during the deferred stage, so from the
 * after-translucent point onward it is never re-flipped: whichever texture the
 * after-translucent flip set selects is exactly the copy the composite stage
 * reads. The query binds a throwaway framebuffer writing to that texture and
 * reads back the colour-attachment object name.
 * <p>
 * All iris references are resolved lazily; callers must be on the render thread
 * with iris active ({@code IRISVEIL_ACTIVE} implies iris).
 */
public final class MistExposureSource {

    /** The colour buffer holding the pack's exposure scalar (colortex4). */
    private static final int EXPOSURE_BUFFER = 4;

    private static Object lastPipeline;
    private static int lastWidth = -1;
    private static int lastHeight = -1;
    private static GlFramebuffer mainQueryFbo;
    private static GlFramebuffer altQueryFbo;

    private MistExposureSource() {}

    /**
     * Binds a query framebuffer for colortex4 and returns its texture id, or
     * {@code -1} when iris/pipeline is unavailable. Restores the vanilla main
     * render target afterwards — the caller must re-bind its draw framebuffer
     * before drawing.
     */
    public static int acquireExposureTexture() {
        if (!(Iris.getPipelineManager().getPipelineNullable()
                instanceof IrisRenderingPipelineTargetsAccessor accessor))
            return -1;

        IrisRenderingPipeline pipeline = (IrisRenderingPipeline) (Object) accessor;
        RenderTargets targets = accessor.cmi$getRenderTargets();
        int width = targets.getCurrentWidth();
        int height = targets.getCurrentHeight();

        // Recreate the query framebuffers when the pipeline (pack reload) or the
        // render-target size changes — both invalidate the old FBOs.
        if (mainQueryFbo == null || lastPipeline != pipeline
                || lastWidth != width || lastHeight != height) {
            destroyQueryFbos();
            mainQueryFbo = targets.createFramebufferWritingToMain(new int[]{EXPOSURE_BUFFER});
            altQueryFbo = targets.createFramebufferWritingToAlt(new int[]{EXPOSURE_BUFFER});
            lastPipeline = pipeline;
            lastWidth = width;
            lastHeight = height;
        }

        ImmutableSet<Integer> flipped = accessor.cmi$getFlippedAfterTranslucent();
        GlFramebuffer query = flipped != null && flipped.contains(EXPOSURE_BUFFER)
                ? altQueryFbo
                : mainQueryFbo;
        query.bind();
        int textureId = GL30.glGetFramebufferAttachmentParameteri(
                GL30.GL_FRAMEBUFFER, GL30.GL_COLOR_ATTACHMENT0,
                GL30.GL_FRAMEBUFFER_ATTACHMENT_OBJECT_NAME);
        Minecraft.getInstance().getMainRenderTarget().bindWrite(false);
        return textureId;
    }

    private static void destroyQueryFbos() {
        if (mainQueryFbo != null) {
            mainQueryFbo.destroy();
            mainQueryFbo = null;
        }
        if (altQueryFbo != null) {
            altQueryFbo.destroy();
            altQueryFbo = null;
        }
    }
}