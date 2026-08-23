package com.iridium126.createmanaindustry.client.render.fuelrod;

import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import com.iridium126.createmanaindustry.CreateManaIndustry;
import com.iridium126.createmanaindustry.client.render.mist.MistExposureSource;
import com.iridium126.createmanaindustry.client.render.mist.MistInjectionProfiles;
import com.iridium126.createmanaindustry.client.render.shaderpack.SundialAutoExposure;

import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.VertexFormat;
import foundry.veil.api.client.render.VeilRenderSystem;
import foundry.veil.api.client.render.shader.program.ShaderProgram;
import net.irisshaders.iris.Iris;
import net.minecraft.client.Minecraft;
import top.leonx.irisveil.IrisVeilCompat;
import top.leonx.irisveil.accessors.IrisRenderingPipelineAccessor;
import top.leonx.irisveil.compat.veil.VeilCompatRegistry;

import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL13;
import org.lwjgl.opengl.GL30;
import com.iridium126.createmanaindustry.client.render.mist.MistIrisHook;

/**
 * Draws the fuel rod bloom shader inside the Iris gbuffer while a shader pack
 * is active (mirrors {@code MistIrisHook}, without the shadow/tyndall parts).
 * <p>
 * With Iris the world renders into the gbuffer and Veil's post pipelines are
 * not composited, so this hook draws the same full-screen glow pass directly
 * into the pack's buffers at the world render hook point of iris-veil-compat;
 * the shaderpack's own composite (and bloom, if it has one) then processes the
 * added glow.
 * <p>
 * Three registrations exist because the compat registry pins the draw buffers
 * per hook; each hook self-gates on the active mist injection profile
 * ({@link MistInjectionProfiles}):
 * <ul>
 * <li>Scene-colour packs (default): replace-style draw into {@code colortex0}
 * — the shader re-emits the sampled scene colour plus the glow.</li>
 * <li>Bliss-family packs: their colortex0 is a clouds/volumetric-effects
 * buffer that a composite pass overwrites before any reader could pick the
 * draw up, so a scene-colour draw there never reaches the screen. The bliss
 * hook instead folds the glow into {@code colortex2}, the translucent colour
 * layer their composite chain merges into the frame — reading the layer back,
 * adding the glow additively on top of it at the pack's 0.1x storage scale
 * and compensating the pack's final auto-exposure multiply. The fold is
 * deliberately not weighted by the layer's coverage alpha: the co-drawn mist
 * pass raises that alpha towards 1 across its own silhouette, and a
 * coverage-weighted fold would cancel the ring behind exactly that mist
 * (see {@code fuel_rod_glow_iris.fsh}).</li>
 * <li>iterationT-family packs go further still: their gbuffer colour holds
 * unlit albedo and the lighting pass runs after translucent, so ANY scene-
 * colour draw would be relit as surface albedo. The raw hook renders the
 * tonemapped ring radiance into the unused {@code colortex10}, which the
 * patched lighting pass adds over its lit image next to the mist layer
 * ({@code colortex9}) — see {@code IterationTColoredLightAdapter}.</li>
 * </ul>
 * The samplers are bound manually (unit 0 = scene colour, unit 1 = main depth,
 * unit 3 = the pack's exposure scalar), the same way {@code MistIrisHook} does
 * — Veil's sampler-unit bookkeeping misassigns them under Iris. All references
 * to iris-veil-compat classes are guarded by {@link CreateManaIndustry#IRISVEIL_ACTIVE}.
 */
public final class FuelRodBloomIrisHook {

