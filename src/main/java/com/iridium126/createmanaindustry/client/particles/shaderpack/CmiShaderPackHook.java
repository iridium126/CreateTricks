package com.iridium126.createmanaindustry.client.particles.shaderpack;

import com.iridium126.createmanaindustry.CreateManaIndustry;
import com.iridium126.createmanaindustry.client.particles.engine.CMIParticleEngine;
import com.iridium126.createmanaindustry.client.particles.engine.ParticleBuffers;
import com.iridium126.createmanaindustry.config.ClientConfig;
import com.iridium126.createmanaindustry.client.render.mist.MistInjectionProfiles;

import com.mojang.blaze3d.systems.RenderSystem;

import net.irisshaders.iris.uniforms.CapturedRenderingState;
import net.minecraft.client.Minecraft;
import top.leonx.irisveil.IrisVeilCompat;
import top.leonx.irisveil.compat.veil.VeilCompatRegistry;

import org.joml.Matrix4f;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL13;
import org.lwjgl.opengl.GL15;
import org.lwjgl.opengl.GL20;
import org.lwjgl.opengl.GL30;
import org.lwjgl.opengl.GL33;
import org.lwjgl.opengl.GL43;

/**
 * LATE fallback window for the MODEL particle segments -- after this system's
 * early entities merge ({@link CmiEarlyModelHook}, injected before
 * {@code blockentities}), registered here on iris-veil-compat's post-world-
 * border hooks so its output still rides the pack composite.
 *
 * <p>Since routing went all-early (r4), this window only engages when the
 * early merge is unavailable for the active pack (sticky compile failure,
 * missing Entities program...) or stood down mid-frame: it submits the
 * engine's own self-hosted model program -- hardware depth test against the
 * compat framebuffer when one exists, otherwise manual compares against the
 * MAIN render target's depth texture ({@code uMainDepth/uManualDepth}, same
 * technique as the mist pass).</p>
 *
 * <p>The former pack-program branch lived here until the deferred-encoder
 * finding: encoder packs' gbuffer fragments write packed data that is
 * invisible in a post-lighting colour buffer, which is why the merge moved to
 * the early window and this hook dropped back to self-drawn-only.</p>
 *
 * <p>Arbitration: when the early hook drew this frame it latches
 * {@link CMIParticleEngine#markHookModelDrawn()}; render() checks the latch
 * first and returns without drawing. Sprite buckets always stay on the
 * AFTER_LEVEL path (design Q9 freeze).</p>
 *
 * <p>All iris-veil-compat references require the mod to be loaded -- init() is
 * only called under {@link CreateManaIndustry#IRISVEIL_ACTIVE}.</p>
 */
public final class CmiShaderPackHook {

    private static final String HOOK_ID_SCENE = "createmanaindustry:model_scene";
    private static final String HOOK_ID_TRANSLUCENT = "createmanaindustry:model_translucent";
    private static final String HOOK_ID_HDR = "createmanaindustry:model_hdr";
    private static final String HOOK_ID_RAW = "createmanaindustry:model_raw";

    // Same per-family scene-colour targets the mist hooks use (see MistIrisHook).
    private static final int[] DRAW_BUFFERS_SCENE = {0};
    private static final int[] DRAW_BUFFERS_TRANSLUCENT = {2};
    private static final int[] DRAW_BUFFERS_HDR = {3};
    private static final int[] DRAW_BUFFERS_RAW = {9};

    /** Main-depth sampler unit for the self-drawn fallback's manual depth test. */
    private static final int MAIN_DEPTH_UNIT = 2;

    private static boolean registered;

    /** GL_TIME_ELAPSED ring for the hook-side GPU cost (never blocks). */
    private static final int[] TIMER_QUERIES = new int[4];
    private static int timerSlot = 0;
    private static int timerIssued = 0;
    private static boolean timerReady = false;

    private CmiShaderPackHook() {}

    // ------------------------------------------------------------------
    // Registration
    // ------------------------------------------------------------------

