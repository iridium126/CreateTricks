package com.iridium126.createmanaindustry.client.particles.shaderpack;

import com.iridium126.createmanaindustry.CreateManaIndustry;
import com.iridium126.createmanaindustry.client.particles.engine.CMIParticleEngine;
import com.iridium126.createmanaindustry.client.particles.engine.ParticleBuffers;
import com.iridium126.createmanaindustry.config.ClientConfig;
import com.iridium126.createmanaindustry.client.render.mist.MistInjectionProfiles;

import com.mojang.blaze3d.systems.RenderSystem;
import net.irisshaders.iris.uniforms.CapturedRenderingState;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.ShaderInstance;
import org.joml.Matrix3f;
import org.joml.Matrix4f;
import top.leonx.irisveil.IrisVeilCompat;
import top.leonx.irisveil.compat.veil.VeilCompatRegistry;

import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL13;
import org.lwjgl.opengl.GL15;
import org.lwjgl.opengl.GL20;
import org.lwjgl.opengl.GL30;
import org.lwjgl.opengl.GL33;
import org.lwjgl.opengl.GL43;

import java.util.HashMap;
import java.util.Map;

/**
 * Draws the MODEL (allay) particle segments inside the Iris gbuffer through
 * iris-veil-compat's world render hooks -- the only composition window in this
 * project that is verified to survive a shader pack's composite chain
 * (Round-1..4 probes; the mist volumetric pass rides the same mechanism).
 *
 * <p>Two draw modes, selected per frame:</p>
 * <ul>
 *   <li><b>Pack programs</b> (design M2): {@link ShaderPackProgramCompiler}
 *       merged the allay vertex program into the pack's Block program; drawing
 *       through those ShaderInstances gives the models the pack's surface
 *       lighting, fog and tone mapping. Requires the compat framebuffer to have
 *       a depth attachment (hardware occlusion).</li>
 *   <li><b>Self-drawn fallback</b> (design M1): the engine's own model program,
 *       submitted at the hook instead of AFTER_LEVEL, so its output still rides
 *       the pack composite (tone mapping / bloom). When the target framebuffer
 *       has no depth attachment, the fragment shader compares against the MAIN
 *       render target's depth texture manually (uMainDepth/uManualDepth), the
 *       same technique as the mist pass.</li>
 * </ul>
 *
 * <p>Arbitration: a successful hook draw latches
 * {@code CMIParticleEngine.markHookModelDrawn()}, which makes the regular
 * AFTER_LEVEL frame skip its own drawModels (preventing double submission).
 * Sprite buckets always stay on the AFTER_LEVEL path (design Q9 freeze).</p>
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
    private static final ShaderPackProgramCompiler COMPILER = new ShaderPackProgramCompiler();

    /** GL_TIME_ELAPSED ring for the hook-side GPU cost (never blocks). */
    private static final int[] TIMER_QUERIES = new int[4];
    private static int timerSlot = 0;
    private static int timerIssued = 0;
    private static boolean timerReady = false;

    /** Uniform-location cache (program id -> name -> location). */
    private static final Map<Integer, Map<String, Integer>> UNIFORMS = new HashMap<>();

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
     * Draws the MODEL segments into the already-bound compat framebuffer.
     * Returns whether anything was drawn (drives the arbitration latch).
     */
    private static boolean render() {
        CMIParticleEngine engine = CMIParticleEngine.INSTANCE;
        try {
            boolean packReady = COMPILER.ensureCompiled();
            boolean hardwareDepth = queryHardwareDepth();

            // Mode selection: pack programs need hardware depth for occlusion;
            // without an attachment fall back to the self-drawn program whose
            // fragment shader samples the main depth manually (consensus #4).
            boolean usePackPath = packReady && hardwareDepth;

            if (!timerReady) {
                GL30.glGenQueries(TIMER_QUERIES);
                timerReady = true;
            }
            GL15.glBeginQuery(GL33.GL_TIME_ELAPSED, TIMER_QUERIES[timerSlot]);
            boolean drew;

            if (usePackPath)
                drew = drawWithPackPrograms(engine);
            else
                drew = drawSelfHosted(engine, packReady);

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
                engine.shaderPackPathStatus = usePackPath ? "shader-pack programs" : "self-drawn via hook";
                engine.shaderPackDepthStatus = hardwareDepth ? "hardware" : "manual main-depth";
                engine.shaderPackErrorStatus = usePackPath ? "" : COMPILER.lastError();
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
    // Mode B: merged pack programs
    // ------------------------------------------------------------------

    private static boolean drawWithPackPrograms(CMIParticleEngine engine) {
        var gpu = engine.gpuBuffers();
        ShaderInstance opaque = COMPILER.opaqueShader();
        ShaderInstance translucent = COMPILER.translucentShader();
        if (opaque == null || translucent == null)
            return false;

        Matrix4f projection = new Matrix4f(CapturedRenderingState.INSTANCE.getGbufferProjection());
        Matrix4f modelView = new Matrix4f(CapturedRenderingState.INSTANCE.getGbufferModelView());

        bindSharedResources(engine, gpu);

        RenderSystem.enableDepthTest();
        GL11.glEnable(GL11.GL_CULL_FACE);

        applyGuarded(opaque);
        uploadIrisMatrices(opaque.getId(), projection, modelView);
        uploadCmiUniforms(opaque.getId(), engine, modelView);
        // cutout segment: no blending, depth writes (early-Z feeder)
        RenderSystem.disableBlend();
        RenderSystem.depthMask(true);
        bindAtlas(engine, COMPILER.gtextureUnit());
        gpu.bindDrawIndirect();
        gpu.drawIndirect(2);
        opaque.clear();

        applyGuarded(translucent);
        uploadIrisMatrices(translucent.getId(), projection, modelView);
        uploadCmiUniforms(translucent.getId(), engine, modelView);
        // ghost segment: standard alpha blend WITH depth writes (L0 semantics)
        RenderSystem.enableBlend();
        RenderSystem.blendFuncSeparate(GL11.GL_SRC_ALPHA, GL11.GL_ONE_MINUS_SRC_ALPHA,
                GL11.GL_ONE, GL11.GL_ONE_MINUS_SRC_ALPHA);
        RenderSystem.depthMask(true);
        bindAtlas(engine, COMPILER.gtextureUnit());
        gpu.bindDrawIndirect();
        gpu.drawIndirect(3);
        translucent.clear();

        restoreState(gpu);
        return true;
    }

    // ------------------------------------------------------------------
    // Mode A: self-hosted program through the hook (M1 / fallback)
    // ------------------------------------------------------------------

    private static boolean drawSelfHosted(CMIParticleEngine engine, boolean packFailed) {
        int prog = engine.modelProgramId();
        if (prog == 0)
            return false;
        var gpu = engine.gpuBuffers();
        Minecraft mc = Minecraft.getInstance();
        if (mc.getMainRenderTarget() == null)
            return false;

        // Previous-generation snapshot: freshest COMMITTED pool + permutation.
        gpu.bindParticleRead(ParticleBuffers.PARTICLE_BB_WRITE);
        gpu.bindSort(ParticleBuffers.SORTWRITE_BINDING, safeFinalPerm(engine));
        gpu.bindEmitters(ParticleBuffers.EMITTER_BB);
        gpu.bindModelGeo();
        gpu.bindVao();

        GL20.glUseProgram(prog);
        Matrix4f view = new Matrix4f(CapturedRenderingState.INSTANCE.getGbufferModelView());
        Matrix4f projection = new Matrix4f(CapturedRenderingState.INSTANCE.getGbufferProjection());
        var camPos = Minecraft.getInstance().gameRenderer.getMainCamera().getPosition();
        setMat4(prog, "ModelViewMat", view);
        setMat4(prog, "ProjMat", projection);
        setFloat3(prog, "uCamPos", (float) camPos.x, (float) camPos.y, (float) camPos.z);
        setFloat(prog, "uFadeDist", (float) ClientConfig.particleFadeDistance);
        setInt(prog, "uSprite", 1);

        boolean hardwareDepth = queryHardwareDepth();
        int depthTex = mc.getMainRenderTarget().getDepthTextureId();
        setInt(prog, "uManualDepth", hardwareDepth ? 0 : 1);
        if (!hardwareDepth && depthTex > 0) {
            RenderSystem.activeTexture(GL13.GL_TEXTURE0 + MAIN_DEPTH_UNIT);
            GL11.glBindTexture(GL11.GL_TEXTURE_2D, depthTex);
            setInt(prog, "uMainDepth", MAIN_DEPTH_UNIT);
        } else {
            setInt(prog, "uMainDepth", 0);
        }

        GL13.glActiveTexture(GL13.GL_TEXTURE1);
        GL11.glBindTexture(GL11.GL_TEXTURE_2D, engine.modelAtlasTextureId());

        RenderSystem.enableDepthTest();
        GL11.glEnable(GL11.GL_CULL_FACE);
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
        if (packFailed)
            engine.shaderPackErrorStatus = "pack merge failed; self-drawn fallback active";
        return true;
    }

    /** -1 until the first committed sorted frame; guards premature binds. */
    private static int safeFinalPerm(CMIParticleEngine engine) {
        int id = engine.lastFinalPermBufferId();
        return id >= 0 ? id : engine.gpuBuffers().sortBuffer(0);
    }

    // ------------------------------------------------------------------
    // Shared plumbing
    // ------------------------------------------------------------------

    private static void bindSharedResources(CMIParticleEngine engine, ParticleBuffers gpu) {
        // Previous-generation snapshot on the MERGED binding slots (high numbers,
        // clear of Iris/Flywheel territory).
        gpu.bindParticleRead(ShaderPackProgramCompiler.MERGED_BINDING_POOL);
        gpu.bindEmitters(ShaderPackProgramCompiler.MERGED_BINDING_EMITTERS);
        gpu.bindSort(ShaderPackProgramCompiler.MERGED_BINDING_SORT, safeFinalPerm(engine));
        gpu.bindModelGeo();
        gpu.bindVao();
    }

    /** The pack fragment samples the entity texture through its gtexture sampler. */
    private static void bindAtlas(CMIParticleEngine engine, int unit) {
        if (unit < 0)
            return;
        RenderSystem.activeTexture(GL13.GL_TEXTURE0 + unit);
        GL11.glBindTexture(GL11.GL_TEXTURE_2D, engine.modelAtlasTextureId());
    }

    /**
     * Applies an Iris-created ShaderInstance. When the merged program optimised
     * the vanilla ModelViewMat away, apply() can throw inside its uniform
     * iteration (the reference wrapper seeded a dummy; that field is final under
     * the NeoForge mappings, so fall back to a bare glUseProgram instead -- our
     * uploads below do not depend on the vanilla uniform state).
     */
    private static void applyGuarded(ShaderInstance shader) {
        try {
            shader.apply();
        } catch (RuntimeException e) {
            GL20.glUseProgram(shader.getId());
        }
    }

    /**
     * Iris rewrote the pack's fixed-function matrices onto these iris_* uniforms;
     * they are NOT refreshed outside Iris's own draw stream, so supply the
     * current gbuffer matrices ourselves (same protocol as the reference wrapper).
     */
    private static void uploadIrisMatrices(int progId, Matrix4f projection, Matrix4f modelView) {
        int projLoc = GL20.glGetUniformLocation(progId, "iris_ProjMat");
        if (projLoc >= 0) {
            try (var stack = org.lwjgl.system.MemoryStack.stackPush()) {
                GL20.glUniformMatrix4fv(projLoc, false, projection.get(stack.mallocFloat(16)));
            }
        }
        int mvLoc = GL20.glGetUniformLocation(progId, "iris_ModelViewMat");
        if (mvLoc >= 0) {
            try (var stack = org.lwjgl.system.MemoryStack.stackPush()) {
                GL20.glUniformMatrix4fv(mvLoc, false, modelView.get(stack.mallocFloat(16)));
            }
        }
        int normalLoc = GL20.glGetUniformLocation(progId, "iris_NormalMat");
        if (normalLoc >= 0) {
            Matrix4f inv = new Matrix4f(modelView).invert();
            Matrix3f normalMat = new Matrix3f(inv.transpose());
            try (var stack = org.lwjgl.system.MemoryStack.stackPush()) {
                GL20.glUniformMatrix3fv(normalLoc, false, normalMat.get(stack.mallocFloat(9)));
            }
        }
    }

    private static void uploadCmiUniforms(int progId, CMIParticleEngine engine, Matrix4f modelView) {
        var camPos = Minecraft.getInstance().gameRenderer.getMainCamera().getPosition();
        setFloat3(progId, "cmi_CameraPos", (float) camPos.x, (float) camPos.y, (float) camPos.z);
        setFloat(progId, "cmi_FadeDist", (float) ClientConfig.particleFadeDistance);
        setMat4(progId, "cmi_ModelViewMat", modelView);
    }

    private static void restoreState(ParticleBuffers gpu) {
        RenderSystem.depthMask(true);
        RenderSystem.defaultBlendFunc();
        RenderSystem.disableBlend();
        GL30.glBindVertexArray(0);
        GL20.glUseProgram(0);
        // release our high SSBO slots back to neutral so nothing downstream can
        // accidentally consume them
        for (int b = ShaderPackProgramCompiler.MERGED_BINDING_GEO; b <= ShaderPackProgramCompiler.MERGED_BINDING_SORT; b++)
            GL30.glBindBufferBase(GL43.GL_SHADER_STORAGE_BUFFER, b, 0);
        RenderSystem.activeTexture(GL13.GL_TEXTURE0);
    }
// uniform helpers -------------------------------------------------------

    private static int loc(int prog, String name) {
        return UNIFORMS.computeIfAbsent(prog, k -> new HashMap<>())
                .computeIfAbsent(name, n -> GL20.glGetUniformLocation(prog, n));
    }

    private static void setInt(int prog, String name, int v) {
        int l = loc(prog, name);
        if (l >= 0) GL20.glUniform1i(l, v);
    }

    private static void setFloat(int prog, String name, float v) {
        int l = loc(prog, name);
        if (l >= 0) GL20.glUniform1f(l, v);
    }

    private static void setFloat3(int prog, String name, float x, float y, float z) {
        int l = loc(prog, name);
        if (l >= 0) GL20.glUniform3f(l, x, y, z);
    }

    private static void setMat4(int prog, String name, Matrix4f m) {
        int l = loc(prog, name);
        if (l >= 0) {
            try (var stack = org.lwjgl.system.MemoryStack.stackPush()) {
                GL20.glUniformMatrix4fv(l, false, m.get(stack.mallocFloat(16)));
            }
        }
    }
}