    private static final String HOOK_ID = "createmanaindustry:fuel_rod_glow";
    private static final String HOOK_ID_BLISS = "createmanaindustry:fuel_rod_glow_bliss";
    private static final String HOOK_ID_HDR = "createmanaindustry:fuel_rod_glow_hdr";
    private static final String HOOK_ID_RAW = "createmanaindustry:fuel_rod_glow_raw";
    /** Scene-colour packs: replace-style draw into colortex0, like the default mist hook. */
    private static final int[] DRAW_BUFFERS = {0};
    /**
     * Bliss-family packs: fold the glow into colortex2, the translucent colour
     * layer their composite chain merges into the frame.
     */
    private static final int[] DRAW_BUFFERS_BLISS = {2};
    /**
     * Sundial-family packs: fold the glow into colortex3, the deferred-lit HDR
     * scene buffer their composite chain tonemaps into the display buffer at the
     * very end (their Composite0 zeroes colortex0.rgb outright, so scene-colour
     * draws never reach the screen there).
     */
    private static final int[] DRAW_BUFFERS_HDR = {3};
    /**
     * iterationT-family packs: render the tonemapped ring radiance as a raw
     * additive layer into colortex10, a buffer the pack never touches. The
     * in-memory patch installed by IterationTColoredLightAdapter makes the
     * pack's own lighting pass (composite.fsh) add this buffer over its lit
     * image alongside the mist layer in colortex9 — the gbuffer colour there
     * holds unlit albedo, so a scene-colour draw would be relit as surface
     * albedo and collapse under the pack's lighting.
     */
    private static final int[] DRAW_BUFFERS_RAW = {10};

    private static boolean registered;

    /** First successful draw per hook, so a run's log is self-describing. */
    private static final Set<String> DRAW_LOGGED =
            ConcurrentHashMap.newKeySet();

    private FuelRodBloomIrisHook() {
    }

    /**
     * Registers the world render hooks with iris-veil-compat. Safe to call
     * multiple times; no-op when iris-veil-compat is not loaded.
     */
    public static void init() {
        if (!CreateManaIndustry.IRISVEIL_ACTIVE || registered)
            return;
        registered = true;
        // Only the scene-colour hook ticks the rod animation: its gate runs every
        // frame regardless of which registration draws (the bliss gate runs on
        // the same frames and must not double-step the animations).
        VeilCompatRegistry.registerWorldRenderHook(
                HOOK_ID, DRAW_BUFFERS,
                FuelRodBloomIrisHook::shouldRenderSceneColor,
                (camera, gameRenderer) -> render(camera, gameRenderer,
                        MistInjectionProfiles.Profile.SCENE_COLOR));
        VeilCompatRegistry.registerWorldRenderHook(
                HOOK_ID_BLISS, DRAW_BUFFERS_BLISS,
                FuelRodBloomIrisHook::shouldRenderBliss,
                (camera, gameRenderer) -> render(camera, gameRenderer,
                        MistInjectionProfiles.Profile.TRANSLUCENT_LAYER));
        VeilCompatRegistry.registerWorldRenderHook(
                HOOK_ID_HDR, DRAW_BUFFERS_HDR,
                FuelRodBloomIrisHook::shouldRenderHdr,
                (camera, gameRenderer) -> render(camera, gameRenderer,
                        MistInjectionProfiles.Profile.HDR_SCENE));
        // Like the other non-scene gates, this one does not tick: the
        // scene-colour gate owns the rod animation tick every frame regardless
        // of which registration draws.
        VeilCompatRegistry.registerWorldRenderHook(
                HOOK_ID_RAW, DRAW_BUFFERS_RAW,
                FuelRodBloomIrisHook::shouldRenderRawLayer,
                (camera, gameRenderer) -> render(camera, gameRenderer,
                        MistInjectionProfiles.Profile.RAW_LAYER));
    }

    /** Whether the active pack uses the Bliss-family translucent-layer profile. */
    private static boolean isBlissProfile() {
        return MistInjectionProfiles.activeProfile()
                == MistInjectionProfiles.Profile.TRANSLUCENT_LAYER;
    }

    /** Whether the active pack uses the Sundial-family HDR-scene profile. */
    private static boolean isHdrProfile() {
        return MistInjectionProfiles.activeProfile()
                == MistInjectionProfiles.Profile.HDR_SCENE;
    }

    /**
     * Whether the iris gbuffer path is currently taking over from the vanilla
     * Veil post pipeline. Consulted by
     * {@link FuelRodBloomHandler#syncGlowPipeline()} to maintain the "post
     * pipeline XOR iris hook" invariant.
     */
    public static boolean isActivePath() {
        if (!CreateManaIndustry.IRISVEIL_ACTIVE
                || !IrisVeilCompat.isShaderPackInUse()
                || !FuelRodBloomHandler.isGlowActive())
            return false;
        ShaderProgram shader = getProgram();
        return shader != null && shader.isValid();
    }