    public static void init() {
        if (!CreateManaIndustry.IRISVEIL_ACTIVE || registered)
            return;
        registered = true;
        // Four registrations share one render body because the compat registry
        // pins the draw buffers per hook; each gate selects by pack family.
        VeilCompatRegistry.registerWorldRenderHook(HOOK_ID_SCENE, DRAW_BUFFERS_SCENE,
                () -> shouldRender(MistInjectionProfiles.Profile.SCENE_COLOR),
                (camera, gameRenderer) -> render());
        VeilCompatRegistry.registerWorldRenderHook(HOOK_ID_TRANSLUCENT, DRAW_BUFFERS_TRANSLUCENT,
                () -> shouldRender(MistInjectionProfiles.Profile.TRANSLUCENT_LAYER),
                (camera, gameRenderer) -> render());
        VeilCompatRegistry.registerWorldRenderHook(HOOK_ID_HDR, DRAW_BUFFERS_HDR,
                () -> shouldRender(MistInjectionProfiles.Profile.HDR_SCENE),
                (camera, gameRenderer) -> render());
        VeilCompatRegistry.registerWorldRenderHook(HOOK_ID_RAW, DRAW_BUFFERS_RAW,
                () -> shouldRender(MistInjectionProfiles.Profile.RAW_LAYER),
                (camera, gameRenderer) -> render());
    }

    private static boolean shouldRender(MistInjectionProfiles.Profile profile) {
        CMIParticleEngine engine = CMIParticleEngine.INSTANCE;
        if (!ClientConfig.shaderPackIntegration
                || !CreateManaIndustry.IRISVEIL_ACTIVE
                || !IrisVeilCompat.isShaderPackInUse()
                || !engine.available()
                || engine.liveCount() <= 0)
            return false;
        // The activeProfile comparison above guarantees exactly one of the
        // four registered hooks fires per frame.
        return true;
    }

    // ------------------------------------------------------------------
    // Frame draw
    // ------------------------------------------------------------------

    /**
     * Draws the MODEL segments into the already-bound compat framebuffer via
     * the engine's self-hosted program. Returns whether anything was drawn
     * (drives the arbitration latch).
     */
    private static boolean render() {
        CMIParticleEngine engine = CMIParticleEngine.INSTANCE;
        try {
            // The early entities merge owns MODEL rendering whenever it ran --
            // including zero-instance frames, whose empty commands make our
            // fallback redundant by definition.
            if (engine.isHookModelLatchSet())
                return false;

            if (!timerReady) {
                GL30.glGenQueries(TIMER_QUERIES);
                timerReady = true;
            }
            GL15.glBeginQuery(GL33.GL_TIME_ELAPSED, TIMER_QUERIES[timerSlot]);
            boolean drew = drawSelfHosted(engine);
            GL15.glEndQuery(GL33.GL_TIME_ELAPSED);

            // Collect the oldest sample (RING-1 frames old, virtually complete).
            timerSlot = (timerSlot + 1) % TIMER_QUERIES.length;
            if (++timerIssued >= TIMER_QUERIES.length) {
                int oldest = TIMER_QUERIES[timerSlot];
                if (GL15.glGetQueryObjecti(oldest, GL15.GL_QUERY_RESULT_AVAILABLE) == GL11.GL_TRUE)
                    engine.addExternalGpuMs(GL15.glGetQueryObjectui(oldest, GL15.GL_QUERY_RESULT) / 1_000_000.0);
            }

            if (drew) {
                engine.markHookModelDrawn();
                engine.shaderPackPathStatus = "self-drawn via hook (fallback)";
                engine.shaderPackDepthStatus = queryHardwareDepth() ? "hardware" : "manual main-depth";
                if (engine.shaderPackErrorStatus.isEmpty())
                    engine.shaderPackErrorStatus = CmiEarlyModelHook.lastError();
            }
            return drew;
        } catch (RuntimeException | LinkageError e) {
            CreateManaIndustry.LOGGER.warn("[CMI particles] shader-pack hook render failed", e);
            engine.shaderPackErrorStatus = String.valueOf(e);
            return false;
        }
    }

    /**
     * True when the CURRENTLY BOUND framebuffer (the compat gbuffer the hook
     * framework selected) carries a depth attachment. Pure GL query -- no
     * reflection into iris-veil-compat internals needed.
     */
    private static boolean queryHardwareDepth() {
        try {
            int obj = GL30.glGetFramebufferAttachmentParameteri(GL30.GL_FRAMEBUFFER,
                    GL30.GL_DEPTH_ATTACHMENT, GL30.GL_FRAMEBUFFER_ATTACHMENT_OBJECT_NAME);
            return obj != 0;
        } catch (RuntimeException e) {
            return false;
        }
    }

    // ------------------------------------------------------------------
    // Self-hosted program through the hook (fallback)
    // ------------------------------------------------------------------

