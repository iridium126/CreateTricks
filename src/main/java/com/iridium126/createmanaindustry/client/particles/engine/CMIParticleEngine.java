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
import org.joml.Matrix4f;
import org.joml.Matrix4fc;
import org.joml.Vector3f;
import org.lwjgl.BufferUtils;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL12;
import org.lwjgl.opengl.GL13;
import org.lwjgl.opengl.GL15;
import org.lwjgl.opengl.GL20;
import org.lwjgl.opengl.GL30;
import org.lwjgl.opengl.GL32;
import org.lwjgl.opengl.GL33;
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
 *   <li><b>Fast</b> (no translucent particles alive): {@code keygen} still runs
 *       every frame as the cull pass — GPU frustum culling plus permutation
 *       partitioning — but the counting sort and the two sorted draws are
 *       skipped.</li>
 *   <li><b>Sorted</b> (any ALPHA/MODEL particles): {@code keygen} also writes
 *       (key,payload) pairs into the sort buffer — inverted, logarithmically
 *       quantized view-depth bands (constant relative resolution, far drawn
 *       first) with the item type riding in the payload — and a single-pass
 *       GPU counting sort (histogram/scan/scatter) orders the COMBINED
 *       translucent items back-to-front; the model ghost segment and the ALPHA
 *       sprites both walk that array, each filtering to its own type. Draw
 *       order groups all depth writers first (OPAQUE cutout sprites, model
 *       multi-draw), then the sorted translucent passes, additive last.
 *       Textured sprites sample an atlas; colliding emitters also resolve
 *       against a 3D occupancy bake in the update pass.</li>
 * </ul>
 * All GL programs are created from the mod's bundled GLSL with raw LWJGL
 * ({@link ParticlePrograms}) — no dependency on Veil's shader manager.
 */
public final class CMIParticleEngine {

    public static final CMIParticleEngine INSTANCE = new CMIParticleEngine();

    private static final int MAX_EMITTERS = 128;
    private static final int SAFETY_MARGIN = 2048;
    /**
     * Width of the distance fade ramp past the configured fade distance
     * (blocks); must match the literal in additive.fsh / textured.fsh.
     */
    private static final float FADE_RAMP_BLOCKS = 24.0f;
    /** Counting-sort passes over the 8-bit depth-band key (one, by design). */
    private static final int RADIX_SHIFTS[] = { 0 };
    /** GL_TIME_ELAPSED query ring — reads a 3-frame-old sample, never blocking. */
    private static final int TIMER_RING = 4;

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

    /** Live animation-switch request posted from a client thread. */
    private record AnimReq(EmitterSpec spec, EmitterSpec.Animation animation) {
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
    private final List<Stream> streams = new ArrayList<>();
    private final ParticleBuffers gpu = new ParticleBuffers();
    private final ParticlePrograms programs = new ParticlePrograms();
    private final ParticleFrameProfiler profiler = new ParticleFrameProfiler();
    private final CollisionBake collisionBake = new CollisionBake();
    private final ParticleAtlas cherryAtlas = ParticleAtlas.CHERRY;
    private final ParticleAtlas allayAtlas = ParticleAtlas.ALLAY;

    private final FloatBuffer emitFront = BufferUtils.createFloatBuffer(ParticleBuffers.MAX_EMIT_COMMANDS * 8);
    private final Vec3[] emitOrigins = new Vec3[ParticleBuffers.MAX_EMIT_COMMANDS];
    private final int[] emitIds = new int[ParticleBuffers.MAX_EMIT_COMMANDS];
    private final int[] emitCounts = new int[ParticleBuffers.MAX_EMIT_COMMANDS];
    private final boolean[] emitTranslucent = new boolean[ParticleBuffers.MAX_EMIT_COMMANDS];

    /**
     * Uniform-location cache (program id -> name -> location). Locations are
     * stable for a linked program, so the per-frame uniform sets cost one map
     * lookup instead of a driver call; cleared whenever programs are rebuilt
     * (rebuild recreates every program id) or the engine shuts down.
     */
    private final Map<Integer, Map<String, Integer>> uniformLocations = new HashMap<>();

