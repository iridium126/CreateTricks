package com.iridium126.createmanaindustry.client.render.mist;

import com.iridium126.createmanaindustry.CreateManaIndustry;
import com.iridium126.createmanaindustry.config.ClientConfig;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.VertexFormat;
import foundry.veil.api.client.render.VeilRenderSystem;
import foundry.veil.api.client.render.shader.program.ShaderProgram;
import net.irisshaders.iris.Iris;
import net.irisshaders.iris.shadows.ShadowRenderTargets;
import net.irisshaders.iris.shadows.ShadowRenderer;
import top.leonx.irisveil.accessors.IrisRenderingPipelineAccessor;
import net.minecraft.client.Minecraft;
import top.leonx.irisveil.IrisVeilCompat;
import top.leonx.irisveil.compat.veil.VeilCompatRegistry;

import org.lwjgl.BufferUtils;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL13;
import org.lwjgl.opengl.GL30;

import java.nio.IntBuffer;
import com.iridium126.createmanaindustry.client.render.shaderpack.ActivePackOptions;
import com.iridium126.createmanaindustry.client.render.shaderpack.IrisShadowTextures;
import com.iridium126.createmanaindustry.client.render.shaderpack.PackShadowParams;
import com.iridium126.createmanaindustry.client.render.shaderpack.ShadowDistortionRegistry;
import com.iridium126.createmanaindustry.client.render.shaderpack.SundialAutoExposure;

/**
 * Renders the mist volumetric shader inside the Iris gbuffer while a shader
 * pack is active.
 * <p>
 * The vanilla Veil post pipeline reads {@code minecraft:main} — but with Iris
 * the world renders into the gbuffer (colortex0) and the post output gets
 * overwritten by Iris's final composite. This hook draws the same full-screen
 * ray-march pass directly into colortex0 at the after-translucent point of
 * {@code LevelRenderer.renderLevel} (via iris-veil-compat's world render
 * hook), so the fog is sampled by the shaderpack's composite pass.
 * <p>
 * Draws the dedicated {@code mist_volumetric_iris} program (kept separate from
 * the vanilla post shader so this hook's manual sampler binds cannot pollute
 * the vanilla pipeline) — the main render target's depth texture holds the
 * full solid world depth at the hook point (iris never clears it), so the
 * depth-based ray endpoint and the march cutoff at the scene surface behave
 * exactly like the vanilla post pipeline, giving full occlusion of the fog
 * behind solid geometry.
 * <p>
 * The samplers are bound manually (unit 0 = colortex0, unit 1 = main depth,
 * unit 2 = the iris shadow map for the Tyndall effect): Veil's sampler-unit
 * bookkeeping misassigns them under Iris (depth sampling returned 0, and
 * mixing Veil binds with manual ones corrupted the colour sampler). Depth
 * sampling parameters are set explicitly as well.
 * <p>
 * All references to iris-veil-compat classes are guarded by
 * {@link CreateManaIndustry#IRISVEIL_ACTIVE} — the classes are only resolved
 * when the mod is actually loaded.
 */
public final class MistIrisHook {

    private static final String HOOK_ID_SCENE = "createmanaindustry:mist";
    private static final String HOOK_ID_TRANSLUCENT = "createmanaindustry:mist_translucent";
    private static final String HOOK_ID_HDR = "createmanaindustry:mist_hdr";
    /** Scene-colour packs: draw into colortex0, like the simulated end-sea compat hook. */
    private static final int[] DRAW_BUFFERS_SCENE = {0};
    /**
     * Bliss-family packs: draw into colortex2, the translucent colour layer their
     * composite chain merges into the frame. Their colortex0 is a clouds/effects
     * buffer that a composite pass rewrites before any reader could pick the
     * scene-colour draw up.
     */
    private static final int[] DRAW_BUFFERS_TRANSLUCENT = {2};
    /**
     * Sundial-family packs: fold into colortex3, the deferred-lit HDR scene
     * buffer their composite chain tonemaps into the display buffer at the very
     * end (their Composite0 zeroes colortex0.rgb outright, so scene-colour draws
     * never reach the screen there).
     */
    private static final int[] DRAW_BUFFERS_HDR = {3};