    private static boolean drawSelfHosted(CMIParticleEngine engine) {
        int prog = engine.modelProgramId();
        if (prog == 0)
            return false;
        var gpu = engine.gpuBuffers();
        Minecraft mc = Minecraft.getInstance();
        if (mc.getMainRenderTarget() == null)
            return false;

        // Previous-generation snapshot: freshest COMMITTED pool + permutation,
        // bound on the engine's standard slots (model.vsh reads base 1/5/7/12).
        gpu.bindParticleRead(ParticleBuffers.PARTICLE_BB_WRITE);
        gpu.bindSort(ParticleBuffers.SORTWRITE_BINDING, safeFinalPerm(engine));
        gpu.bindEmitters(ParticleBuffers.EMITTER_BB);
        gpu.bindModelGeo();
        gpu.bindVao();

        GL20.glUseProgram(prog);
        Matrix4f view = new Matrix4f(CapturedRenderingState.INSTANCE.getGbufferModelView());
        Matrix4f projection = new Matrix4f(CapturedRenderingState.INSTANCE.getGbufferProjection());
        var camPos = mc.gameRenderer.getMainCamera().getPosition();
        setMat4(prog, "ModelViewMat", view);
        setMat4(prog, "ProjMat", projection);
        setFloat3(prog, "uCamPos", (float) camPos.x, (float) camPos.y, (float) camPos.z);
        setFloat1(prog, "uFadeDist", (float) ClientConfig.particleFadeDistance);
        setInt1(prog, "uSprite", 1);

        boolean hardwareDepth = queryHardwareDepth();
        int depthTex = mc.getMainRenderTarget().getDepthTextureId();
        setInt1(prog, "uManualDepth", hardwareDepth ? 0 : 1);
        if (!hardwareDepth && depthTex > 0) {
            RenderSystem.activeTexture(GL13.GL_TEXTURE0 + MAIN_DEPTH_UNIT);
            GL11.glBindTexture(GL11.GL_TEXTURE_2D, depthTex);
            setInt1(prog, "uMainDepth", MAIN_DEPTH_UNIT);
        } else {
            setInt1(prog, "uMainDepth", 0);
        }

        GL13.glActiveTexture(GL13.GL_TEXTURE1);
        GL11.glBindTexture(GL11.GL_TEXTURE_2D, engine.modelAtlasTextureId());

        RenderSystem.enableDepthTest();
        GL11.glEnable(GL11.GL_CULL_FACE);
        // L0 semantics: both segments blend SRC_ALPHA with depth writes so ghost
        // surfaces occlude later translucent passes
        RenderSystem.enableBlend();
        RenderSystem.blendFuncSeparate(GL11.GL_SRC_ALPHA, GL11.GL_ONE_MINUS_SRC_ALPHA,
                GL11.GL_ONE, GL11.GL_ONE_MINUS_SRC_ALPHA);
        RenderSystem.depthMask(true);

        gpu.bindDrawIndirect();
        // both model segments in one multi-draw, exactly like the L0 path
        gpu.drawModelSegments();

        RenderSystem.depthMask(true);
        RenderSystem.defaultBlendFunc();
        RenderSystem.disableBlend();
        GL20.glUseProgram(0);
        GL30.glBindVertexArray(0);
        RenderSystem.activeTexture(GL13.GL_TEXTURE0);
        return true;
    }

    /** -1 until the first committed sorted frame; guards premature binds. */
    private static int safeFinalPerm(CMIParticleEngine engine) {
        int id = engine.lastFinalPermBufferId();
        return id >= 0 ? id : engine.gpuBuffers().sortBuffer(0);
    }

    // ------------------------------------------------------------------
    // Uniform helpers -- direct lookups, deliberately uncached: the engine
    // program id changes on shader rebuilds and there are only a handful of
    // queries per frame.
    // ------------------------------------------------------------------

    private static void setInt1(int prog, String name, int v) {
        int l = GL20.glGetUniformLocation(prog, name);
        if (l >= 0)
            GL20.glUniform1i(l, v);
    }

    private static void setFloat1(int prog, String name, float v) {
        int l = GL20.glGetUniformLocation(prog, name);
        if (l >= 0)
            GL20.glUniform1f(l, v);
    }

    private static void setFloat3(int prog, String name, float x, float y, float z) {
        int l = GL20.glGetUniformLocation(prog, name);
        if (l >= 0)
            GL20.glUniform3f(l, x, y, z);
    }

    private static void setMat4(int prog, String name, Matrix4f m) {
        int l = GL20.glGetUniformLocation(prog, name);
        if (l >= 0) {
            try (var stack = org.lwjgl.system.MemoryStack.stackPush()) {
                GL20.glUniformMatrix4fv(l, false, m.get(stack.mallocFloat(16)));
            }
        }
    }
}