    private boolean initialized = false;
    private boolean disabled = false;
    /**
     * Newest fence-signalled counter snapshot (may lag a few frames while the
     * GPU runs behind): {@code {exact live count, unculled translucent census}}
     * — the census counts ALPHA and MODEL particles (both feed the combined
     * translucent sort).
     */
    private int aliveKnown = 0;
    private int translucentKnown = 0;
    /**
     * Spawns requested since that snapshot (CPU-exact). They keep every
     * dispatch bound conservative between fence polls — deaths only ever
     * shrink the live pool, so {@code snapshot + delta} is always an upper
     * bound on the real count.
     */
    private int spawnDelta = 0;
    private int translucentSpawnDelta = 0;
    /**
     * Latch for the sorted-path decision: set when translucent particles
     * (ALPHA or MODEL) spawn, cleared only by a FRESH census reading zero —
     * fence lag can never cause a frame where live translucent items skip
     * the sorted draw.
     */
    private boolean translucentLatched = false;
    /** Fence over the newest un-read frame's GPU work; 0 = none pending. */
    private long pendingFence = 0;
    /** Counter ring slot {@link #pendingFence} covers. */
    private int pendingSlot = 0;
    private volatile int liveDisplay = 0;
    private volatile float scale = 1f;
    private volatile int streamCount = 0;
    private int simFrame = 0;
    private int frameSeed = 0;
    private long lastErrorTime = 0;
    private final int[] timerQueries = new int[TIMER_RING];
    private int timerSlot = 0;
    private int timerIssued = 0;
    private boolean timerReady = false;
    /** Last completed GPU-side frame cost (ms), 3 frames lagged; 0 = none yet. */
    private double lastGpuMs = 0;
    /** Scratch Proj*View and its 6 normalized frustum planes (keygen cull test). */
    private final Matrix4f projView = new Matrix4f();
    private final float[] frustumPlanes = new float[24];
    private final org.joml.Vector4f planeScratch = new org.joml.Vector4f();

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

    /**
     * Live-switches the animation of every emitter created from {@code spec} —
     * including already-alive particles (the header re-uploads next frame).
     * Fire-and-forget like {@link #spawn}; applied on the render thread.
     */
    public void setAnimation(EmitterSpec spec, EmitterSpec.Animation animation) {
        this.pending.add(new AnimReq(spec, animation));
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
        if (this.timerReady) {
            GL15.glDeleteQueries(this.timerQueries);
            this.timerReady = false;
        }
        this.timerSlot = 0;
        this.timerIssued = 0;
        this.lastGpuMs = 0;
        try {
            this.cherryAtlas.free();
            this.allayAtlas.free();
            this.collisionBake.free();
        } catch (RuntimeException | LinkageError e) {
            CreateManaIndustry.LOGGER.warn("[CMI particles] atlas/bake free failed", e);
        }
        this.pending.clear();
        this.streams.clear();
        resetPoolState();
        this.uniformLocations.clear();
        this.initialized = false;
        this.disabled = false;
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
        if (!ClientConfig.particleEnabled) {
            // Master switch off: drop live particles/streams so nothing lingers
            // (we are on the render thread, GPU state is ours to touch).
            if (this.initialized)
                dropAll();
            return;
        }
        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null)
            return;

