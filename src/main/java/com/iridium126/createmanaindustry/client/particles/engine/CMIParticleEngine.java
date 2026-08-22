package com.iridium126.createmanaindustry.client.particles.engine;

import java.nio.FloatBuffer;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentLinkedQueue;

import com.iridium126.createmanaindustry.CreateManaIndustry;
import com.iridium126.createmanaindustry.client.particles.emitter.EmitterSpec;
import com.iridium126.createmanaindustry.config.ClientConfig;

import com.mojang.blaze3d.systems.RenderSystem;
import net.minecraft.client.Camera;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.Minecraft;
import net.minecraft.world.phys.Vec3;
import org.joml.Matrix4fc;
import org.joml.Vector3f;
import org.lwjgl.BufferUtils;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL12;
import org.lwjgl.opengl.GL13;
import org.lwjgl.opengl.GL20;
import org.lwjgl.opengl.GL30;
import org.lwjgl.opengl.GL42;
import org.lwjgl.opengl.GL43;
import org.lwjgl.system.MemoryStack;

/**
 * Client-side GPU particle engine (singleton), self-hosted GL.
 * <p>
 * Pure-GPU pipeline: a compute pass integrates/compacts the live particle pool
 * between two double-buffered SSBOs and appends newly requested particles; a
 * packless VAO draws instanced billboards through {@code glDrawArraysIndirect}.
 * <p>
 * Two draw paths:
 * <ul>
 *   <li><b>Fast</b> (no ALPHA emitters ever used): matches the original engine —
 *       particles drawn by {@code gl_InstanceID} straight from the write pool.</li>
 *   <li><b>Sorted</b> (any ALPHA emitter): after update/emit, {@code keygen}
 *       writes (key,index) pairs into the sort buffer with a material-major,
 *       view-depth key; an LSD radix sort orders the permutation; the pool is
 *       then drawn in two ranges (additive first, alpha back-to-front) through
 *       the permutation. ALPHA particles sample a sprite atlas; colliding
 *       emitters also resolve against a 3D occupancy bake in the update pass.</li>
 * </ul>
 * All GL programs are created from the mod's bundled GLSL with raw LWJGL
 * ({@link ParticlePrograms}) — no dependency on Veil's shader manager.
 */
public final class CMIParticleEngine {

    public static final CMIParticleEngine INSTANCE = new CMIParticleEngine();

    private static final int MAX_EMITTERS = 128;
    private static final int SAFETY_MARGIN = 2048;
    private static final float DEFAULT_FADE_DIST = 40.0f;
    private static final float SORT_MAX_DEPTH = 2048.0f;
    private static final int RADIX_SHIFTS[] = { 0, 8, 16 }; // 24-bit depth key

    /** One-shot burst request posted from a client thread. */
    private static final class Burst {
        final EmitterSpec spec;
        final Vec3 origin;
        final int count;
        final boolean unthrottled;

        Burst(EmitterSpec spec, Vec3 origin, int count, boolean unthrottled) {
            this.spec = spec;
            this.origin = origin;
            this.count = count;
            this.unthrottled = unthrottled;
        }
    }

    private record StreamReq(EmitterSpec spec, Vec3 origin, double rate, double duration) {
    }

    /** Active stream consumed each frame on the render thread. */
    private static final class Stream {
        final EmitterSpec spec;
        final Vec3 origin;
        final double rate;
        final double duration; // <= 0 = infinite
        double elapsed;
        double accumulator;

        Stream(EmitterSpec spec, Vec3 origin, double rate, double duration) {
            this.spec = spec;
            this.origin = origin;
            this.rate = rate;
            this.duration = duration;
        }
    }

    private final ConcurrentLinkedQueue<Object> pending = new ConcurrentLinkedQueue<>();
    private final Map<EmitterSpec, Integer> emitterIds = new HashMap<>();
    private final Map<Integer, Integer> bakedSliceByEmitter = new HashMap<>();
    private final List<Stream> streams = new ArrayList<>();
    private final ParticleBuffers gpu = new ParticleBuffers();
    private final ParticlePrograms programs = new ParticlePrograms();
    private final ParticleFrameProfiler profiler = new ParticleFrameProfiler();
    private final CollisionBake collisionBake = new CollisionBake();
    private final ParticleAtlas cherryAtlas = ParticleAtlas.CHERRY;