    private static boolean registered;

    private MistIrisHook() {
    }

    /**
     * Registers the world render hook with iris-veil-compat. Safe to call
     * multiple times; no-op when iris-veil-compat is not loaded.
     */
    public static void init() {
        if (!CreateManaIndustry.IRISVEIL_ACTIVE || registered)
            return;
        registered = true;
        VeilCompatRegistry.registerWorldRenderHook(
                HOOK_ID_SCENE, DRAW_BUFFERS_SCENE,
                MistIrisHook::shouldRenderScene,
                (camera, gameRenderer) -> render(camera, gameRenderer,
                        MistInjectionProfiles.Profile.SCENE_COLOR));
        // Three hooks share one registration site because the compat registry pins
        // the draw buffers per hook; each hook self-gates on the active profile.
        // Only the scene hook ticks the mist — exactly one hook draws per frame.
        VeilCompatRegistry.registerWorldRenderHook(
                HOOK_ID_TRANSLUCENT, DRAW_BUFFERS_TRANSLUCENT,
                MistIrisHook::shouldRenderTranslucent,
                (camera, gameRenderer) -> render(camera, gameRenderer,
                        MistInjectionProfiles.Profile.TRANSLUCENT_LAYER));
        VeilCompatRegistry.registerWorldRenderHook(
                HOOK_ID_HDR, DRAW_BUFFERS_HDR,
                MistIrisHook::shouldRenderHdr,
                (camera, gameRenderer) -> render(camera, gameRenderer,
                        MistInjectionProfiles.Profile.HDR_SCENE));
    }

    /**
     * Whether the iris gbuffer path is currently taking over from the vanilla
     * Veil post pipeline. Consulted by {@link MistClientHandler#syncMistPipeline()}
     * to maintain the "post pipeline XOR iris hook" invariant.
     */
    public static boolean isActivePath() {
        if (!CreateManaIndustry.IRISVEIL_ACTIVE
                || !IrisVeilCompat.isShaderPackInUse()
                || !MistClientHandler.isMistActive())
            return false;
        ShaderProgram shader = getProgram();
        return shader != null && shader.isValid();
    }

    /**
     * Scene-colour hook gate. Ticks the mist animations (the iris path is the
     * only tick point while a shader pack is active — this hook runs every frame
     * regardless of which profile wins) and reconciles the vanilla post pipeline.
     */
    private static boolean shouldRenderScene() {
        MistClientHandler.tickMist();
        MistClientHandler.syncMistPipeline();
        return isActivePath()
                && MistInjectionProfiles.activeProfile() == MistInjectionProfiles.Profile.SCENE_COLOR;
    }

    /** Translucent-layer hook gate — no ticking (the scene hook owns that). */
    private static boolean shouldRenderTranslucent() {
        return isActivePath()
                && MistInjectionProfiles.activeProfile() == MistInjectionProfiles.Profile.TRANSLUCENT_LAYER;
    }

    /** HDR-scene hook gate (Sundial) — no ticking (the scene hook owns that). */
    private static boolean shouldRenderHdr() {
        return isActivePath()
                && MistInjectionProfiles.activeProfile() == MistInjectionProfiles.Profile.HDR_SCENE;
    }

