package com.iridium126.createmanaindustry.client.dimension.iris;

import java.nio.ByteBuffer;
import java.util.function.IntSupplier;

import org.lwjgl.opengl.GL11C;
import org.lwjgl.opengl.GL15C;
import org.lwjgl.opengl.GL30C;
import org.lwjgl.opengl.GL31C;
import org.lwjgl.opengl.GL33C;
import org.lwjgl.opengl.GL43C;
import org.lwjgl.opengl.GL45C;
import org.lwjgl.system.MemoryUtil;

import com.iridium126.createmanaindustry.CreateManaIndustry;

/**
 * ALLVR's terrain draw targets under an active shader pack (G2 draw mounting):
 * a self-managed framebuffer whose color attachments are the pack's colortex
 * textures resolved by {@link AllvrIrisPipelineData} (flip-aware, voxy's same
 * resolution) and whose depth attachment is iris's main depth (depthtex0), so
 * the patched terrain pass depth-tests against the vanilla terrain drawn
 * earlier in the gbuffer phase and its pixels land where the pack's deferred
 * passes expect them.
 * <p>
 * Also owns the patch's uniform UBO (values written by the layout updater each
 * frame) and the depth-only shadow framebuffer for the shadow-pass draw.
 * <p>
 * State discipline: the caller saves/restores the bound framebuffer and
 * viewport around {@link #bind()} (the vanilla translucents and iris's own
 * passes follow in the same frame); {@link #unbind()} releases our UBO /
 * sampler / SSBO bindings so nothing downstream reads stale units.
 */
public final class AllvrIrisFrameTarget {

    private final AllvrIrisPipelineData data;
    private final IntSupplier depthSupplier;

    private int colorFbo;
    private final int[] attachedTextures = new int[8];
    private int attachedCount = -1;
    private int attachedDepth = -1;
    private boolean attachedOk;

    private int viewportW;
    private int viewportH;
    private boolean warnedViewportMismatch;
    private boolean warnedIncomplete;

    private int uniformBuffer;
    private long uniformScratch;
    private int uniformSize = -1;

    private int shadowFbo;
    private int shadowDepth = -1;

    public AllvrIrisFrameTarget(AllvrIrisPipelineData data, IntSupplier depthSupplier) {
        this.data = data;
        this.depthSupplier = depthSupplier;
    }

    /** The depth texture of this target (wired into the pipeline data as the
     *  vxDepthTex* sampler source; both opaque and translucent share it — the
     *  V0 terrain pass is opaque-only). */
    public int depthTexture() {
        return this.depthSupplier.getAsInt();
    }