        if (!this.initialized) {
            try {
            this.initialized = this.gpu.init(ClientConfig.particleMaxCount, MAX_EMITTERS);
            if (this.initialized)
                this.gpu.uploadModelGeometry(
                        AllayModelGeometry.VERTICES, AllayModelGeometry.INDICES,
                        AllayModelGeometry.OPAQUE_INDEX_COUNT);
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
        if (this.programs.needsRebuild()) {
            this.programs.rebuild();
            this.uniformLocations.clear(); // every program id was recreated
        }
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
        // Prefer the (lagged) GPU-side cost; fall back to CPU submit time until
        // the first timer query has completed.
        this.profiler.record(this.lastGpuMs > 0 ? this.lastGpuMs : (System.nanoTime() - t0) / 1_000_000.0,
                ClientConfig.particleAutoThrottle);
        this.scale = this.profiler.emissionScale();
    }

    /** Clears every particle, stream and queued request (render thread only). */
    private void dropAll() {
        this.pending.clear();
        this.streams.clear();
        this.gpu.clearParticles();
        resetPoolState();
        this.streamCount = 0;
        this.profiler.reset();
    }

    /** Resets the CPU-side counter snapshot bookkeeping (pool is/becomes empty). */
    private void resetPoolState() {
        this.aliveKnown = 0;
        this.translucentKnown = 0;
        this.spawnDelta = 0;
        this.translucentSpawnDelta = 0;
        this.translucentLatched = false;
        this.liveDisplay = 0;
        if (this.pendingFence != 0) {
            GL32.glDeleteSync(this.pendingFence);
            this.pendingFence = 0;
        }
    }

    private void runFrame(Camera camera, Matrix4fc view, Matrix4fc projectionMatrix, DeltaTracker deltaTracker) {
        this.frameSeed++;

        int slot = this.simFrame % ParticleBuffers.COUNTER_RING;
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
            } else if (item instanceof AnimReq ar) {
                applyAnimation(ar.spec(), ar.animation());
            } else if (item instanceof Boolean) {
                doClear = true;
            }
        }
        if (doClear) {
            this.streams.clear();
            this.gpu.clearParticles();
            resetPoolState();
        }

        int cap = this.gpu.capacity();
        if (!doClear)
            pollCounterSnapshot(cap);

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
            this.emitTranslucent[entryCount] = isTranslucent(b.spec);
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
            this.emitTranslucent[entryCount] = isTranslucent(s.spec);
            totalSpawn += n;
            entryCount++;
        }

        this.streamCount = this.streams.size();