    private final FloatBuffer emitFront = BufferUtils.createFloatBuffer(ParticleBuffers.MAX_EMIT_COMMANDS * 8);
    private final Vec3[] emitOrigins = new Vec3[ParticleBuffers.MAX_EMIT_COMMANDS];
    private final int[] emitIds = new int[ParticleBuffers.MAX_EMIT_COMMANDS];
    private final int[] emitCounts = new int[ParticleBuffers.MAX_EMIT_COMMANDS];

    private boolean initialized = false;
    private boolean disabled = false;
    /** ALPHA spawns requested during THIS frame (drives the dual-mode decision). */
    private boolean alphaSpawnedThisFrame = false;
    /** Last frame's ALPHA particle count (read back lagged from the counter ring). */
    private int prevAlpha = 0;
    private int aliveRead = 0;
    private volatile int liveDisplay = 0;
    private volatile float scale = 1f;
    private volatile int streamCount = 0;
    private int simFrame = 0;
    private int frameSeed = 0;
    private long lastErrorTime = 0;

    private CMIParticleEngine() {
    }

    // ------------------------------------------------------------------
    // Public API (any client thread; GPU work deferred to the render thread)
    // ------------------------------------------------------------------

    /** Fires {@code count} particles at {@code origin} (throttle applied). */
    public void spawn(EmitterSpec spec, Vec3 origin, int count) {
        if (count <= 0)
            return;
        this.pending.add(new Burst(spec, origin, count, false));
    }

    /** Fires {@code count} particles ignoring the adaptive throttle (benchmark). */
    public void spawnUnthrottled(EmitterSpec spec, Vec3 origin, int count) {
        if (count <= 0)
            return;
        this.pending.add(new Burst(spec, origin, count, true));
    }

    /** Streams {@code rate} particles/second for {@code seconds} (<= 0 = until cleared). */
    public void stream(EmitterSpec spec, Vec3 origin, double rate, double seconds) {
        this.pending.add(new StreamReq(spec, origin, rate, seconds));
    }

    /** Drops all live particles and clears streams. */
    public void clear() {
        this.pending.add(Boolean.TRUE);
        this.profiler.reset();
    }

    public void setBudget(float ms) {
        this.profiler.setBudget(ms);
    }

    /** Rough current live count (last frame's read-back). */
    public int liveCount() {
        return this.liveDisplay;
    }

    public int capacity() {
        return this.gpu.capacity();
    }

    public boolean available() {
        return this.initialized && !this.disabled && this.programs.ready();
    }

    /** Called on resource reload so shaders recompile next frame. */
    public void requestProgramRebuild() {
        this.programs.requestRebuild();
    }

    /** Frees all GPU resources on client shutdown. Safe when never initialised. */
    public void close() {
        try {
            this.gpu.free();
        } catch (RuntimeException | LinkageError e) {
            CreateManaIndustry.LOGGER.warn("[CMI particles] GPU free failed", e);
        }
        this.programs.delete();
        try {
            this.cherryAtlas.free();
            this.collisionBake.free();
        } catch (RuntimeException | LinkageError e) {
            CreateManaIndustry.LOGGER.warn("[CMI particles] atlas/bake free failed", e);
        }
        this.pending.clear();
        this.streams.clear();
        this.bakedSliceByEmitter.clear();
        this.initialized = false;
        this.disabled = false;
        this.alphaSpawnedThisFrame = false;
        this.prevAlpha = 0;
    }

    public float emissionScale() {
        return this.scale;
    }

    public double emaMs() {
        return this.profiler.emaMs();
    }

    public float budgetMs() {
        return this.profiler.budget();
    }

    public int streamCount() {
        return this.streamCount;
    }

    // ------------------------------------------------------------------
    // Frame hook — RenderLevelStageEvent.AFTER_LEVEL (native NeoForge)
    // ------------------------------------------------------------------

    public void renderFrame(Camera camera, Matrix4fc view, Matrix4fc projectionMatrix, DeltaTracker deltaTracker) {
        if (this.disabled)
            return;
        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null)
            return;