    /**
     * Per-frame setup: (re)attaches the pack's colortex textures and the depth
     * texture when they changed, sizes the viewport, uploads the uniform UBO.
     * Returns false when the framebuffer is incomplete (dead pack textures —
     * the caller falls back to the unpatched draw).
     */
    public boolean beginFrame(int mainWidth, int mainHeight) {
        if (mainWidth <= 0 || mainHeight <= 0) {
            return false;
        }
        if (this.colorFbo == 0) {
            this.colorFbo = GL45C.glCreateFramebuffers();
            this.attachedCount = -1;
            this.attachedDepth = -1;
        }
        int[] targets = this.data.opaqueDrawTargets;
        boolean colorsChanged = this.attachedCount != targets.length;
        if (!colorsChanged) {
            for (int i = 0; i < targets.length; i++) {
                colorsChanged |= this.attachedTextures[i] != targets[i];
            }
        }
        if (colorsChanged) {
            this.attachedCount = targets.length;
            for (int i = 0; i < targets.length; i++) {
                this.attachedTextures[i] = targets[i];
                GL45C.glNamedFramebufferTexture(this.colorFbo, GL30C.GL_COLOR_ATTACHMENT0 + i, targets[i], 0);
            }
            for (int i = targets.length; i < this.attachedTextures.length; i++) {
                GL45C.glNamedFramebufferTexture(this.colorFbo, GL30C.GL_COLOR_ATTACHMENT0 + i, 0, 0);
            }
            int[] drawBuffers = new int[targets.length];
            for (int i = 0; i < drawBuffers.length; i++) {
                drawBuffers[i] = GL30C.GL_COLOR_ATTACHMENT0 + i;
            }
            GL45C.glNamedFramebufferDrawBuffers(this.colorFbo, drawBuffers);
        }
        int depth = this.depthSupplier.getAsInt();
        int prevDepth = this.attachedDepth;
        if (depth != prevDepth) {
            this.attachedDepth = depth;
            GL45C.glNamedFramebufferTexture(this.colorFbo, GL30C.GL_DEPTH_ATTACHMENT, depth, 0);
        }
        if (colorsChanged || depth != prevDepth) {
            this.attachedOk = GL45C.glCheckNamedFramebufferStatus(this.colorFbo, GL30C.GL_FRAMEBUFFER)
                == GL30C.GL_FRAMEBUFFER_COMPLETE;
            if (!this.attachedOk && !this.warnedIncomplete) {
                this.warnedIncomplete = true;
                CreateManaIndustry.LOGGER.error("[Allvr] terrain frame target incomplete (pack targets changed?) "
                    + "— falling back to the unpatched draw");
            }
        }
        if (!this.attachedOk) {
            return false;
        }
        // the pack's colortex may be smaller than the main target (TAAU /
        // renderScale) — draw at the texture's size so nothing clips; the
        // full TAAU upscale path stays out of scope (grilling decision ⑤)
        this.viewportW = this.data.opaqueWidth > 0 ? this.data.opaqueWidth : mainWidth;
        this.viewportH = this.data.opaqueHeight > 0 ? this.data.opaqueHeight : mainHeight;
        if (!this.warnedViewportMismatch && (this.viewportW != mainWidth || this.viewportH != mainHeight)) {
            this.warnedViewportMismatch = true;
            CreateManaIndustry.LOGGER.warn(
                "[Allvr] pack colortex ({}x{}) differs from the main target ({}x{}) — drawing at the colortex size",
                this.viewportW, this.viewportH, mainWidth, mainHeight);
        }
        this.uploadUniforms();
        return true;
    }

    private void uploadUniforms() {
        var uniforms = this.data.getUniforms();
        if (uniforms == null) {
            return;
        }
        if (this.uniformSize != uniforms.size()) {
            if (this.uniformScratch != 0) {
                MemoryUtil.nmemFree(this.uniformScratch);
                this.uniformScratch = 0;
            }
            if (this.uniformBuffer != 0) {
                GL15C.glDeleteBuffers(this.uniformBuffer);
                this.uniformBuffer = 0;
            }
            this.uniformSize = uniforms.size();
            this.uniformScratch = MemoryUtil.nmemAlloc(this.uniformSize);
            MemoryUtil.memSet(this.uniformScratch, 0, this.uniformSize);
            this.uniformBuffer = GL15C.glGenBuffers();
            GL15C.glBindBuffer(GL31C.GL_UNIFORM_BUFFER, this.uniformBuffer);
            GL15C.glBufferData(GL31C.GL_UNIFORM_BUFFER, this.uniformSize, GL15C.GL_DYNAMIC_DRAW);
            GL15C.glBindBuffer(GL31C.GL_UNIFORM_BUFFER, 0);
        }
        uniforms.updater().accept(this.uniformScratch);
        ByteBuffer view = MemoryUtil.memByteBuffer(this.uniformScratch, this.uniformSize);
        GL45C.glNamedBufferSubData(this.uniformBuffer, 0, view);
    }