    /**
     * Scene-colour hook gate. Ticks the rod animations (the iris path is the
     * only tick point while a shader pack is active) and reconciles the vanilla
     * post pipeline.
     */
    private static boolean shouldRenderSceneColor() {
        FuelRodBloomHandler.tickGlow();
        FuelRodBloomHandler.syncGlowPipeline();
        boolean draw = isActivePath()
                && MistInjectionProfiles.activeProfile() == MistInjectionProfiles.Profile.SCENE_COLOR;
        if (draw)
            logFirstDraw(HOOK_ID, "replace draw into colortex0");
        return draw;
    }

    /** Bliss-family hook gate — no ticking (the scene-colour gate owns that). */
    private static boolean shouldRenderBliss() {
        boolean draw = isActivePath() && isBlissProfile();
        if (draw)
            logFirstDraw(HOOK_ID_BLISS, "translucent-layer fold into colortex2");
        return draw;
    }

    /** HDR-scene hook gate (Sundial) — no ticking (the scene-colour gate owns that). */
    private static boolean shouldRenderHdr() {
        boolean draw = isActivePath() && isHdrProfile();
        if (draw)
            logFirstDraw(HOOK_ID_HDR, "hdr-scene fold into colortex3");
        return draw;
    }

    /** Raw-layer hook gate (iterationT) — no ticking (the scene-colour gate owns that). */
    private static boolean shouldRenderRawLayer() {
        boolean draw = isActivePath()
                && MistInjectionProfiles.activeProfile() == MistInjectionProfiles.Profile.RAW_LAYER;
        if (draw)
            logFirstDraw(HOOK_ID_RAW, "raw additive layer into colortex10");
        return draw;
    }

    private static void logFirstDraw(String hookId, String detail) {
        if (DRAW_LOGGED.add(hookId)) {
            CreateManaIndustry.LOGGER.info("[CMI compat] drew iris fuel-rod glow pass '{}' ({})",
                    hookId, detail);
        }
    }