        // 3. Cap spawn request to the free pool (extra safety margin). The
        // pool-usage estimate uses the (possibly stale) snapshot plus spawns
        // since — overspawning is impossible: the GPU-side slot guard drops
        // anything beyond capacity anyway.
        int free = Math.max(0, cap - this.aliveKnown - this.spawnDelta - SAFETY_MARGIN);
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
                    this.emitTranslucent[w] = this.emitTranslucent[i];
                    totalSpawn += n;
                    w++;
                }
            }
            entryCount = w;
        }

        // Translucent spawns (ALPHA sprites + MODEL parts, CPU-exact even when
        // the snapshot is stale) latch the sorted path until a fresh census
        // reads zero.
        int translucentSpawnTotal = 0;
        for (int i = 0; i < entryCount; i++)
            if (this.emitTranslucent[i])
                translucentSpawnTotal += this.emitCounts[i];
        if (translucentSpawnTotal > 0)
            this.translucentLatched = true;

        // 4. Upload emit commands into the next ring slot + emitters.
        int ringId = this.gpu.nextEmitBuffer();
        this.gpu.uploadDirtyEmitters();
        if (entryCount > 0) {
            this.emitFront.clear();
            int prefix = 0;
            for (int i = 0; i < entryCount; i++) {
                Vec3 o = this.emitOrigins[i];
                float seed = (((long) this.frameSeed * 2654435761L + i * 73856093L) >>> 8) % 1_000_000 * 0.000001f;
                this.emitFront.put((float) o.x).put((float) o.y).put((float) o.z).put(this.emitCounts[i]);
                // b.z carries the exclusive prefix offset so the shader can
                // binary-search its command instead of scanning linearly
                this.emitFront.put(this.emitIds[i]).put(seed).put((float) prefix).put(0f);
                prefix += this.emitCounts[i];
            }
            this.emitFront.flip();
            this.gpu.uploadEmits(ringId, this.emitFront);
        }

        // Dual mode: the combined translucent sort + the two sorted draws run
        // only while translucent particles may exist (ALPHA or MODEL — latched
        // on spawn, released only by a fresh census reading zero). keygen
        // itself runs in both paths — it is the fast path's frustum-cull pass
        // and owns the draw counts in both.
        boolean sorted = this.translucentLatched;

        // 5. Compute passes: reset -> update -> emit. The elapsed-time query
        //    brackets all GPU work (dispatches + draws) of this frame.
        // Upper bound on the read buffer's live count (snapshot + spawns since
        // — deaths only shrink it); update threads beyond the GPU-exact count
        // (read from the previous counter slot, binding 14) exit immediately.
        int updateBound = Math.min(cap, this.aliveKnown + this.spawnDelta + 64);
        if (!this.timerReady) {
            GL15.glGenQueries(this.timerQueries);
            this.timerReady = true;
        }
        GL15.glBeginQuery(GL33.GL_TIME_ELAPSED, this.timerQueries[this.timerSlot]);
        GL20.glUseProgram(this.programs.reset());
        this.gpu.bindIndirect(2);
        this.gpu.bindCounter(3, slot);
        GL43.glDispatchCompute(1, 1, 1);
        GL42.glMemoryBarrier(GL43.GL_SHADER_STORAGE_BARRIER_BIT | GL42.GL_ATOMIC_COUNTER_BARRIER_BIT);

        GL20.glUseProgram(this.programs.update());
        this.gpu.bindParticleRead(0);
        this.gpu.bindParticleWrite(1);
        this.gpu.bindCounter(3, slot);
        this.gpu.bindPrevCounter(ParticleBuffers.PREVCOUNTER_BINDING, slot);
        this.gpu.bindEmitters(5);
        if (this.collisionBake.ready()) {
            this.collisionBake.bind(0);
            this.gpu.bindBakeMeta();
            setIntUniform(this.programs.update(), "uCollision", 0);
            setIntUniform(this.programs.update(), "uCollisionOn", 1);
        } else {
            setIntUniform(this.programs.update(), "uCollisionOn", 0);
        }
        setUIntUniform(this.programs.update(), "uCapacity", cap);
        setFloatUniform(this.programs.update(), "uDt", dt);
        GL43.glDispatchCompute(Math.max(1, (updateBound + 63) / 64), 1, 1);
        GL42.glMemoryBarrier(GL43.GL_SHADER_STORAGE_BARRIER_BIT | GL42.GL_ATOMIC_COUNTER_BARRIER_BIT);

        if (entryCount > 0) {
            GL20.glUseProgram(this.programs.emit());
            this.gpu.bindParticleWrite(1);
            this.gpu.bindCounter(3, slot);
            this.gpu.bindEmitBuffer(4, ringId);
            this.gpu.bindEmitters(5);
            setUIntUniform(this.programs.emit(), "uTotalSpawn", totalSpawn);
            setUIntUniform(this.programs.emit(), "uEmitCount", entryCount);
            setUIntUniform(this.programs.emit(), "uCapacity", cap);
            GL43.glDispatchCompute(Math.max(1, (totalSpawn + 63) / 64), 1, 1);
            GL42.glMemoryBarrier(GL43.GL_SHADER_STORAGE_BARRIER_BIT | GL42.GL_ATOMIC_COUNTER_BARRIER_BIT);
        }

        // This frame's spawns are CPU-exact even when the counter snapshot is
        // stale; accumulating them right away keeps every dispatch bound below
        // conservative until the next fence poll resets the baseline.
        this.spawnDelta += totalSpawn;
        this.translucentSpawnDelta += translucentSpawnTotal;

        // 6. keygen: GPU frustum cull + partition of both permutations. Runs in
        //    BOTH paths (it is the fast path's cull pass); it owns the draw
        //    counts, so update/emit never touch the indirect buffer.
        int finalPerm = -1;
        // spawnDelta now includes this frame's spawns; aliveEstimate is an
        // upper bound on the freshly written pool's live count
        int aliveEstimate = this.aliveKnown + this.spawnDelta;
        if (aliveEstimate > 0 || entryCount > 0) {
            int sortUpper = Math.min(cap, Math.max(0, aliveEstimate + 64));

            // keygen: additive -> orderAdd[8], opaque sprites -> orderOpaque[15],
            // model opaque -> orderModel[13], translucent items -> sortData[7]
            int kg = this.programs.keygen();
            GL20.glUseProgram(kg);
            this.gpu.bindParticleWrite(0);
            this.gpu.bindIndirect(2);
            this.gpu.bindCounter(3, slot);
            this.gpu.bindEmitters(5);
            this.gpu.bindOrderAdd();
            this.gpu.bindOrderOpaque();
            this.gpu.bindSort(ParticleBuffers.SORTWRITE_BINDING, this.gpu.sortBuffer(0));
            setUIntUniform(kg, "uUpper", sortUpper);
            Vec3 camPos = camera.getPosition();
            setFloatUniform(kg, "uCamPos", (float) camPos.x, (float) camPos.y, (float) camPos.z);
            setFloatUniform(kg, "uMaxDepth", sortFarBlocks());
            setMat4Uniform(kg, "uView", view);
            extractFrustum(projectionMatrix, view);
            int frustumLoc = loc(kg, "uFrustum");
            if (frustumLoc >= 0)
                GL20.glUniform4fv(frustumLoc, this.frustumPlanes);
            GL43.glDispatchCompute(Math.max(1, (sortUpper + 63) / 64), 1, 1);
            GL42.glMemoryBarrier(GL43.GL_SHADER_STORAGE_BARRIER_BIT
                    | GL42.GL_ATOMIC_COUNTER_BARRIER_BIT
                    | GL42.GL_COMMAND_BARRIER_BIT);

            if (sorted) {
                // single counting-sort pass over the COMBINED translucent
                // items (ALPHA sprites + MODEL translucent parts, one item per
                // particle); dispatch sized by the (possibly stale) census
                // plus translucent spawns since — always an upper bound
                int translucentUpper = Math.min(cap,
                        Math.max(0, this.translucentKnown + this.translucentSpawnDelta + 64));
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
                    GL43.glDispatchCompute(Math.max(1, (translucentUpper + 63) / 64), 1, 1);
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
                    GL43.glDispatchCompute(Math.max(1, (translucentUpper + 63) / 64), 1, 1);
                    GL42.glMemoryBarrier(GL43.GL_SHADER_STORAGE_BARRIER_BIT | GL42.GL_ATOMIC_COUNTER_BARRIER_BIT);

                    int t = readId;
                    readId = writeId;
                    writeId = t;
                }
                finalPerm = readId; // last written buffer

                GL42.glMemoryBarrier(GL43.GL_SHADER_STORAGE_BARRIER_BIT
                        | GL42.GL_ATOMIC_COUNTER_BARRIER_BIT
                        | GL42.GL_COMMAND_BARRIER_BIT);
            }
        }

        // 7. Draw. Every DEPTH-WRITING pass first (OPAQUE cutout sprites, then
        //    ONE merged model multi-draw: opaque cutout segment -> translucent
        //    ghost segment), so early-Z rejects occluded fragments of all later
        //    blended passes. When translucent items exist, the combined sort
        //    runs and the ALPHA sprites follow back-to-front (blend, no depth
        //    write; ghosts before sprites is the documented tradeoff). Additive
        //    draws LAST: it is order-independent, so position costs nothing but
        //    lets ghost depth occlude glow behind cloaks.
        if (aliveEstimate > 0 || entryCount > 0) {
            // Bind the FINAL sorted permutation up front: both translucent
            // segments (model ghost segment here, ALPHA sprites below) walk it.
            // On the fast path nothing reads it, but the programs statically
            // declare the sort SSBO, so keep a valid buffer bound regardless.
            this.gpu.bindSort(ParticleBuffers.SORTWRITE_BINDING,
                    sorted ? finalPerm : this.gpu.sortBuffer(0));
            this.gpu.bindOrderOpaque();
            drawPass(1, view, projectionMatrix, camera);
            drawModels(view, projectionMatrix, camera);
            if (sorted) {
                drawPass(2, view, projectionMatrix, camera);
            }
            this.gpu.bindOrderAdd();
            drawPass(0, view, projectionMatrix, camera);
        }

        // 8. Capture this frame's UNculled translucent census (counter.translucentCensus,
        //    maintained by keygen before the frustum test) into the counter
        //    ring spare, then fence the frame: next frame POLLS the fence and
        //    only reads the counters once the GPU has finished writing them
        //    (a raw readback is a pipeline stall, however lagged the slot).
        GL20.glUseProgram(this.programs.capture());
        this.gpu.bindCounter(3, slot);
        GL43.glDispatchCompute(1, 1, 1);
        GL42.glMemoryBarrier(GL43.GL_SHADER_STORAGE_BARRIER_BIT | GL42.GL_ATOMIC_COUNTER_BARRIER_BIT);
        GL15.glEndQuery(GL33.GL_TIME_ELAPSED);
        if (this.pendingFence != 0)
            GL32.glDeleteSync(this.pendingFence);
        this.pendingFence = GL32.glFenceSync(GL32.GL_SYNC_GPU_COMMANDS_COMPLETE, 0);
        this.pendingSlot = slot;

        // Collect the oldest query in the ring (issued TIMER_RING-1 frames ago,
        // virtually always complete): the true GPU-side cost of a frame. Never
        // blocks — skipped when the GPU is running that far behind.
        this.timerSlot = (this.timerSlot + 1) % TIMER_RING;
        if (++this.timerIssued >= TIMER_RING) {
            int oldest = this.timerQueries[this.timerSlot];
            if (GL15.glGetQueryObjecti(oldest, GL15.GL_QUERY_RESULT_AVAILABLE) == GL11.GL_TRUE)
                this.lastGpuMs = (GL15.glGetQueryObjectui(oldest, GL15.GL_QUERY_RESULT) & 0xFFFFFFFFL) / 1_000_000.0;
        }

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

    /**
     * Draws BOTH MODEL segments through ONE glMultiDrawElementsIndirect: the
     * opaque segment (cutout + depth writes) then the translucent cloak+wings
     * segment. Both commands resolve instances identically — the COMBINED
     * translucent sort array filtered to model items — and differ only in
     * their element-buffer index range, so no per-draw uniform or attribute is
     * needed (a baseInstance/divisor-1 selector was tried here and REJECTED:
     * instanced attribute fetch walks baseInstance + instanceID, so with more
     * than one model particle later instances read wrong/OOB entries and lose
     * their geometry). The translucent segment blends WITH depth writes:
     * within one allay the depth writes resolve part order geometrically while
     * giving the double-wound shell a single blend per pixel from BOTH sides;
     * across draws, ghost surfaces occlude later translucent passes (sprites
     * behind a cloak are hidden rather than seen through it) — the documented
     * tradeoff. Winding follows vanilla {@code ModelPart.Cube} order; if a
     * future geometry bake flips it, swap {@code glFrontFace} — do not reorder
     * the data.
     */
    private void drawModels(Matrix4fc view, Matrix4fc projectionMatrix, Camera camera) {
        int prog = this.programs.modelRender();
        if (prog == 0)
            return;
        GL20.glUseProgram(prog);
        this.gpu.bindParticleWrite(1);
        this.gpu.bindEmitters(5);
        this.gpu.bindModelGeo(); // unbindShaders() clears binding 12 every frame
        this.gpu.bindVao();

        setMat4Uniform(prog, "ModelViewMat", view);
        setMat4Uniform(prog, "ProjMat", projectionMatrix);
        Vec3 pos = camera.getPosition();
        setFloatUniform(prog, "uCamPos", (float) pos.x, (float) pos.y, (float) pos.z);
        setFloatUniform(prog, "uFadeDist", (float) ClientConfig.particleFadeDistance);
        this.allayAtlas.bind(1);
        setIntUniform(prog, "uSprite", 1);

        RenderSystem.enableDepthTest();
        GL11.glEnable(GL11.GL_CULL_FACE); // double-wound faces stay visible from both sides
        // blend enabled for both segments (the opaque fsh outputs alpha 1.0,
        // so blending is a no-op there); BOTH write depth — opaque by
        // definition, translucent so ghosts occlude later translucent draws
        RenderSystem.enableBlend();
        RenderSystem.blendFuncSeparate(GL11.GL_SRC_ALPHA, GL11.GL_ONE_MINUS_SRC_ALPHA,
                GL11.GL_ONE, GL11.GL_ONE_MINUS_SRC_ALPHA);
        RenderSystem.depthMask(true);

        this.gpu.bindDrawIndirect();
        this.gpu.drawModelSegments();

        RenderSystem.depthMask(true);
        RenderSystem.defaultBlendFunc();
        RenderSystem.disableBlend();
        GL20.glUseProgram(0);
        GL30.glBindVertexArray(0);
    }

    /**
     * Draws one billboard bucket: 0 = additive (soft circle, unsorted, drawn
     * LAST), 1 = OPAQUE cutout sprite (depth write, unsorted), 2 = ALPHA
     * blended sprite (walks the combined translucent sort array, no depth
     * write). Bucket 1 runs before every blended pass so its depth writes feed
     * early-Z; buckets 0/2 never write depth.
     */
    private void drawPass(int mode,
            Matrix4fc view, Matrix4fc projectionMatrix, Camera camera) {
        boolean textured = mode != 0;
        int prog = textured ? this.programs.texturedRender() : this.programs.render();
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

        if (textured) {
            this.cherryAtlas.bind(1);
            setIntUniform(prog, "uSprite", 1);
            setFloatUniform(prog, "uAtlasCols", this.cherryAtlas.cols());
            setFloatUniform(prog, "uAtlasRows", this.cherryAtlas.rows());
            setIntUniform(prog, "uMode", mode == 1 ? 1 : 0);
        } else {
            setFloatUniform(prog, "uGlow", 1.0f);
        }
        setFloatUniform(prog, "uFadeDist", (float) ClientConfig.particleFadeDistance);

        RenderSystem.enableDepthTest();
        if (mode == 1) {
            // OPAQUE cutout: no blending, depth writes — order-independent
            RenderSystem.disableBlend();
            RenderSystem.depthMask(true);
        } else {
            RenderSystem.enableBlend();
            if (mode == 2)
                RenderSystem.blendFuncSeparate(GL11.GL_SRC_ALPHA, GL11.GL_ONE_MINUS_SRC_ALPHA,
                        GL11.GL_ONE, GL11.GL_ONE_MINUS_SRC_ALPHA);
            else
                RenderSystem.blendFuncSeparate(GL11.GL_ONE, GL11.GL_ONE, GL11.GL_ONE, GL11.GL_ONE);
            RenderSystem.depthMask(false);
        }

        this.gpu.bindDrawIndirect();
        if (mode == 0)
            this.gpu.drawIndirect(0);
        else if (mode == 1)
            this.gpu.drawIndirect(1);
        else
            this.gpu.drawIndirect(4);

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

    /**
     * Fence-polled counter snapshot. The pending fence covers the newest frame
     * whose counters we have not read; polling with a zero timeout never
     * stalls. When the GPU has finished that frame, {@code {writeSlot, spare}}
     * = {@code {exact live count, unculled translucent census}} becomes the fresh
     * snapshot and the CPU-side spawn deltas reset. Otherwise the stale
     * snapshot stands and the deltas keep every dispatch bound conservative
     * (deaths only ever shrink the live pool, so snapshot + delta is always a
     * safe upper bound).
     */
    private void pollCounterSnapshot(int cap) {
        if (this.pendingFence == 0)
            return;
        int wait = GL32.glClientWaitSync(this.pendingFence, 0, 0L);
        if (wait == GL32.GL_TIMEOUT_EXPIRED)
            return; // GPU still behind — poll again next frame, zero stalls
        if (wait == GL32.GL_WAIT_FAILED) {
            long now = System.currentTimeMillis();
            if (now - this.lastErrorTime > 5000) {
                this.lastErrorTime = now;
                CreateManaIndustry.LOGGER.warn("[CMI particles] counter fence wait failed; keeping stale snapshot");
            }
        } else {
            int[] counts = this.gpu.readbackCounts(this.pendingSlot);
            int alive = counts[0];
            int translucent = counts[1];
            if (alive < 0 || alive > cap)
                alive = 0;
            if (translucent < 0 || translucent > cap)
                translucent = 0;
            this.aliveKnown = alive;
            this.translucentKnown = translucent;
            this.spawnDelta = 0;
            this.translucentSpawnDelta = 0;
            this.translucentLatched = translucent > 0;
            this.liveDisplay = alive;
        }
        GL32.glDeleteSync(this.pendingFence);
        this.pendingFence = 0;
    }

    /**
     * Power-of-two sort far bound covering the configured fade end (>= 64).
     * The 256 logarithmic depth bands then carry ~constant relative
     * resolution: 2^(log2(far)/256) per band, e.g. ~1.9% at 128 blocks.
     */
    private static float sortFarBlocks() {
        int far = (int) Math.ceil(ClientConfig.particleFadeDistance + FADE_RAMP_BLOCKS);
        return Math.max(64f, Integer.highestOneBit(Math.max(1, far - 1)) << 1);
    }

    /**
     * Extracts the six frustum planes of Proj*View into {@link #frustumPlanes}
     * for keygen's per-particle sphere test, using JOML's built-in
     * Gribb–Hartmann extraction (PLANE_NX..PLANE_PZ; inside points satisfy
     * dot(n,p)+d >= 0). Hand-rolling this with mAB() accessors is a convention
     * trap — JOML's mAB is column-A/row-B, and mixing up rows/columns yields
     * garbage planes that cull everything. The view matrix is the camera
     * rotation, so plane distances are measured against camera-relative
     * positions — matching the shader convention
     * {@code uView * vec4(worldPos - uCamPos, 1)}.
     */
    private void extractFrustum(Matrix4fc projectionMatrix, Matrix4fc view) {
        Matrix4f m = this.projView.set(projectionMatrix).mul(view);
        for (int i = 0; i < 6; i++) {
            m.frustumPlane(i, this.planeScratch);
            float a = this.planeScratch.x;
            float b = this.planeScratch.y;
            float c = this.planeScratch.z;
            float d = this.planeScratch.w;
            float inv = 1f / (float) Math.sqrt(a * a + b * b + c * c);
            int o = i * 4;
            this.frustumPlanes[o] = a * inv;
            this.frustumPlanes[o + 1] = b * inv;
            this.frustumPlanes[o + 2] = c * inv;
            this.frustumPlanes[o + 3] = d * inv;
        }
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
     * Render-thread half of {@link #setAnimation}: rewrites the header of
     * every emitter created from the spec (the mirror re-uploads next frame,
     * so already-alive particles pick the new animation up immediately).
     */
    private void applyAnimation(EmitterSpec spec, EmitterSpec.Animation animation) {
        if (spec.material != EmitterSpec.Material.MODEL)
            return;
        for (var e : this.emitterIds.entrySet()) {
            if (e.getKey().equals(spec))
                this.gpu.setEmitterHeader(e.getValue(), spec.packedWithAnimation(animation.index()));
        }
    }

    /**
     * Per-spawn runtime state for an emitter: lazy-loads the sprite atlas and
     * keeps the collision bake volume near the spawn site alive. The emitter
     * header itself stays position-free — update.comp picks the containing
     * bake slice per particle, so one spec can serve many spawn sites.
     */
    private void ensureEmitterRuntime(int id, EmitterSpec spec, Vec3 origin) {
        if (spec.material == EmitterSpec.Material.ALPHA || spec.material == EmitterSpec.Material.OPAQUE) {
            this.cherryAtlas.ensureLoaded();
        } else if (spec.material == EmitterSpec.Material.MODEL) {
            this.allayAtlas.ensureLoaded();
        }
        if (spec.collideMode != EmitterSpec.CollideMode.NONE && origin != null)
            this.collisionBake.ensure(origin);
    }

    /** Whether a spec's particles feed the combined translucent sort (ALPHA or MODEL). */
    private static boolean isTranslucent(EmitterSpec spec) {
        return spec.material == EmitterSpec.Material.ALPHA || spec.material == EmitterSpec.Material.MODEL;
    }

    private static int loc(int prog, String name) {
        return INSTANCE.uniformLocations
                .computeIfAbsent(prog, p -> new HashMap<>())
                .computeIfAbsent(name, n -> GL20.glGetUniformLocation(prog, n));
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
