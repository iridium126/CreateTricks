package com.iridium126.createmanaindustry.client.particles.shaderpack;

import com.iridium126.createmanaindustry.CreateManaIndustry;
import com.iridium126.createmanaindustry.client.particles.engine.CMIParticleEngine;
import com.iridium126.createmanaindustry.client.particles.engine.ParticleBuffers;
import com.iridium126.createmanaindustry.config.ClientConfig;

import com.mojang.blaze3d.systems.RenderSystem;
import net.irisshaders.iris.shadows.ShadowRenderer;
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

import java.util.HashMap;
import java.util.Map;

/**
 * Draws the MODEL (allay) particle segments INSIDE the gbuffer phase -- at the
 * exact point Flywheel draws its entity-stage instanced content: injected by
 * {@code LevelRendererBlockEntitiesMixin} just before the profiler section
 * switches to {@code blockentities}, i.e. after regular entities and before
 * block entities, translucent terrain and any deferred composite.
 *
 * <p>The MODEL segments are drawn through a program MERGED into the active
 * shader pack's {@code gbuffers_entities} pipeline (see
 * {@link ShaderPackProgramCompiler}), so the pack consumes the merged output
 * exactly like a native entity's: Photon-family packs light the particles
 * through their deferred pass -- including the {@code material_mask = 40}
 * emission their entity vertex shaders apply at full block light, which our
 * injected full-bright lightmap deliberately triggers (that IS the vanilla
 * allay's "glowing eyes" treatment) -- while forward-lit packs shade us
 * in-fragment as usual.
 *
 * <p>Arbitration: a successful draw here latches
 * {@link CMIParticleEngine#markHookModelDrawn()}, so the engine's AFTER_LEVEL
 * frame skips its own drawModels. When compilation fails the latch stays clear
 * and runFrame draws the MODEL segments itself on that plain path --
 * byte-for-byte the treatment every other particle type gets (sprites are
 * permanently on it per design freeze Q9).
 *
 * <p>All iris-veil-compat references require the mod to be loaded -- render()
 * is only reachable under {@link CreateManaIndustry#IRISVEIL_ACTIVE} (checked
 * in the mixin before this class is first touched).
 */
public final class CMIPackEntityMergeHook {

    private static final ShaderPackProgramCompiler COMPILER = new ShaderPackProgramCompiler();

    /** GL_TIME_ELAPSED ring for this window's GPU cost (never blocks). */
    private static final int[] TIMER_QUERIES = new int[4];
    private static int timerSlot = 0;
    private static int timerIssued = 0;
    private static boolean timerReady = false;

    /** Uniform-location cache (program id -> name -> location). */
    private static final Map<Integer, Map<String, Integer>> UNIFORMS = new HashMap<>();
    private static boolean rebuildListenerRegistered = false;

    private CMIPackEntityMergeHook() {}

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
                return; // sticky failure recorded; the engine's plain AFTER_LEVEL path takes over

            beginTimer(engine);
            drawEntities(engine);
            endTimer(engine);