        if (!this.initialized) {
            try {
                this.initialized = this.gpu.init(ClientConfig.particleMaxCount, MAX_EMITTERS);
            } catch (RuntimeException | LinkageError e) {
                this.initialized = false;
                CreateManaIndustry.LOGGER.error("[CMI particles] GPU init failed", e);
            }
            if (!this.initialized) {
                this.disabled = true;
                CreateManaIndustry.LOGGER.error(
                        "[CMI particles] GPU particle engine failed to initialise; disabled for this session.");
                return;
            }
        }
        if (this.programs.needsRebuild())
            this.programs.rebuild();
        if (!this.programs.ready())
            return; // shaders not compiled yet (or compile failed) — retry on reload
        this.profiler.setBudget((float) ClientConfig.particleBudgetMs);

        long t0 = System.nanoTime();
        try {
            runFrame(camera, view, projectionMatrix, deltaTracker);
        } catch (RuntimeException | LinkageError e) {
            long now = System.currentTimeMillis();
            if (now - this.lastErrorTime > 5000) {
                this.lastErrorTime = now;
                CreateManaIndustry.LOGGER.error("[CMI particles] frame failed", e);
            }
        }
        this.profiler.record((System.nanoTime() - t0) / 1_000_000.0, ClientConfig.particleAutoThrottle);
        this.scale = this.profiler.emissionScale();
    }

    private void runFrame(Camera camera, Matrix4fc view, Matrix4fc projectionMatrix, DeltaTracker deltaTracker) {
        this.frameSeed++;
        this.alphaSpawnedThisFrame = false;

        int slot = this.simFrame % ParticleBuffers.COUNTER_RING;
        int readSlot = (this.simFrame - 1 + ParticleBuffers.COUNTER_RING) % ParticleBuffers.COUNTER_RING;
        this.simFrame++;

        // 0. Refresh stale collision bakes + make CPU-side uploads visible.
        this.collisionBake.tick();
        if (this.collisionBake.metaDirty()) {
            this.gpu.uploadBakeMeta(this.collisionBake.meta());
            this.collisionBake.markMetaClean();
        }
        if (this.collisionBake.ready()) {
            // host texture writes must be visible to the compute pass
            GL42.glMemoryBarrier(GL42.GL_TEXTURE_FETCH_BARRIER_BIT);
        }

        // 1. Drain requests from client thread.
        List<Burst> bursts = new ArrayList<>();
        boolean doClear = false;
        Object item;
        while ((item = this.pending.poll()) != null) {
            if (item instanceof Burst b) {
                bursts.add(b);
            } else if (item instanceof StreamReq sr) {
                this.streams.add(new Stream(sr.spec(), sr.origin(), sr.rate(), sr.duration()));
            } else if (item instanceof Boolean) {
                doClear = true;
            }
        }
        if (doClear) {
            this.streams.clear();
            this.gpu.clearParticles();
            this.aliveRead = 0;
            this.prevAlpha = 0;
            this.liveDisplay = 0;
        }

        int cap = this.gpu.capacity();
        if (!doClear) {
            // {writeSlot, spare} = {last frame's live count, last frame's alpha count}
            int[] counts = this.gpu.readbackCounts(readSlot);
            this.aliveRead = counts[0];
            this.prevAlpha = counts[1];
            if (this.aliveRead < 0 || this.aliveRead > cap)
                this.aliveRead = 0;
            if (this.prevAlpha < 0 || this.prevAlpha > cap)
                this.prevAlpha = 0;
            this.liveDisplay = this.aliveRead;
        }

        float dt = clampDelta(deltaTracker);

        // 2. Build emit entries (bursts first, then streams).
        int entryCount = 0;
        int totalSpawn = 0;
        for (Burst b : bursts) {
            int id = ensureEmitter(b.spec);
            if (id < 0)
                continue;
            ensureEmitterRuntime(id, b.spec, b.origin);
            int n = b.unthrottled ? b.count : Math.max(1, Math.round(b.count * this.scale));
            if (n <= 0 || entryCount >= ParticleBuffers.MAX_EMIT_COMMANDS)
                continue;
            this.emitIds[entryCount] = id;
            this.emitCounts[entryCount] = n;
            this.emitOrigins[entryCount] = b.origin;
            totalSpawn += n;
            entryCount++;
        }
        Iterator<Stream> it = this.streams.iterator();
        while (it.hasNext()) {
            Stream s = it.next();
            s.elapsed += dt;
            if (s.duration > 0 && s.elapsed >= s.duration) {
                it.remove();
                continue;
            }
            double want = s.rate * this.scale * dt + s.accumulator;
            int n = (int) want;
            s.accumulator = want - n;
            if (n <= 0)
                continue;
            int id = ensureEmitter(s.spec);
            if (id < 0 || entryCount >= ParticleBuffers.MAX_EMIT_COMMANDS)
                continue;
            ensureEmitterRuntime(id, s.spec, s.origin);
            this.emitIds[entryCount] = id;
            this.emitCounts[entryCount] = n;
            this.emitOrigins[entryCount] = s.origin;
            totalSpawn += n;
            entryCount++;
        }

        this.streamCount = this.streams.size();

        // 3. Cap spawn request to the free pool (extra safety margin).
        int free = Math.max(0, cap - this.aliveRead - SAFETY_MARGIN);
        if (totalSpawn > free) {
            double k = free <= 0 ? 0 : (double) free / totalSpawn;
            totalSpawn = 0;
            int w = 0;
            for (int i = 0; i < entryCount; i++) {
                int n = Math.max(0, (int) (this.emitCounts[i] * k));
                if (n > 0) {
                    this.emitIds[w] = this.emitIds[i];
                    this.emitCounts[w] = n;
                    this.emitOrigins[w] = this.emitOrigins[i];
                    totalSpawn += n;
                    w++;
                }
            }
            entryCount = w;
        }

        // 4. Upload emit commands into the next ring slot + emitters.
        int ringId = this.gpu.nextEmitBuffer();
        this.gpu.uploadDirtyEmitters();
        if (entryCount > 0) {
            this.emitFront.clear();
            for (int i = 0; i < entryCount; i++) {
                Vec3 o = this.emitOrigins[i];
                float seed = (((long) this.frameSeed * 2654435761L + i * 73856093L) >>> 8) % 1_000_000 * 0.000001f;
                this.emitFront.put((float) o.x).put((float) o.y).put((float) o.z).put(this.emitCounts[i]);
                this.emitFront.put(this.emitIds[i]).put(seed).put(0f).put(0f);
            }
            this.emitFront.flip();
            this.gpu.uploadEmits(ringId, this.emitFront);
        }

        // Dual mode: sorted only when alpha particles exist (lagged) or were just
        // requested; otherwise the pool holds only additive and we draw by identity.
        boolean sorted = (this.prevAlpha > 0) || this.alphaSpawnedThisFrame;
        int sortUInt = sorted ? 1 : 0;

        // 5. Compute passes: reset -> update -> emit.
        GL20.glUseProgram(this.programs.reset());
        this.gpu.bindIndirect(2);
        this.gpu.bindCounter(3, slot);
        GL43.glDispatchCompute(1, 1, 1);
        GL42.glMemoryBarrier(GL43.GL_SHADER_STORAGE_BARRIER_BIT | GL42.GL_ATOMIC_COUNTER_BARRIER_BIT);

        GL20.glUseProgram(this.programs.update());
        this.gpu.bindParticleRead(0);
        this.gpu.bindParticleWrite(1);
        this.gpu.bindIndirect(2);
        this.gpu.bindCounter(3, slot);
        this.gpu.bindEmitters(5);
        if (this.collisionBake.ready()) {
            this.collisionBake.bind(0);
            this.gpu.bindBakeMeta();
            setIntUniform(this.programs.update(), "uCollision", 0);
            setIntUniform(this.programs.update(), "uCollisionOn", 1);
        } else {
            setIntUniform(this.programs.update(), "uCollisionOn", 0);
        }
        setUIntUniform(this.programs.update(), "uAliveRead", this.aliveRead);
        setUIntUniform(this.programs.update(), "uCapacity", cap);
        setUIntUniform(this.programs.update(), "uSort", sortUInt);
        setFloatUniform(this.programs.update(), "uDt", dt);
        GL43.glDispatchCompute(Math.max(1, (this.aliveRead + 63) / 64), 1, 1);
        GL42.glMemoryBarrier(GL43.GL_SHADER_STORAGE_BARRIER_BIT | GL42.GL_ATOMIC_COUNTER_BARRIER_BIT);

        if (entryCount > 0) {
            GL20.glUseProgram(this.programs.emit());
            this.gpu.bindParticleWrite(1);
            this.gpu.bindIndirect(2);
            this.gpu.bindCounter(3, slot);
            this.gpu.bindEmitBuffer(4, ringId);
            this.gpu.bindEmitters(5);
            setUIntUniform(this.programs.emit(), "uTotalSpawn", totalSpawn);
            setUIntUniform(this.programs.emit(), "uEmitCount", entryCount);
            setUIntUniform(this.programs.emit(), "uCapacity", cap);
            setUIntUniform(this.programs.emit(), "uSort", sortUInt);
            GL43.glDispatchCompute(Math.max(1, (totalSpawn + 63) / 64), 1, 1);
            GL42.glMemoryBarrier(GL43.GL_SHADER_STORAGE_BARRIER_BIT | GL42.GL_ATOMIC_COUNTER_BARRIER_BIT);
        }

        // 6. Sort path: partition (keygen) + LSD radix over the ALPHA segment only.
        int finalPerm = -1;
        boolean doRadix = false;
        if (sorted) {
            int sortUpper = Math.min(cap, Math.max(0, this.aliveRead + totalSpawn + 64));
            int alphaUpper = Math.min(cap, Math.max(0, this.prevAlpha + totalSpawn + 64));
            doRadix = alphaUpper > 0;

            // keygen: additive -> orderAdd[binding 8], alpha -> compact sortData[binding 7]
            GL20.glUseProgram(this.programs.keygen());
            this.gpu.bindParticleWrite(0);
            this.gpu.bindIndirect(2);
            this.gpu.bindCounter(3, slot);
            this.gpu.bindEmitters(5);
            this.gpu.bindOrderAdd();
            this.gpu.bindSort(ParticleBuffers.SORTWRITE_BINDING, this.gpu.sortBuffer(0));
            setUIntUniform(this.programs.keygen(), "uUpper", sortUpper);
            setFloatUniform(this.programs.keygen(), "uCamPos", (float) camera.getPosition().x,
                    (float) camera.getPosition().y, (float) camera.getPosition().z);
            setFloatUniform(this.programs.keygen(), "uMaxDepth", SORT_MAX_DEPTH);
            setMat4Uniform(this.programs.keygen(), "uView", view);
            GL43.glDispatchCompute(Math.max(1, (sortUpper + 63) / 64), 1, 1);
            GL42.glMemoryBarrier(GL43.GL_SHADER_STORAGE_BARRIER_BIT | GL42.GL_ATOMIC_COUNTER_BARRIER_BIT);

            if (doRadix) {
                int readId = this.gpu.sortBuffer(0);
                int writeId = this.gpu.sortBuffer(1);
                for (int pass = 0; pass < ParticleBuffers.RADIX_PASSES; pass++) {
                    int shift = RADIX_SHIFTS[pass];

                    this.gpu.clearHist();
                    GL42.glMemoryBarrier(GL43.GL_SHADER_STORAGE_BARRIER_BIT);

                    GL20.glUseProgram(this.programs.radixHist());
                    this.gpu.bindIndirect(2);
                    this.gpu.bindSort(ParticleBuffers.SORTREAD_BINDING, readId);
                    this.gpu.bindHist();
                    setUIntUniform(this.programs.radixHist(), "uShift", shift);
                    GL43.glDispatchCompute(Math.max(1, (alphaUpper + 63) / 64), 1, 1);
                    GL42.glMemoryBarrier(GL42.GL_ATOMIC_COUNTER_BARRIER_BIT | GL43.GL_SHADER_STORAGE_BARRIER_BIT);

                    GL20.glUseProgram(this.programs.radixScan());
                    this.gpu.bindHist();
                    this.gpu.bindOffsets();
                    GL43.glDispatchCompute(1, 1, 1);
                    GL42.glMemoryBarrier(GL43.GL_SHADER_STORAGE_BARRIER_BIT | GL42.GL_ATOMIC_COUNTER_BARRIER_BIT);

                    GL20.glUseProgram(this.programs.radixScatter());
                    this.gpu.bindIndirect(2);
                    this.gpu.bindSort(ParticleBuffers.SORTREAD_BINDING, readId);
                    this.gpu.bindSort(ParticleBuffers.SORTWRITE_BINDING, writeId);
                    this.gpu.bindOffsets();
                    setUIntUniform(this.programs.radixScatter(), "uShift", shift);
                    GL43.glDispatchCompute(Math.max(1, (alphaUpper + 63) / 64), 1, 1);
                    GL42.glMemoryBarrier(GL43.GL_SHADER_STORAGE_BARRIER_BIT | GL42.GL_ATOMIC_COUNTER_BARRIER_BIT);

                    int t = readId;
                    readId = writeId;
                    writeId = t;
                }
                finalPerm = readId; // last written buffer
            }

            GL42.glMemoryBarrier(GL43.GL_SHADER_STORAGE_BARRIER_BIT
                    | GL42.GL_ATOMIC_COUNTER_BARRIER_BIT
                    | GL42.GL_COMMAND_BARRIER_BIT);
        }

        // 7. Draw (two dense standalone permutations, no base-offset readback).
        if (this.aliveRead > 0 || entryCount > 0) {
            if (sorted) {
                this.gpu.bindOrderAdd();
                drawPass(false, 1, view, projectionMatrix, camera);
                if (doRadix) {
                    this.gpu.bindSort(ParticleBuffers.SORTWRITE_BINDING, finalPerm);
                    drawPass(true, 1, view, projectionMatrix, camera);
                }
            } else {
                drawPass(false, 0, view, projectionMatrix, camera);
            }
        }

        // 8. Capture this frame's alpha count (cmd[1].y) into the counter ring
        //    spare for next frame's lagged readback (non-blocking dual-mode feed).
        GL20.glUseProgram(this.programs.capture());
        this.gpu.bindIndirect(2);
        this.gpu.bindCounter(3, slot);
        GL43.glDispatchCompute(1, 1, 1);
        GL42.glMemoryBarrier(GL43.GL_SHADER_STORAGE_BARRIER_BIT | GL42.GL_ATOMIC_COUNTER_BARRIER_BIT);

        // 9. Unbind SSBO bases and release the 3D occupancy texture (unit 0) /
        //    sprite atlas (unit 1); swap the ping-pong pool.
        this.gpu.unbindShaders();
        GL13.glActiveTexture(GL13.GL_TEXTURE0);
        GL11.glBindTexture(GL12.GL_TEXTURE_3D, 0);
        GL13.glActiveTexture(GL13.GL_TEXTURE1);
        GL11.glBindTexture(GL11.GL_TEXTURE_2D, 0);
        GL13.glActiveTexture(GL13.GL_TEXTURE0);

        this.gpu.swap();
    }

    /** Draws one half of the pool (additive or alpha) through its permutation. */
    private void drawPass(boolean alpha, int usePerm,
            Matrix4fc view, Matrix4fc projectionMatrix, Camera camera) {
        int prog = alpha ? this.programs.alphaRender() : this.programs.render();
        if (prog == 0)
            return;
        GL20.glUseProgram(prog);
        // draw the freshly written (this frame) pool
        this.gpu.bindParticleWrite(1);
        this.gpu.bindEmitters(5);
        this.gpu.bindVao();

        setMat4Uniform(prog, "ModelViewMat", view);
        setMat4Uniform(prog, "ProjMat", projectionMatrix);

        Vec3 pos = camera.getPosition();
        Vector3f up = camera.getUpVector();
        Vector3f left = camera.getLeftVector();
        setFloatUniform(prog, "uCamPos", (float) pos.x, (float) pos.y, (float) pos.z);
        setFloatUniform(prog, "uCamRight", -left.x, -left.y, -left.z);
        setFloatUniform(prog, "uCamUp", up.x, up.y, up.z);
        setUIntUniform(prog, "uUsePerm", usePerm);

        if (alpha) {
            this.cherryAtlas.bind(1);
            setIntUniform(prog, "uSprite", 1);
            setFloatUniform(prog, "uAtlasCols", this.cherryAtlas.cols());
            setFloatUniform(prog, "uAtlasRows", this.cherryAtlas.rows());
        } else {
            setFloatUniform(prog, "uGlow", 1.0f);
        }
        setFloatUniform(prog, "uFadeDist", DEFAULT_FADE_DIST);

        RenderSystem.enableBlend();
        if (alpha) {
            RenderSystem.blendFuncSeparate(GL11.GL_SRC_ALPHA, GL11.GL_ONE_MINUS_SRC_ALPHA,
                    GL11.GL_ONE, GL11.GL_ONE_MINUS_SRC_ALPHA);
        } else {
            RenderSystem.blendFuncSeparate(GL11.GL_ONE, GL11.GL_ONE, GL11.GL_ONE, GL11.GL_ONE);
        }
        RenderSystem.enableDepthTest();
        RenderSystem.depthMask(false);

        this.gpu.bindDrawIndirect();
        if (alpha)
            this.gpu.drawIndirect(1);
        else
            this.gpu.drawIndirect(0);

        RenderSystem.depthMask(true);
        RenderSystem.defaultBlendFunc();
        RenderSystem.disableBlend();
        GL20.glUseProgram(0);
        GL30.glBindVertexArray(0);
    }

    // ------------------------------------------------------------------
    // Private helpers
    // ------------------------------------------------------------------

    private static float clampDelta(DeltaTracker deltaTracker) {
        float ticks = deltaTracker.getRealtimeDeltaTicks();
        return Math.max(0.001f, Math.min(0.25f, ticks * 0.05f));
    }

    /** Allocates (or returns) the GPU emitter header id for a spec, cached by equals. */
    private int ensureEmitter(EmitterSpec spec) {
        Integer existing = this.emitterIds.get(spec);
        if (existing != null)
            return existing;
        if (this.emitterIds.size() >= MAX_EMITTERS) {
            CreateManaIndustry.LOGGER.warn("[CMI particles] emitter header limit ({}); request dropped", MAX_EMITTERS);
            return -1;
        }
        int id = this.emitterIds.size();
        this.emitterIds.put(spec, id);
        this.gpu.setEmitterHeader(id, spec.packed());
        return id;
    }

    /**
     * Per-spawn runtime state for an emitter: flips the sorted path for ALPHA
     * specs, lazy-loads the sprite atlas, and (re)assigns the collision bake
     * slice for colliding specs, re-uploading the emitter header when needed.
     */
    private void ensureEmitterRuntime(int id, EmitterSpec spec, Vec3 origin) {
        if (spec.material == EmitterSpec.Material.ALPHA) {
            this.alphaSpawnedThisFrame = true;
            this.cherryAtlas.ensureLoaded();
        }
        if (spec.collideMode != EmitterSpec.CollideMode.NONE && origin != null) {
            int bake = this.collisionBake.ensure(spec, origin);
            Integer prev = this.bakedSliceByEmitter.get(id);
            if (prev == null || prev.intValue() != bake) {
                this.bakedSliceByEmitter.put(id, bake);
                this.gpu.setEmitterHeader(id, spec.packedWithBake(bake));
            }
        }
    }

    private static int loc(int prog, String name) {
        return GL20.glGetUniformLocation(prog, name);
    }

    private static void setUIntUniform(int prog, String name, int value) {
        int l = loc(prog, name);
        if (l >= 0)
            GL30.glUniform1ui(l, value);
    }

    private static void setIntUniform(int prog, String name, int value) {
        int l = loc(prog, name);
        if (l >= 0)
            GL20.glUniform1i(l, value);
    }

    private static void setFloatUniform(int prog, String name, float value) {
        int l = loc(prog, name);
        if (l >= 0)
            GL20.glUniform1f(l, value);
    }

    private static void setFloatUniform(int prog, String name, float x, float y, float z) {
        int l = loc(prog, name);
        if (l >= 0)
            GL20.glUniform3f(l, x, y, z);
    }

    private static void setMat4Uniform(int prog, String name, Matrix4fc matrix) {
        int l = loc(prog, name);
        if (l < 0)
            return;
        try (MemoryStack stack = MemoryStack.stackPush()) {
            FloatBuffer buf = stack.mallocFloat(16);
            matrix.get(buf);
            GL20.glUniformMatrix4fv(l, false, buf);
        }
    }
}