    /** Binds the terrain FBO + viewport + the patch's UBO/sampler/SSBO bindings. */
    public void bind() {
        GL30C.glBindFramebuffer(GL30C.GL_FRAMEBUFFER, this.colorFbo);
        GL11C.glViewport(0, 0, this.viewportW, this.viewportH);
        if (this.uniformBuffer != 0) {
            GL30C.glBindBufferBase(GL31C.GL_UNIFORM_BUFFER, AllvrIrisPipelineData.UNIFORM_UBO_BINDING, this.uniformBuffer);
        }
        var images = this.data.getImageSet();
        if (images != null) {
            images.bindingFunction().accept(AllvrIrisPipelineData.SAMPLER_BINDING_BASE);
        }
        var ssbos = this.data.getSsboSet();
        if (ssbos != null) {
            ssbos.bindingFunction().accept(AllvrIrisPipelineData.SSBO_BINDING_BASE);
        }
    }

    /** Releases our bindings (the pack's own passes rebind theirs, but nothing
     *  should inherit our units by accident). */
    public void unbind() {
        GL30C.glBindBufferBase(GL31C.GL_UNIFORM_BUFFER, AllvrIrisPipelineData.UNIFORM_UBO_BINDING, 0);
        var images = this.data.getImageSet();
        if (images != null) {
            for (int i = 0; i < images.samplerCount(); i++) {
                int unit = AllvrIrisPipelineData.SAMPLER_BINDING_BASE + i;
                org.lwjgl.opengl.ARBDirectStateAccess.glBindTextureUnit(unit, 0);
                GL33C.glBindSampler(unit, 0);
            }
        }
        var ssbos = this.data.getSsboSet();
        if (ssbos != null) {
            for (int i = 0; i < ssbos.bindingCount(); i++) {
                GL30C.glBindBufferBase(GL43C.GL_SHADER_STORAGE_BUFFER,
                    AllvrIrisPipelineData.SSBO_BINDING_BASE + i, 0);
            }
        }
    }

    /**
     * Binds (and lazily creates) the depth-only shadow framebuffer against the
     * pack's shadow map. Returns false when the shadow targets are unavailable.
     */
    public boolean bindShadow(int shadowDepthTexture, int resolution) {
        if (shadowDepthTexture <= 0 || resolution <= 0) {
            return false;
        }
        if (this.shadowFbo == 0) {
            this.shadowFbo = GL45C.glCreateFramebuffers();
            GL45C.glNamedFramebufferDrawBuffer(this.shadowFbo, GL11C.GL_NONE);
            GL45C.glNamedFramebufferReadBuffer(this.shadowFbo, GL11C.GL_NONE);
        }
        if (shadowDepthTexture != this.shadowDepth) {
            this.shadowDepth = shadowDepthTexture;
            GL45C.glNamedFramebufferTexture(this.shadowFbo, GL30C.GL_DEPTH_ATTACHMENT, shadowDepthTexture, 0);
        }
        return GL45C.glCheckNamedFramebufferStatus(this.shadowFbo, GL30C.GL_FRAMEBUFFER)
            == GL30C.GL_FRAMEBUFFER_COMPLETE;
    }

    /** The depth-only shadow framebuffer id (valid after a {@link #bindShadow} success). */
    public int shadowFbo() {
        return this.shadowFbo;
    }

    public void destroy() {
        if (this.colorFbo != 0) {
            GL30C.glDeleteFramebuffers(this.colorFbo);
            this.colorFbo = 0;
        }
        if (this.shadowFbo != 0) {
            GL30C.glDeleteFramebuffers(this.shadowFbo);
            this.shadowFbo = 0;
        }
        if (this.uniformBuffer != 0) {
            GL15C.glDeleteBuffers(this.uniformBuffer);
            this.uniformBuffer = 0;
        }
        if (this.uniformScratch != 0) {
            MemoryUtil.nmemFree(this.uniformScratch);
            this.uniformScratch = 0;
        }
        this.uniformSize = -1;
        this.attachedCount = -1;
        this.attachedDepth = -1;
        this.shadowDepth = -1;
    }
}