            // Latch even when this frame's instance count is zero: skipping the
            // L0 drawModels then costs nothing (it would submit the same empty
            // commands), while a CPU-side per-type readback would be a stall.
            engine.markHookModelDrawn();
            engine.shaderPackPathStatus = "pack entity merge (cutout+ghost dual, model near-first, current-frame perm)";
            engine.shaderPackDepthStatus = "hardware (gbuffer)";
            engine.shaderPackErrorStatus = "";
            engine.shaderPackPermStatus = "gbuffer permutation age " + engine.lastFinalPermAgeFrames()
                    + "f (0 = this frame's cull)";
        } catch (RuntimeException | LinkageError e) {
            CreateManaIndustry.LOGGER.warn("[CMI particles] pack entity merge draw failed", e);
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

    // ------------------------------------------------------------------
    // Shadow track (S-track)
    // ------------------------------------------------------------------

    /**
     * Draws the MODEL segments into the active shadow map -- called from
     * {@code MixinIrisShadowRenderer} inside {@code renderShadows} at the same
     * spot iris-flw-compat uses for Flywheel content, right before Iris batches
     * its own buffered entity geometry ("draw entities"). Only the MODEL type
     * casts shadows; sprites stay shadow-free exactly like vanilla particles.
     *
     * <p>Fully contained: any failure here only means no particle shadows this
     * frame -- the gbuffer path and the plain fallback are untouched.
     */
    public static void renderShadow() {
        CMIParticleEngine engine = CMIParticleEngine.INSTANCE;
        try {
            if (!ClientConfig.shaderPackIntegration
                    || !IrisVeilCompat.isShaderPackInUse()
                    || !engine.available()
                    || engine.liveCount() <= 0)
                return;

            if (!ensureCompiledWithListener()) {
                engine.shaderPackShadowStatus = "merge failed";
                return;
            }
            if (!COMPILER.shadowReady()) {
                engine.shaderPackShadowStatus = "no shadow track";
                return;
            }

            drawShadow(engine);
            engine.shaderPackShadowStatus = "active";
            // The shadow track deliberately consumes the previous generation:
            // Iris renders shadows before renderSky, ahead of every level-stage
            // event (including the AFTER_SKY compute commit). Age >= 1 here is
            // the documented exception, surfaced so regressions are observable.
            engine.shaderPackPermStatus = "shadow permutation age " + engine.lastFinalPermAgeFrames()
                    + "f (>=1 expected: shadows precede AFTER_SKY compute)";
        } catch (RuntimeException | LinkageError e) {
            CreateManaIndustry.LOGGER.warn("[CMI particles] shadow-track draw failed", e);
            engine.shaderPackShadowStatus = "draw failed";
        }
    }

    /**
     * Both segments go through the ONE merged shadow program (no ghost blend
     * pinning here -- a shadow map is depth-led): skipping the ghost geometry
     * would make cloaks/wings stop casting shadows.
     */
    private static void drawShadow(CMIParticleEngine engine) {
        var gpu = engine.gpuBuffers();
        boolean cullWasEnabled = GL11.glIsEnabled(GL11.GL_CULL_FACE);

        bindSharedResources(engine, gpu);
        gpu.bindDrawIndirect();

        ShaderInstance shader = COMPILER.shadowShader();
        shader.apply();
        uploadShadowUniforms(shader.getId(), engine);
        // Depth-led pass: draw order is irrelevant here (every visible instance
        // contributes its depth exactly once either way), so keep forward reads.
        uploadSegmentMode(shader.getId(), engine, false);
        RenderSystem.enableDepthTest();
        GL11.glEnable(GL11.GL_CULL_FACE);
        RenderSystem.disableBlend();
        RenderSystem.depthMask(true);
        bindAtlas(engine, COMPILER.shadowGtextureUnit());
        gpu.drawModelCutout();
        gpu.drawModelGhost();
        shader.clear();

        RenderSystem.depthMask(true);
        GL30.glBindVertexArray(0);
        GL20.glUseProgram(0);
        gpu.unbindMergedTbos(ShaderPackProgramCompiler.MERGED_SAMPLER_UNIT_BASE);
        RenderSystem.activeTexture(GL13.GL_TEXTURE0);

        // ShadowRenderer deliberately renders its terrain with cull disabled
        // and keeps that state for later sections; restore what we found.
        if (!cullWasEnabled)
            GL11.glDisable(GL11.GL_CULL_FACE);
    }

    /**
     * Shadow-pass uniform refresh: same protocol as the gbuffer segments except
     * the matrices come from Iris's shadow camera. The vertex INPUT SPACE is the
     * same camera-relative level space as the gbuffer pass: ShadowMatrices.
     * createModelViewMatrix bakes NO camera translation into MODELVIEW -- only
     * the sub-grid snap remainder -- while native geometry arrives relative to
     * the UNSHIFTED camera position (entities via pose translation, terrain via
     * per-section offsets). So cmi_CameraPos here feeds exactly what the gbuffer
     * path feeds, and our vertex main needs no shadow variant at all.
     */
    private static void uploadShadowUniforms(int progId, CMIParticleEngine engine) {
        Matrix4f projection = new Matrix4f(ShadowRenderer.PROJECTION);
        Matrix4f modelView = new Matrix4f(ShadowRenderer.MODELVIEW);
        uploadIrisMatrices(progId, projection, modelView);
        uploadNeutralEntityUniforms(progId);
        var camPos = Minecraft.getInstance().gameRenderer.getMainCamera().getPosition();
        setFloat3(progId, "cmi_CameraPos", (float) camPos.x, (float) camPos.y, (float) camPos.z);
        setFloat(progId, "cmi_FadeDist", (float) ClientConfig.particleFadeDistance);
        // Round-trip insurance for packs whose merged program is the plain
        // terrain-fallback shadow variant (no shadow_entities source): its
        // vertex stage routes positions through the NAMED matrices
        // shadowModelView/shadowModelViewInverse/cameraPosition. Push them to
        // agree with OUR uploads so the round-trip stays exact regardless of
        // how Iris's uniform updaters behave during an external apply(). All
        // three are unused by the entities variant -- locations read -1.
        setMat(progId, "shadowModelView", modelView);
        setMat(progId, "shadowModelViewInverse", new Matrix4f(modelView).invert());
        setFloat3(progId, "cameraPosition", (float) camPos.x, (float) camPos.y, (float) camPos.z);
        setSamplers(progId);
        uploadStormItemUniforms(progId, engine);
    }

    // ------------------------------------------------------------------
    // Draw
    // ------------------------------------------------------------------

    private static void drawEntities(CMIParticleEngine engine) {
        var gpu = engine.gpuBuffers();

        bindSharedResources(engine, gpu);
        gpu.bindDrawIndirect();

        // Cutout segment: native solid-entity treatment -- no blending, depth
        // writes everywhere (early-Z feeder for later passes). The program
        // inherits the pack's entity directives wholesale.
        ShaderInstance shader = COMPILER.entitiesShader();
        shader.apply();
        int progId = shader.getId();
        uploadSegmentUniforms(progId, engine);
        // Nearest-first cutout: early-Z rejects occluded fragments before the
        // pack's fragment shader runs -- the dense-swarm fragment-pressure fix.
        uploadSegmentMode(progId, engine, true);
        RenderSystem.enableDepthTest();
        GL11.glEnable(GL11.GL_CULL_FACE);
        RenderSystem.disableBlend();
        RenderSystem.depthMask(true);
        bindAtlas(engine, COMPILER.gtextureUnit());
        gpu.drawModelCutout();
        shader.clear();

        // Ghost segment: same geometry stream and inherited alpha test, but the
        // pinned translucent blend applies (ExtendedShader.apply() overrides GL
        // blend state itself; we mirror it through RenderSystem so the state
        // tracker stays consistent). Depth writes stay ON -- the documented L0
        // tradeoff lets ghost surfaces occlude later translucent passes.
        ShaderInstance ghost = COMPILER.ghostShader();
        ghost.apply();
        progId = ghost.getId();
        uploadSegmentUniforms(progId, engine);
        // Ghost keeps forward reads: back-to-front order is load-bearing for
        // correct translucent compositing of overlapping shells.
        uploadSegmentMode(progId, engine, false);
        RenderSystem.enableDepthTest();
        GL11.glEnable(GL11.GL_CULL_FACE);
        RenderSystem.enableBlend();
        RenderSystem.blendFuncSeparate(GL11.GL_SRC_ALPHA, GL11.GL_ONE_MINUS_SRC_ALPHA,
                GL11.GL_ONE, GL11.GL_ONE_MINUS_SRC_ALPHA);
        RenderSystem.depthMask(true);
        bindAtlas(engine, COMPILER.ghostGtextureUnit());
        gpu.drawModelGhost();
        ghost.clear();

        RenderSystem.depthMask(true);
        RenderSystem.defaultBlendFunc();
        RenderSystem.disableBlend();
        GL30.glBindVertexArray(0);
        GL20.glUseProgram(0);

        gpu.unbindMergedTbos(ShaderPackProgramCompiler.MERGED_SAMPLER_UNIT_BASE);
        RenderSystem.activeTexture(GL13.GL_TEXTURE0);
    }

    /**
     * Per-program uniform refresh for one merged segment: the iris_* matrices
     * are NOT refreshed outside Iris's own draw stream, so supply the current
     * gbuffer matrices ourselves (same protocol as flw-compat), then neutralise
     * the stale entity uniforms and push our data uniforms.
     */
    private static void uploadSegmentUniforms(int progId, CMIParticleEngine engine) {
        Matrix4f projection = new Matrix4f(CapturedRenderingState.INSTANCE.getGbufferProjection());
        Matrix4f modelView = new Matrix4f(CapturedRenderingState.INSTANCE.getGbufferModelView());
        uploadIrisMatrices(progId, projection, modelView);
        uploadNeutralEntityUniforms(progId);
        uploadCmiUniforms(progId, engine);
        setSamplers(progId);
    }

    /** Points the merged program's samplerBuffer uniforms at our TBO units. */
    private static void setSamplers(int progId) {
        int base = ShaderPackProgramCompiler.MERGED_SAMPLER_UNIT_BASE;
        setInt(progId, "cmi_Geo", base);
        setInt(progId, "cmi_Pool", base + 1);
        setInt(progId, "cmi_Emitters", base + 2);
        setInt(progId, "cmi_Sorted", base + 3);
    }

    private static void setMat(int progId, String name, Matrix4f m) {
        int l = loc(progId, name);
        if (l >= 0)
            GL20.glUniformMatrix4fv(l, false, m.get(new float[16]));
    }

    private static void bindSharedResources(CMIParticleEngine engine, ParticleBuffers gpu) {
        // Merged programs read particle data through TBO views pinned to fixed
        // texture units; plain sampler declarations survive every in-game pass
        // where interface blocks demonstrably do not.
        int permId = engine.lastFinalPermBufferId();
        gpu.bindMergedTbos(ShaderPackProgramCompiler.MERGED_SAMPLER_UNIT_BASE,
                gpu.particleReadBufferId(),
                permId >= 0 ? permId : gpu.sortBuffer(0));
        gpu.bindVao();
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

    private static void uploadCmiUniforms(int progId, CMIParticleEngine engine) {
        var camPos = Minecraft.getInstance().gameRenderer.getMainCamera().getPosition();
        setFloat3(progId, "cmi_CameraPos", (float) camPos.x, (float) camPos.y, (float) camPos.z);
        setFloat(progId, "cmi_FadeDist", (float) ClientConfig.particleFadeDistance);
        setFloat(progId, "uTimeSec", engine.timeSec());
        uploadStormItemUniforms(progId, engine);
    }

    /**
     * Held-item uniforms (mirror of the engine's uploadStormItemUniforms): the
     * dive-wave staging feeding the carrier predicate and sword tier, plus the
     * per-tier atlas UV rects. Same names and staging the self-drawn model.vsh
     * consumes; cheap on stormless frames.
     */
    private static void uploadStormItemUniforms(int progId, CMIParticleEngine engine) {
        setVec4Array(progId, "uWave", engine.stormWaveUniform());
        setVec4Array(progId, "uWaveTarget", engine.stormWaveTargetUniform());
        setFloatArray(progId, "uWaveTier", engine.stormWaveTierUniform());
        setVec4Array(progId, "uHeldItemUV",
                com.iridium126.createmanaindustry.client.particles.engine.ParticlePrograms.heldItemUVTable());
    }

    /**
     * Segment-selection uniforms shared by every merged program variant. The
     * metadata slot index is the particle CAPACITY (the reserved tail cell of
     * each sort buffer where capture.comp published this generation's N_model),
     * read from the live buffer sizing rather than config so the two can never
     * drift; {@code nearestFirst} flips only the cutout segment's iteration
     * direction (see the reversed-read comment in the merged vertex source).
     */
    private static void uploadSegmentMode(int progId, CMIParticleEngine engine, boolean nearestFirst) {
        setInt(progId, "cmi_MetaSlot", engine.gpuBuffers().capacity());
        setInt(progId, "cmi_ReverseInstance", nearestFirst ? 1 : 0);
    }

    /** The pack fragment samples the entity texture through its gtexture sampler. */
    private static void bindAtlas(CMIParticleEngine engine, int unit) {
        if (unit < 0)
            return;
        RenderSystem.activeTexture(GL13.GL_TEXTURE0 + unit);
        GL11.glBindTexture(GL11.GL_TEXTURE_2D, engine.modelAtlasTextureId());
    }

    // ------------------------------------------------------------------
    // Timer ring (never blocks)
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

    private static void setVec4Array(int prog, String name, float[] values) {
        int l = loc(prog, name);
        if (l >= 0 && values != null && values.length >= 4)
            GL20.glUniform4fv(l, values);
    }

    private static void setFloatArray(int prog, String name, float[] values) {
        int l = loc(prog, name);
        if (l >= 0 && values != null && values.length > 0)
            GL20.glUniform1fv(l, values);
    }
}