    /**
     * Draws the mist pass into the iris gbuffer. The compat gbuffer framebuffer
     * (writing to colortex0) is already bound by iris-veil-compat; the main
     * render target is restored by the framework afterwards.
     */
    private static boolean render(Object camera, Object gameRenderer,
            MistInjectionProfiles.Profile profile) {
        try {
            ShaderProgram shader = getProgram();
            if (shader == null)
                return false;

            // Exposure-compensation plumbing per profile. Translucent-layer packs
            // store their auto-exposure scalar in colortex4 texel (10,37); Sundial
            // keeps the adapted average brightness in colortex7 texel (0,0).w and
            // multiplies the final frame by avg^-S * 0.2 * 2^EV — the HDR fold
            // pre-divides our added radiance by that product so the calibrated
            // mist brightness survives the pack's auto-exposure. The acquisition
            // binds its own query framebuffer and restores the vanilla main
            // target, so the draw framebuffer must be re-bound through the compat
            // accessor afterwards.
            int exposureTextureId = -1;
            int exposureMode = 0;
            SundialAutoExposure.Params sundialExposure = null;
            if (profile == MistInjectionProfiles.Profile.TRANSLUCENT_LAYER) {
                exposureTextureId = MistExposureSource.acquireExposureTexture(4);
                exposureMode = 1;
            } else if (profile == MistInjectionProfiles.Profile.HDR_SCENE) {
                exposureTextureId = MistExposureSource.acquireExposureTexture(7);
                exposureMode = 2;
                sundialExposure = SundialAutoExposure.resolveForCurrentPack();
            }
            if (exposureMode != 0) {
                if (Iris.getPipelineManager().getPipelineNullable()
                        instanceof IrisRenderingPipelineAccessor pipelineAccessor)
                    pipelineAccessor.irisveil$bindCompatGbufferFramebuffer(
                            profile == MistInjectionProfiles.Profile.TRANSLUCENT_LAYER
                                    ? DRAW_BUFFERS_TRANSLUCENT
                                    : DRAW_BUFFERS_HDR);
            }

            // colortex0's current texture is attached to the bound framebuffer.
            // Querying it avoids the flipped-after-translucent ambiguity of
            // IrisCompat#getRenderTargets (the alt/main texture choice).
            int colortex0Id = GL30.glGetFramebufferAttachmentParameteri(
                    GL30.GL_FRAMEBUFFER, GL30.GL_COLOR_ATTACHMENT0,
                    GL30.GL_FRAMEBUFFER_ATTACHMENT_OBJECT_NAME);

            // Main RT depth — iris writes the full solid world depth here and
            // nothing clears it during the frame. Fetched fresh every frame
            // (the texture can be recreated on resize). The default sampling
            // parameters (NEAREST, CLAMP, no compare mode) are left untouched —
            // overriding them permanently would leak into the vanilla post
            // pipeline after a shaderpack switch.
            int depthId = Minecraft.getInstance().getMainRenderTarget().getDepthTextureId();

            // Mirror Veil's post pipeline state: replace-style blend since the
            // shader outputs the final color (occlusion is handled inside the
            // shader by the depth-based march cutoff, like the vanilla path).
            RenderSystem.enableBlend();
            RenderSystem.blendFuncSeparate(GL11.GL_ONE, GL11.GL_ZERO, GL11.GL_ONE, GL11.GL_ZERO);

            shader.bind();
            // Bind both samplers manually — Veil's sampler-unit bookkeeping can
            // misassign them under Iris (depth sampling returned 0 before, and
            // mixing it with manual binds corrupted the colour sampler), so pin
            // unit 0 = colortex0 and unit 1 = main depth explicitly.
            int col0Uniform = shader.getUniformLocation("DiffuseSampler0");
            int depthUniform = shader.getUniformLocation("DiffuseDepthSampler");
            RenderSystem.activeTexture(GL13.GL_TEXTURE0);
            GL11.glBindTexture(GL11.GL_TEXTURE_2D, colortex0Id);
            if (col0Uniform >= 0)
                GL30.glUniform1i(col0Uniform, 0);
            RenderSystem.activeTexture(GL13.GL_TEXTURE1);
            GL11.glBindTexture(GL11.GL_TEXTURE_2D, depthId);
            if (depthUniform >= 0)
                GL30.glUniform1i(depthUniform, 1);

            // --- Tyndall: bind the iris shadow map (shadowtex0) to unit 2 ---
            // The shader texelFetches raw depth and compares it itself, so the
            // texture's filter/compare state is left untouched (no leak into the
            // pack's own sampling). The texture id is captured by the
            // ShadowRendererAccessor mixin when iris builds its shadow renderer;
            // when the pack has no shadows (or the renderer isn't up yet) it is
            // -1 and ShadowMapBound goes to 0, making the shader treat every
            // sample as lit.
            int shadowDepthId = IrisShadowTextures.getShadowDepthTextureId();
            int shadowMapBound = shadowDepthId >= 0 ? 1 : 0;
            float shadowMapResolution = 0.0f;
            int shadowUniform = shader.getUniformLocation("ShadowMap0");
            int shadowBoundUniform = shader.getUniformLocation("ShadowMapBound");
            int shadowResUniform = shader.getUniformLocation("ShadowMapResolution");
            if (shadowMapBound == 1) {
                RenderSystem.activeTexture(GL13.GL_TEXTURE2);
                GL11.glBindTexture(GL11.GL_TEXTURE_2D, shadowDepthId);
                // Actual texture width — iris may render shadows at a quality-scaled
                // resolution, so texelFetch coordinates must match the real size.
                IntBuffer size = BufferUtils.createIntBuffer(1);
                GL11.glGetTexLevelParameteriv(GL11.GL_TEXTURE_2D, 0, GL11.GL_TEXTURE_WIDTH, size);
                shadowMapResolution = size.get(0);
            }
            if (shadowUniform >= 0)
                GL30.glUniform1i(shadowUniform, 2);
            if (shadowBoundUniform >= 0)
                GL30.glUniform1i(shadowBoundUniform, shadowMapBound);
            if (shadowResUniform >= 0)
                GL30.glUniform1f(shadowResUniform, shadowMapResolution);

            // --- Colored translucent shadows (Bliss TRANSLUCENT_COLORED_SHADOWS):
            // bind shadowtex1 (opaque-only depth) and shadowcolor0 so the shader
            // can transmit the caster's colour where ONLY translucent geometry
            // blocks the sun, mirroring the pack's own three-sample fog stage.
            boolean coloredStage = false;
            int opaqueDepthId = IrisShadowTextures.getOpaqueDepthTextureId();
            if (ActivePackOptions.isEnabled("TRANSLUCENT_COLORED_SHADOWS") && opaqueDepthId >= 0) {
                if (IrisShadowTextures.getShadowTargets()
                        instanceof ShadowRenderTargets shadowTargets) {
                    // shadowcolor0 is created lazily by iris exactly when a pack
                    // references it; forcing it here matches that behaviour and
                    // is gated on the pack option being on.
                    var colorTarget = shadowTargets.getOrCreate(0);
                    int shadowColorId = shadowTargets.isFlipped(0)
                            ? colorTarget.getAltTexture()
                            : colorTarget.getMainTexture();

                    RenderSystem.activeTexture(GL13.GL_TEXTURE4);
                    GL11.glBindTexture(GL11.GL_TEXTURE_2D, opaqueDepthId);
                    RenderSystem.activeTexture(GL13.GL_TEXTURE5);
                    GL11.glBindTexture(GL11.GL_TEXTURE_2D, Math.max(shadowColorId, 0));
                    coloredStage = shadowColorId >= 0;
                }
            }
            if (!coloredStage) {
                RenderSystem.activeTexture(GL13.GL_TEXTURE4);
                GL11.glBindTexture(GL11.GL_TEXTURE_2D, 0);
                RenderSystem.activeTexture(GL13.GL_TEXTURE5);
                GL11.glBindTexture(GL11.GL_TEXTURE_2D, 0);
            }
            int shadow1Uniform = shader.getUniformLocation("ShadowMap1");
            if (shadow1Uniform >= 0)
                GL30.glUniform1i(shadow1Uniform, 4);
            int shadowColorUniform = shader.getUniformLocation("ShadowColor0");
            if (shadowColorUniform >= 0)
                GL30.glUniform1i(shadowColorUniform, 5);
            int shadow1BoundUniform = shader.getUniformLocation("ShadowMap1Bound");
            if (shadow1BoundUniform >= 0)
                GL30.glUniform1i(shadow1BoundUniform, coloredStage ? 1 : 0);
            var coloredUniform = shader.getUniform("ColoredShadows");
            if (coloredUniform != null)
                coloredUniform.setInt(coloredStage ? 1 : 0);

            // Exposure-compensation sampler: unit 3 carries the pack's exposure
            // buffer (colortex4 for translucent-layer packs, colortex7 for the
            // Sundial HDR fold); ExposureMode tells the shader which texel and
            // formula to apply.
            if (exposureMode != 0) {
                var exposureUniform = shader.getUniformLocation("ExposureSampler");
                RenderSystem.activeTexture(GL13.GL_TEXTURE3);
                GL11.glBindTexture(GL11.GL_TEXTURE_2D, Math.max(exposureTextureId, 0));
                if (exposureUniform >= 0)
                    GL30.glUniform1i(exposureUniform, 3);
                var exposureBoundUniform = shader.getUniform("ExposureBound");
                if (exposureBoundUniform != null)
                    exposureBoundUniform.setInt(exposureTextureId >= 0 ? 1 : 0);
                var exposureModeUniform = shader.getUniform("ExposureMode");
                if (exposureModeUniform != null)
                    exposureModeUniform.setInt(exposureMode);
                var exposureParamsUniform = shader.getUniform("ExposureParams");
                if (exposureParamsUniform != null)
                    exposureParamsUniform.setVector(
                            exposureMode == 2 && sundialExposure != null ? sundialExposure.strength() : 0.0F,
                            exposureMode == 2 && sundialExposure != null ? sundialExposure.exposureValue() : 0.0F,
                            0.0F, 0.0F);
            }

            MistClientHandler.applyMistUniforms(shader);

            // Tyndall shadow matrices — the current frame's shadow pass state
            // (public statics, refreshed every frame by iris's renderShadows()).
            var shadowModelView = shader.getUniform("ShadowModelView");
            if (shadowModelView != null && ShadowRenderer.MODELVIEW != null)
                shadowModelView.setMatrix(ShadowRenderer.MODELVIEW);
            var shadowProjection = shader.getUniform("ShadowProjection");
            if (shadowProjection != null && ShadowRenderer.PROJECTION != null)
                shadowProjection.setMatrix(ShadowRenderer.PROJECTION);

            // Tyndall shadow distortion — resolve the active pack's convention
            // (cached) and apply the same remap the pack baked into its shadow
            // map.
            PackShadowParams distortion = ShadowDistortionRegistry.resolveForCurrentPack();
            var modeUniform = shader.getUniform("ShadowDistortionMode");
            if (modeUniform != null)
                modeUniform.setInt(distortion.glslMode());
            var distUniform = shader.getUniform("ShadowDistortion");
            if (distUniform != null)
                distUniform.setFloat(distortion.bias());
            var depthScaleUniform = shader.getUniform("ShadowDepthScale");
            if (depthScaleUniform != null)
                depthScaleUniform.setFloat(distortion.depthScale());
            var debugShadowUniform = shader.getUniform("DebugShadowVisualization");
            if (debugShadowUniform != null)
                debugShadowUniform.setInt(ClientConfig.mistDebugShadow ? 1 : 0);

            // Injection target: 0 = composite over the sampled scene colour, 1 =
            // premultiplied under-operator into the pack's translucent layer
            // (Bliss-family colortex2).
            var targetUniform = shader.getUniform("MistTargetMode");
            if (targetUniform != null)
                targetUniform.setInt(
                        profile == MistInjectionProfiles.Profile.TRANSLUCENT_LAYER ? 1 : 0);
            // Logarithmic distortion curve (mode 3): x=k, y=a, z=b, w=z scale.
            var logUniform = shader.getUniform("ShadowLogParams");
            if (logUniform != null)
                logUniform.setVector(distortion.logK(), distortion.logA(), distortion.logB(),
                        distortion.depthScale());

            shader.setDefaultUniforms(VertexFormat.Mode.TRIANGLE_STRIP);
            VeilRenderSystem.drawScreenQuad();

            ShaderProgram.unbind();
            RenderSystem.disableBlend();
            RenderSystem.defaultBlendFunc();
            // Restore the conventional active texture unit — the manual binds
            // left it on unit 1, and render code generally assumes unit 0. The
            // texture bindings themselves are left alone: iris and the vanilla
            // pipeline rebind whatever they sample on every draw.
            RenderSystem.activeTexture(GL13.GL_TEXTURE0);
            return true;
        } catch (RuntimeException | LinkageError e) {
            CreateManaIndustry.LOGGER.warn("Iris mist hook render failed", e);
            return false;
        }
    }

    /** Fetches the mist shader program fresh each frame (survives shader reloads). */
    private static ShaderProgram getProgram() {
        return VeilRenderSystem.renderer().getShaderManager()
                .getShader(CreateManaIndustry.modLoc("mist_volumetric_iris"));
    }
}