    /**
     * Draws the glow pass into the iris gbuffer for the given injection
     * profile: SCENE_COLOR re-emits the sampled scene colour plus the glow into
     * colortex0, TRANSLUCENT_LAYER folds it under colortex2 (with Bliss
     * auto-exposure compensation), HDR_SCENE folds it additively into Sundial's
     * deferred-lit colortex3 (with Sundial auto-exposure compensation).
     */
    private static boolean render(Object camera, Object gameRenderer,
            MistInjectionProfiles.Profile profile) {
        try {
            ShaderProgram shader = getProgram();
            if (shader == null)
                return false;

            boolean translucentLayer = profile == MistInjectionProfiles.Profile.TRANSLUCENT_LAYER;
            boolean hdrScene = profile == MistInjectionProfiles.Profile.HDR_SCENE;

            // Exposure-compensation plumbing per profile: locate the pack's
            // auto-exposure scalar first — the acquisition binds its own query
            // framebuffer and restores the vanilla main target, so the compat
            // gbuffer framebuffer must be re-bound through the accessor
            // afterwards. Sundial needs no binding: its exposure multiply is a
            // constant (see SundialAutoExposure), carried in ExposureParams.x.
            int exposureTextureId = -1;
            int exposureMode = 0;
            float sundialScale = 1.0F;
            if (translucentLayer) {
                exposureTextureId = MistExposureSource.acquireExposureTexture(4);
                exposureMode = 1;
            } else if (hdrScene) {
                sundialScale = SundialAutoExposure.compensationScale();
                exposureMode = 2;
            }
            if (exposureMode != 0) {
                if (Iris.getPipelineManager().getPipelineNullable()
                        instanceof IrisRenderingPipelineAccessor pipelineAccessor)
                    pipelineAccessor.irisveil$bindCompatGbufferFramebuffer(
                            translucentLayer ? DRAW_BUFFERS_BLISS : DRAW_BUFFERS_HDR);
            }

            // The bound framebuffer's attachment 0 is now colortex0 (scene-colour
            // packs), colortex2 (translucent-layer) or colortex3 (HDR-scene).
            // Querying it avoids the flipped-after-translucent ambiguity of
            // guessing main/alt.
            int sceneTextureId = GL30.glGetFramebufferAttachmentParameteri(
                    GL30.GL_FRAMEBUFFER, GL30.GL_COLOR_ATTACHMENT0,
                    GL30.GL_FRAMEBUFFER_ATTACHMENT_OBJECT_NAME);

            // Main RT depth — iris writes the full solid world depth here and
            // nothing clears it during the frame. Fetched fresh every frame
            // (the texture can be recreated on resize).
            int depthId = Minecraft.getInstance().getMainRenderTarget().getDepthTextureId();

            // Replace-style blend in both modes: the shader reads back the target
            // content and outputs the composed result itself (the translucent-layer
            // branch folds the glow under the existing layer inside the shader).
            RenderSystem.enableBlend();
            RenderSystem.blendFuncSeparate(GL11.GL_ONE, GL11.GL_ZERO, GL11.GL_ONE, GL11.GL_ZERO);

            shader.bind();
            int sceneUniform = shader.getUniformLocation("DiffuseSampler0");
            int depthUniform = shader.getUniformLocation("DiffuseDepthSampler");
            RenderSystem.activeTexture(GL13.GL_TEXTURE0);
            GL11.glBindTexture(GL11.GL_TEXTURE_2D, sceneTextureId);
            if (sceneUniform >= 0)
                GL30.glUniform1i(sceneUniform, 0);
            RenderSystem.activeTexture(GL13.GL_TEXTURE1);
            GL11.glBindTexture(GL11.GL_TEXTURE_2D, depthId);
            if (depthUniform >= 0)
                GL30.glUniform1i(depthUniform, 1);

            // Injection target: 0 = replace-style scene-colour output, 1 =
            // translucent-layer RMW (Bliss-family colortex2), 2 = raw additive
            // layer (iterationT-family colortex10).
            var outputModeUniform = shader.getUniform("OutputMode");
            if (outputModeUniform != null)
                outputModeUniform.setInt(switch (profile) {
                    case TRANSLUCENT_LAYER -> 1;
                    case RAW_LAYER -> 2;
                    default -> 0;
                });

            // Exposure-compensation state: unit 3 carries the pack's exposure
            // buffer for sampler-based modes (Bliss); the Sundial fold instead
            // receives its constant scale through ExposureParams.x.
            if (exposureMode != 0) {
                if (exposureTextureId >= 0) {
                    var exposureUniform = shader.getUniformLocation("ExposureSampler");
                    RenderSystem.activeTexture(GL13.GL_TEXTURE3);
                    GL11.glBindTexture(GL11.GL_TEXTURE_2D, exposureTextureId);
                    if (exposureUniform >= 0)
                        GL30.glUniform1i(exposureUniform, 3);
                }
                var exposureBoundUniform = shader.getUniform("ExposureBound");
                if (exposureBoundUniform != null)
                    exposureBoundUniform.setInt(exposureTextureId >= 0 ? 1 : 0);
                var exposureModeUniform = shader.getUniform("ExposureMode");
                if (exposureModeUniform != null)
                    exposureModeUniform.setInt(exposureMode);
                var exposureParamsUniform = shader.getUniform("ExposureParams");
                if (exposureParamsUniform != null)
                    exposureParamsUniform.setVector(
                            exposureMode == 2 ? sundialScale : 0.0F, 0.0F, 0.0F, 0.0F);
            }

            FuelRodBloomHandler.applyGlowUniforms(shader);

            shader.setDefaultUniforms(VertexFormat.Mode.TRIANGLE_STRIP);
            VeilRenderSystem.drawScreenQuad();

            ShaderProgram.unbind();
            RenderSystem.disableBlend();
            RenderSystem.defaultBlendFunc();
            // Restore the conventional active texture unit — the manual binds
            // left it on unit 3 at most, and render code generally assumes unit 0.
            // The texture bindings themselves are left alone: iris and the vanilla
            // pipeline rebind whatever they sample on every draw.
            RenderSystem.activeTexture(GL13.GL_TEXTURE0);
            return true;
        } catch (RuntimeException | LinkageError e) {
            CreateManaIndustry.LOGGER.warn("Iris fuel rod glow hook render failed", e);
            return false;
        }
    }

    /** Fetches the glow shader program fresh each frame (survives shader reloads). */
    private static ShaderProgram getProgram() {
        return VeilRenderSystem.renderer().getShaderManager()
                .getShader(CreateManaIndustry.modLoc("fuel_rod_glow_iris"));
    }
}
