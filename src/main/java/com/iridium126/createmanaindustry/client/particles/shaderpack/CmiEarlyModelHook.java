package com.iridium126.createmanaindustry.client.particles.shaderpack;

import com.iridium126.createmanaindustry.CreateManaIndustry;
import com.iridium126.createmanaindustry.client.particles.engine.CMIParticleEngine;
import com.iridium126.createmanaindustry.client.particles.engine.ParticleBuffers;
import com.iridium126.createmanaindustry.config.ClientConfig;

import com.mojang.blaze3d.systems.RenderSystem;
import net.irisshaders.iris.uniforms.CapturedRenderingState;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.ShaderInstance;
import org.joml.Matrix3f;
import org.joml.Matrix4f;
import top.leonx.irisveil.IrisVeilCompat;

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
 * Draws the MODEL (allay) particle segments INSIDE the gbuffer phase -- at the
 * exact point Flywheel draws its entity-stage instanced content: injected by
 * {@code LevelRendererBlockEntitiesMixin} just before the profiler section
 * switches to {@code blockentities}, i.e. after regular entities and before
 * block entities, translucent terrain and any deferred composite.
 *
 * <p>Because the draw lands before deferred lighting, the merged program's
 * packed output is consumed by the pack exactly like a native entity's:
 * Photon-family packs light us through their deferred pass -- including the
 * {@code material_mask = 40} emission their entity vertex shaders apply at
 * full block light, which our injected full-bright lightmap deliberately
 * triggers (that IS the vanilla allay's "glowing eyes" treatment) -- while
 * forward-lit packs shade us in-fragment as usual.</p>
 *
 * <p>Arbitration: a successful draw here latches
 * {@link CMIParticleEngine#markHookModelDrawn()}, so the late shader-pack hook
 * stands down and the AFTER_LEVEL frame skips its own drawModels; sprites stay
 * on the late path untouched (design freeze Q9). When compilation fails the
 * latch stays clear and the late hook's self-drawn fallback takes over.</p>
 *
 * <p>All iris-veil-compat references require the mod to be loaded -- render()
 * is only reachable under {@link CreateManaIndustry#IRISVEIL_ACTIVE} (checked
 * in the mixin before this class is first touched).</p>
 */
public final class CmiEarlyModelHook {

    private static final ShaderPackProgramCompiler COMPILER = new ShaderPackProgramCompiler();

    /** GL_TIME_ELAPSED ring for this window's GPU cost (never blocks). */
    private static final int[] TIMER_QUERIES = new int[4];
    private static int timerSlot = 0;
    private static int timerIssued = 0;
    private static boolean timerReady = false;

    /** Uniform-location cache (program id -> name -> location). */
    private static final Map<Integer, Map<String, Integer>> UNIFORMS = new HashMap<>();
    private static boolean rebuildListenerRegistered = false;

    /** Warn-once latch for {@link #applyGuarded}'s fallback path. */
    private static boolean applyFallbackWarned;

    private CmiEarlyModelHook() {}

    // ------------------------------------------------------------------
    // Entry point (mixin, render thread)
    // ------------------------------------------------------------------

    /** Called from {@code LevelRendererBlockEntitiesMixin} inside renderLevel. */
    public static void render() {
        CMIParticleEngine engine = CMIParticleEngine.INSTANCE;
        try {
            if (!ClientConfig.shaderPackIntegration
                    || !IrisVeilCompat.isShaderPackInUse()
                    || !engine.available()
                    || engine.liveCount() <= 0)
                return;

            if (!ensureCompiledWithListener())
                return; // sticky failure recorded; late self-drawn fallback engages

            beginTimer(engine);
            drawEntities(engine);
            endTimer(engine);

            // Latch even when this frame's instance count is zero: skipping the
            // L0 drawModels then costs nothing (it would submit the same empty
            // commands), while a CPU-side per-type readback would be a stall.
            engine.markHookModelDrawn();
            engine.shaderPackPathStatus = "early entities merge";
            engine.shaderPackDepthStatus = "hardware (gbuffer)";
            engine.shaderPackErrorStatus = "";
        } catch (RuntimeException | LinkageError e) {
            CreateManaIndustry.LOGGER.warn("[CMI particles] early model hook failed", e);
            engine.shaderPackErrorStatus = String.valueOf(e);
        }
    }

    private static boolean ensureCompiledWithListener() {
        if (!rebuildListenerRegistered) {
            rebuildListenerRegistered = true;
            COMPILER.setOnRebuild(UNIFORMS::clear);
        }
        return COMPILER.ensureCompiled();
    }

    /** Most recent early-path compile failure, for /cmip shaderpack status. */
    public static String lastError() {
        return COMPILER.lastError();
    }

    // ------------------------------------------------------------------
    // Draw
    // ------------------------------------------------------------------

    private static void drawEntities(CMIParticleEngine engine) {
        var gpu = engine.gpuBuffers();
        ShaderInstance shader = COMPILER.entitiesShader();

        bindSharedResources(engine, gpu);

        applyGuarded(shader);
        int progId = shader.getId();

        // Iris rewrote the pack's fixed-function matrices onto iris_* uniforms;
        // they are NOT refreshed outside Iris's own draw stream, so supply the
        // current gbuffer matrices ourselves (same protocol as flw-compat).
        Matrix4f projection = new Matrix4f(CapturedRenderingState.INSTANCE.getGbufferProjection());
        Matrix4f modelView = new Matrix4f(CapturedRenderingState.INSTANCE.getGbufferModelView());
        uploadIrisMatrices(progId, projection, modelView);
        uploadNeutralEntityUniforms(progId);
        uploadCmiUniforms(progId, engine, modelView);

        RenderSystem.enableDepthTest();
        GL11.glEnable(GL11.GL_CULL_FACE);
        // native solid entities are cutout-shaded: no blending on either model
        // segment, depth writes everywhere (early-Z feeder for later passes)
        RenderSystem.disableBlend();
        RenderSystem.depthMask(true);

        bindAtlas(engine, COMPILER.gtextureUnit());
        gpu.bindDrawIndirect();
        // BOTH model segments through one multi-draw, exactly like the L0 path:
        // cutout segment (cmd2) then ghost segment (cmd3), same program/state.
        gpu.drawModelSegments();

        shader.clear();

        RenderSystem.depthMask(true);
        RenderSystem.defaultBlendFunc();
        RenderSystem.disableBlend();
        GL30.glBindVertexArray(0);
        GL20.glUseProgram(0);

        restoreSsboBindings(gpu);
        RenderSystem.activeTexture(GL13.GL_TEXTURE0);
    }

    private static void bindSharedResources(CMIParticleEngine engine, ParticleBuffers gpu) {
        // Previous-generation snapshot on the MERGED binding slots (high numbers,
        // clear of Iris/Flywheel territory): freshest COMMITTED pool + permutation.
        gpu.bindParticleRead(ShaderPackProgramCompiler.MERGED_BINDING_POOL);
        gpu.bindEmitters(ShaderPackProgramCompiler.MERGED_BINDING_EMITTERS);
        gpu.bindSort(ShaderPackProgramCompiler.MERGED_BINDING_SORT, safeFinalPerm(engine));
        gpu.bindModelGeo();
        gpu.bindVao();
    }

    /** -1 until the first committed sorted frame; guards premature binds. */
    private static int safeFinalPerm(CMIParticleEngine engine) {
        int id = engine.lastFinalPermBufferId();
        return id >= 0 ? id : engine.gpuBuffers().sortBuffer(0);
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
            if (!applyFallbackWarned) {
                applyFallbackWarned = true;
                CreateManaIndustry.LOGGER.warn(
                        "[CMI particles] ShaderInstance.apply() failed on the merged program "
                                + "(optimized-away vanilla uniform); using bare glUseProgram from now on", e);
            }
            GL20.glUseProgram(shader.getId());
        }
    }

    /**
     * Iris supplies {@code entityId}/{@code entityColor} only during its own
     * entity stream; outside it they hold stale values from the last rendered
     * entity. Zero them so pack-side id-keyed branches (sign text, end portal,
     * hurt flash...) cannot misfire on our instances. The mask-40 glow we WANT
     * comes from the full-bright lightmap rule, not from these.
     */
    private static void uploadNeutralEntityUniforms(int progId) {
        setInt(progId, "entityId", 0);
        setInt(progId, "blockEntityId", 0);
        setInt(progId, "currentRenderedItemId", 0);
        setFloat4(progId, "entityColor", 0f, 0f, 0f, 0f);
    }

    private static void uploadIrisMatrices(int progId, Matrix4f projection, Matrix4f modelView) {
        int projLoc = GL20.glGetUniformLocation(progId, "iris_ProjMat");
        if (projLoc >= 0)
            GL20.glUniformMatrix4fv(projLoc, false, projection.get(new float[16]));
        int mvLoc = GL20.glGetUniformLocation(progId, "iris_ModelViewMat");
        if (mvLoc >= 0)
            GL20.glUniformMatrix4fv(mvLoc, false, modelView.get(new float[16]));
        int normalLoc = GL20.glGetUniformLocation(progId, "iris_NormalMat");
        if (normalLoc >= 0) {
            Matrix4f inv = new Matrix4f(modelView).invert();
            Matrix3f normalMat = new Matrix3f(inv.transpose());
            GL20.glUniformMatrix3fv(normalLoc, false, normalMat.get(new float[9]));
        }
    }

    private static void uploadCmiUniforms(int progId, CMIParticleEngine engine, Matrix4f modelView) {
        var camPos = Minecraft.getInstance().gameRenderer.getMainCamera().getPosition();
        setFloat3(progId, "cmi_CameraPos", (float) camPos.x, (float) camPos.y, (float) camPos.z);
        setFloat(progId, "cmi_FadeDist", (float) ClientConfig.particleFadeDistance);
        setMat4(progId, "cmi_ModelViewMat", modelView);
    }

    /** The pack fragment samples the entity texture through its gtexture sampler. */
    private static void bindAtlas(CMIParticleEngine engine, int unit) {
        if (unit < 0)
            return;
        RenderSystem.activeTexture(GL13.GL_TEXTURE0 + unit);
        GL11.glBindTexture(GL11.GL_TEXTURE_2D, engine.modelAtlasTextureId());
    }

    private static void restoreSsboBindings(ParticleBuffers gpu) {
        // release our high SSBO slots back to neutral so nothing downstream can
        // accidentally consume them
        for (int b = ShaderPackProgramCompiler.MERGED_BINDING_GEO; b <= ShaderPackProgramCompiler.MERGED_BINDING_SORT; b++)
            GL30.glBindBufferBase(GL43.GL_SHADER_STORAGE_BUFFER, b, 0);
    }

    // ------------------------------------------------------------------
    // Timer ring (same discipline as the late hook)
    // ------------------------------------------------------------------

    private static void beginTimer(CMIParticleEngine engine) {
        if (!timerReady) {
            GL30.glGenQueries(TIMER_QUERIES);
            timerReady = true;
        }
        GL15.glBeginQuery(GL33.GL_TIME_ELAPSED, TIMER_QUERIES[timerSlot]);
    }

    private static void endTimer(CMIParticleEngine engine) {
        GL15.glEndQuery(GL33.GL_TIME_ELAPSED);
        timerSlot = (timerSlot + 1) % TIMER_QUERIES.length;
        if (++timerIssued >= TIMER_QUERIES.length) {
            int oldest = TIMER_QUERIES[timerSlot];
            if (GL15.glGetQueryObjecti(oldest, GL15.GL_QUERY_RESULT_AVAILABLE) == GL11.GL_TRUE)
                engine.addExternalGpuMs(GL15.glGetQueryObjectui(oldest, GL15.GL_QUERY_RESULT) / 1_000_000.0);
        }
    }

    // ------------------------------------------------------------------
    // Uniform helpers
    // ------------------------------------------------------------------

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

    private static void setFloat4(int prog, String name, float x, float y, float z, float w) {
        int l = loc(prog, name);
        if (l >= 0) GL20.glUniform4f(l, x, y, z, w);
    }

    private static void setMat4(int prog, String name, Matrix4f m) {
        int l = loc(prog, name);
        if (l >= 0)
            GL20.glUniformMatrix4fv(l, false, m.get(new float[16]));
    }
}
