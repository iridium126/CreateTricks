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
 *       (key,payload) pairs into the sort buffer — a 9-bit key whose high bit
 *       selects the item type (MODEL=0, ALPHA sprite=1) over inverted,
 *       logarithmically quantized view-depth bands (constant relative
 *       resolution, far drawn first) — and a single-pass GPU counting sort
 *       orders them back-to-front WITHIN each type: the scatter partitions the
 *       array into a MODEL slice [0, N_model) and an ALPHA slice after it, so
 *       every translucent command carries an exact instanceCount and launches
 *       zero foreign-type vertices. Draw
 *       order groups all depth writers first (OPAQUE cutout sprites, model
 *       multi-draw), then the sorted translucent passes, additive last.
 *       Textured sprites sample an atlas; colliding emitters also resolve
 *       against a 3D occupancy bake in the update pass.</li>
 * </ul>
 * All GL programs are created from the mod's bundled GLSL with raw LWJGL
 * ({@link ParticlePrograms}) — no dependency on Veil's shader manager.
 * <p>
 * The frame is SPLIT across two {@code RenderLevelStageEvent} stages of the
 * same {@code renderLevel} pass: {@link #beginFrame} at AFTER_SKY runs every
 * compute dispatch (integrate/emit/cull/sort/capture) and commits a generation;
 * the shader-pack merge hook consumes that commit mid-renderLevel so merged
 * programs cull against THIS frame's camera; {@link #endFrame} at AFTER_LEVEL
 * then submits all draw passes from the committed pool. This removes the
 * previous one-generation entry lag for pack-path MODEL particles; the Iris
 * shadow track (which runs before renderSky, ahead of any stage event) keeps
 * reading the prior generation by design.
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
    /**
     * GL_TIME_ELAPSED query ring depth — each ring reads a 3-frame-old sample,
     * never blocking. The split frame runs TWO rings ({@link #computeTimer}
     * for the AFTER_SKY phase, {@link #drawTimer} for the AFTER_LEVEL phase);
     * their latest samples are summed as the throttle input.
     */
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

    /** Starts (or restarts) the persistent Allay Storm at a site. */
    private record StormReq(Vec3 origin, int count, double radius, int mode, float omega) {
    }

    /** Stops the active storm; its members expire this frame. */
    private record StormStop() {
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
    /**
     * Counter ring slot of the last frame whose output pool was committed by a
     * swap — the ONLY slot update.comp may trust as the read buffer's exact
     * live count. Deriving that slot positionally ({@code current - 1}) breaks
     * when a frame aborts mid-flight: its reset/update passes have already
     * written a PARTIAL counter value into its own slot, and gating the next
     * frame's compaction on that garbage would let stale pool entries beyond
     * the true dense prefix resurrect as ghost particles. Pinning the gate to
     * the last GOOD slot keeps it matched to whatever pool the read side
     * actually holds. Every path that empties the pool ({@code init},
     * {@code clearParticles}) zeroes ALL counter slots, so a stale index left
     * behind by those paths safely reads a live count of 0.
     */
    private int lastGoodSlot = 0;
    /**
     * Sort buffer GL id holding the newest COMMITTED translucent permutation --
     * pointer-local {@code finalPerm} dies with runCompute, but the pack entity
     * merge hook runs inside renderLevel AFTER the AFTER_SKY compute commit and
     * BEFORE the AFTER_LEVEL draw phase, binding exactly the committed
     * permutation of this frame. -1 until the first sorted frame commits.
     */
    private int lastFinalPermId = -1;
    /** simFrame stamp of that newest committed permutation (consumption-age diagnostic). */
    private int lastFinalPermFrameSim = 0;
    /**
     * Set by the pack entity merge hook when it drew the MODEL segments this
     * frame (it fires mid-renderLevel, between the AFTER_SKY compute phase and
     * the AFTER_LEVEL draw phase); {@code endFrame} skips its own drawModels
     * accordingly (render arbitration) and clears the latch afterwards.
     * Written only from the render thread.
     */
    private boolean hookModelsDrawn = false;
    /** Accumulated GPU ms measured inside the shader-pack hook (timer ring). */
    private double externalHookGpuMs = 0;
    // Observable status for /cmip shaderpack status -- written by the merge
    // hook, read by the command, both on the render thread.
    public volatile String shaderPackPathStatus = "self-drawn";
    public volatile String shaderPackDepthStatus = "n/a";
    /** S-track state for /cmip shaderpack status: n/a / active / no shadow track / ... */
    public volatile String shaderPackShadowStatus = "n/a";
    /**
     * Diagnostics for /cmip shaderpack status: how many frames old the sort
     * permutation a pack hook last consumed was. 0 = the merged programs drew
     * THIS frame's cull/sort result; >= 1 = stale generation (shadow track by
     * design — Iris renders shadows before renderSky, ahead of every
     * level-stage event).
     */
    public volatile String shaderPackPermStatus = "n/a";
    public volatile String shaderPackErrorStatus = "";
    private volatile int liveDisplay = 0;
    private volatile float scale = 1f;
    private volatile int streamCount = 0;
    private int simFrame = 0;
    private int frameSeed = 0;

    // ---- Split-frame handoff (AFTER_SKY compute -> AFTER_LEVEL draws) ------
    /** Set by beginFrame when its guards passed; drives endFrame accounting. */
    private boolean frameAttempted = false;
    /** Set only when the compute phase committed fully; gates ALL draw submission. */
    private boolean frameArmed = false;
    /** beginFrame submission clock; endFrame falls back to it before timer samples flow. */
    private long frameStartNanos = 0;
    /**
     * Per-frame handoff values computed by runCompute, consumed by runDraws —
     * the draws repeat the compute section's own guard so a frame where nothing
     * is alive still skips vertex work but keeps every buffer binding valid.
     */
    private int frameAliveEstimate = 0;
    private int frameEntryCount = 0;
    private boolean frameSorted = false;
    private int frameFinalPerm = -1;

    /**
     * One GL_TIME_ELAPSED query ring. Collects the oldest sample on rotation
     * ONLY when available (zero stalls); otherwise the previous value stands.
     * Two instances cover the split frame: one brackets the AFTER_SKY compute
     * phase, the other the AFTER_LEVEL draw phase. Their latest samples are
     * summed as the throttle input so the budget sees the whole per-frame
     * particle cost exactly as when one bracket covered everything.
     */
    private static final class GpuTimerRing {
        final int[] queries = new int[TIMER_RING];
        int slot = 0;
        int issued = 0;
        boolean ready = false;
        /** True once any completed sample has ever landed in {@link #lastMs}. */
        boolean sampled = false;
        double lastMs = 0;

        void ensureCreated() {
            if (!this.ready) {
                GL15.glGenQueries(this.queries);
                this.ready = true;
            }
        }

        void begin() {
            GL15.glBeginQuery(GL33.GL_TIME_ELAPSED, this.queries[this.slot]);
        }

        /** Ends an open bracket exactly once (mirrors the old partial-query discipline). */
        void endBracket() {
            GL15.glEndQuery(GL33.GL_TIME_ELAPSED);
        }

        /** Rotates the cursor and polls the oldest sample — never blocks. */
        void rotateAndPoll() {
            this.slot = (this.slot + 1) % TIMER_RING;
            if (++this.issued >= TIMER_RING) {
                int oldest = this.queries[this.slot];
                if (GL15.glGetQueryObjecti(oldest, GL15.GL_QUERY_RESULT_AVAILABLE) == GL11.GL_TRUE) {
                    this.lastMs = (GL15.glGetQueryObjectui(oldest, GL15.GL_QUERY_RESULT) & 0xFFFFFFFFL) / 1_000_000.0;
                    this.sampled = true;
                }
            }
        }

        void free() {
            if (this.ready) {
                GL15.glDeleteQueries(this.queries);
                this.ready = false;
            }
            this.slot = 0;
            this.issued = 0;
            this.sampled = false;
            this.lastMs = 0;
        }
    }

    // ---- Allay Storm state (render thread only) ----------------------------
    /** Accumulated clamped simulation seconds; drives storm wander/bursts. */
    private float timeSec = 0f;
    private boolean stormActive = false;
    private int stormEmitId = -1;
    private Vec3 stormAnchor = Vec3.ZERO;
    private double stormRadius = 8.0;
    /** SIGNED vortex angular velocity rad/s (0 in ball mode / when idle). */
    private float stormOmega = 0f;
    private int pendingStormSpawns = 0;
    private boolean stormHeaderWritten = false;
    /** Emitter id force-expired on the NEXT update dispatch (-1 = none). */
    private int stormKillEmitId = -1;
    /** Stress-test ceiling (user-set): 2^17 members. */
    private static final int STORM_MAX_COUNT = 131072;
    /** Vortex-mode cap on |omega| (rad/s). */
    private static final float STORM_MAX_OMEGA = 3.0f;
    private static final double STORM_WANDER_RADIUS = 12.0;
    private static final float STORM_MAX_SPEED = 6.0f;
    /**
     * The storm spec is deliberately unique (lifetime/speed/drag differ from
     * every preset) so spec dedupe hands it its own emitter id -- the per-id
     * header then carries the ANCHOR in slots 18/19, which specs themselves
     * never do (they stay position-free so one spec can serve many sites).
     */
    private static final EmitterSpec ALLAY_STORM_SPEC = EmitterSpec.builder()
            .shape(com.iridium126.createmanaindustry.client.particles.emitter.EmitterShape.POINT)
            .speed(2.5, 4.5)
            .life(3600, 3600) // immortal while active; stop expires them via uKillEmit
            .sizeOverLife(0.33, 0.33, 1.0)
            .gravity(0, 0, 0)
            .drag(0.6)
            .material(EmitterSpec.Material.MODEL)
            .animation(EmitterSpec.Animation.FLY)
            // boids steering alone lets members shear straight through walls;
            // REST keeps them sweeping against the occupancy volume (update.comp
            // only collides particles with collideMode > NONE)
            .collide(EmitterSpec.CollideMode.REST)
            .glow(1.0)
            .color(1f, 1f, 1f, 1f)
            .build();
    private long lastErrorTime = 0;
    private final GpuTimerRing computeTimer = new GpuTimerRing();
    private final GpuTimerRing drawTimer = new GpuTimerRing();
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
     * Starts a persistent boids-driven Allay Storm anchored at {@code origin}:
     * up to {@link #STORM_MAX_COUNT} members trickle in over a few frames,
     * flock as a bait ball around a wandering centre and live until
     * {@link #stopStorm()}, {@link #clear()} or level teardown.
     * Render-thread or thread-safe via the request queue like {@link #spawn}.
     */
    public void startStorm(Vec3 origin, int count, double radius, int mode, float omega) {
        this.pending.add(new StormReq(origin,
                Math.max(1, Math.min(STORM_MAX_COUNT, count)),
                Math.max(2.0, Math.min(64.0, radius)), // diameter cap 128
                mode == 2 ? 2 : 1,
                Math.max(-STORM_MAX_OMEGA, Math.min(STORM_MAX_OMEGA, omega))));
    }

    /** Stops the active storm: members expire this frame, no new spawns queue. */
    public void stopStorm() {
        this.pending.add(new StormStop());
    }

    /** Accumulated clamped simulation seconds (storm wander/burst clock). */
    public float timeSec() {
        return this.timeSec;
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
        this.computeTimer.free();
        this.drawTimer.free();
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

    /** Buffer management surface for the pack entity merge hook (same thread). */
    public ParticleBuffers gpuBuffers() {
        return this.gpu;
    }

    /** Buffer id of the newest committed translucent sort permutation (-1 = none yet). */
    public int lastFinalPermBufferId() {
        return this.lastFinalPermId;
    }

    /**
     * Frames elapsed since the newest committed sort permutation was generated
     * (diagnostic surface for {@code /cmip shaderpack status}; -1 before the
     * first sorted frame commits). Split-frame expectation: 0 for every
     * gbuffer-phase consumer, >= 1 only on the shadow track by design.
     */
    public int lastFinalPermAgeFrames() {
        if (this.lastFinalPermId < 0)
            return -1;
        return Math.max(0, this.simFrame - this.lastFinalPermFrameSim);
    }

    /** Lazily loads and returns the MODEL particle atlas texture id. */
    public int modelAtlasTextureId() {
        this.allayAtlas.ensureLoaded();
        return this.allayAtlas.textureId();
    }

    /** Called by the pack entity merge hook after it drew the MODEL segments itself. */
    public void markHookModelDrawn() {
        this.hookModelsDrawn = true;
    }

    /** Accumulates one completed hook-side timer query (GPU ms, lagged). */
    public synchronized void addExternalGpuMs(double ms) {
        this.externalHookGpuMs += Math.max(0.0, ms);
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
    // Frame hooks — RenderLevelStageEvent, SPLIT across two stages:
    //   AFTER_SKY   -> beginFrame  (compute: integrate/cull/sort/commit)
    //   AFTER_LEVEL -> endFrame    (draws: every billboard/model pass)
    //
    // The shader-pack merge hook fires mid-renderLevel (after regular
    // entities, before block entities) — strictly BETWEEN these two stages in
    // the same frame — so the committed permutation it binds was built against
    // THIS frame's camera: fast view rotation no longer shows entering MODEL
    // particles one generation late. Draws stay at AFTER_LEVEL because a
    // gbuffer-phase slot would leave sprites behind solid terrain depth.
    // Iris's shadow track runs BEFORE renderSky, ahead of every level-stage
    // event, so it keeps consuming the previous generation by design
    // (documented leave-open item, reported via shaderPackPermStatus).
    // ------------------------------------------------------------------

    /**
     * Phase C of the split frame. Drains requests, maintains collision bakes,
     * integrates particles, emits newcomers, runs keygen (+ the translucent
     * counting sort when needed), captures the census, arms the counter fence
     * and swaps the pool — committing a fresh generation. Nothing is drawn.
     */
    public void beginFrame(Camera camera, Matrix4fc view, Matrix4fc projectionMatrix, DeltaTracker deltaTracker) {
        this.frameAttempted = false;
        this.frameArmed = false;
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

        this.frameAttempted = true;
        this.frameStartNanos = System.nanoTime();
        try {
            runCompute(camera, view, projectionMatrix, deltaTracker);
        } catch (RuntimeException | LinkageError e) {
            long now = System.currentTimeMillis();
            if (now - this.lastErrorTime > 5000) {
                this.lastErrorTime = now;
                CreateManaIndustry.LOGGER.error("[CMI particles] compute phase failed", e);
            }
        }
    }

    /**
     * Phase D of the split frame: submits every draw pass (honouring the
     * merge-hook arbitration latch) and folds the lagged timer samples of BOTH
     * phases into the throttle. Always records costing whenever the compute
     * phase attempted, so throttle continuity survives aborted frames.
     */
    public void endFrame(Camera camera, Matrix4fc view, Matrix4fc projectionMatrix) {
        if (this.disabled || !ClientConfig.particleEnabled || !this.initialized || !this.frameAttempted)
            return;
        try {
            // An aborted compute phase leaves frameArmed clear; the last fully
            // committed generation simply persists one more frame undrawn.
            if (this.frameArmed)
                runDraws(camera, view, projectionMatrix);
        } finally {
            // Prefer the lagged GPU-side cost of BOTH phases (each ring reads a
            // 3-frame-old completed sample, summed); fall back to CPU submit
            // time across beginFrame..endFrame until samples flow. Hook-side
            // GPU work (shader-pack MODEL draw) is measured by its own timer
            // ring and folded in so the throttle sees the full per-frame cost.
            double baseMs;
            if (this.computeTimer.sampled || this.drawTimer.sampled)
                baseMs = this.computeTimer.lastMs + this.drawTimer.lastMs;
            else
                baseMs = (System.nanoTime() - this.frameStartNanos) / 1_000_000.0;
            synchronized (this) {
                baseMs += this.externalHookGpuMs;
                this.externalHookGpuMs = 0;
            }
            this.profiler.record(baseMs, ClientConfig.particleAutoThrottle);
            this.scale = this.profiler.emissionScale();
            this.frameAttempted = false;
            this.frameArmed = false;
        }
    }

    /** Clears every particle, stream and queued request (render thread only). */
    private void dropAll() {
        this.pending.clear();
        this.streams.clear();
        this.gpu.clearParticles();
        resetPoolState();
        resetStormState();
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

    private void runCompute(Camera camera, Matrix4fc view, Matrix4fc projectionMatrix, DeltaTracker deltaTracker) {
        this.frameSeed++;

        // Draw-phase handoff resets: stale values from an aborted frame never
        // reach runDraws — the frameArmed gate is set only at this phase's
        // success tail, but keeping the handoff consistent anyway makes every
        // consumer read a value that belongs to THIS invocation.
        this.frameEntryCount = 0;
        this.frameAliveEstimate = 0;
        this.frameSorted = false;
        this.frameFinalPerm = -1;

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
            } else if (item instanceof StormReq sr) {
                startStormInternal(sr.origin(), sr.count(), sr.radius(), sr.mode(), sr.omega());
            } else if (item instanceof StormStop) {
                stopStormInternal();
            } else if (item instanceof Boolean) {
                doClear = true;
            }
        }
        if (doClear) {
            this.streams.clear();
            this.gpu.clearParticles();
            resetPoolState();
            resetStormState();
        }

        int cap = this.gpu.capacity();
        if (!doClear)
            pollCounterSnapshot(cap);

        float dt = clampDelta(deltaTracker);
        this.timeSec += dt;

        // 2. Build emit entries (storm first, then bursts, then streams).
        int entryCount = 0;
        int totalSpawn = 0;

        // Storm trickle-in: at most MAX_EMIT_COMMANDS members per frame until
        // the requested population is reached. At 2048 the bait ball assembles
        // over ~8 frames (~0.4 s) -- reads as fish arriving, not popping in.
        // Storm upkeep every active frame, not just while trickle-spawning:
        // the first members of frame one draw in this very tick (so the allay
        // atlas must be live BEFORE emission), and a stray burst evicting a
        // storm volume via LRU must heal instead of silently stripping REST
        // collision. The swarm pins a dedicated 2x2 bake grid (+-48 block
        // footprint); routing it through ensureEmitterRuntime would squat an
        // extra off-grid centred slot the swarm never needs.
        if (this.stormActive) {
            this.allayAtlas.ensureLoaded();
            this.collisionBake.ensureQuadrants(this.stormAnchor);
        }
        if (this.stormActive && this.pendingStormSpawns > 0) {
            int id = ensureEmitter(ALLAY_STORM_SPEC);
            if (id >= 0 && entryCount < ParticleBuffers.MAX_EMIT_COMMANDS) {
                if (!this.stormHeaderWritten) {
                    // Anchor + storm parameters ride the per-id header slots 18/19
                    // (specs stay position-free; this spec dedupes to its own id).
                    float[] h = ALLAY_STORM_SPEC
                            .packedWithAnimation(EmitterSpec.Animation.FLY.index());
                    h[18 * 4 + 0] = this.stormOmega != 0f ? 2f : 1f; // motion mode
                    h[18 * 4 + 1] = (float) this.stormRadius;
                    h[18 * 4 + 2] = (float) STORM_WANDER_RADIUS;
                    h[18 * 4 + 3] = STORM_MAX_SPEED;
                    h[19 * 4 + 0] = (float) this.stormAnchor.x;
                    h[19 * 4 + 1] = (float) this.stormAnchor.y;
                    h[19 * 4 + 2] = (float) this.stormAnchor.z;
                    h[19 * 4 + 3] = 0f;
                    this.gpu.setEmitterHeader(id, h); // uploaded below this frame
                    this.stormEmitId = id;
                    this.stormHeaderWritten = true;
                }
                // drain in ~60 frames regardless of population: 131072 members
                // assemble in about one second instead of twenty-five
                int n = Math.min(this.pendingStormSpawns,
                        Math.max(ParticleBuffers.MAX_EMIT_COMMANDS,
                                (this.pendingStormSpawns + 59) / 60));
                this.emitIds[entryCount] = id;
                this.emitCounts[entryCount] = n;
                this.emitOrigins[entryCount] = this.stormAnchor;
                this.emitTranslucent[entryCount] = isTranslucent(ALLAY_STORM_SPEC);
                totalSpawn += n;
                entryCount++;
                this.pendingStormSpawns -= n;
            }
        }

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

        // Account for this frame's spawns BEFORE any GPU work is enqueued: GL
        // commands already issued keep executing even when a Java exception
        // later unwinds the frame, so the deltas must include them up front to
        // keep the snapshot+delta dispatch bound sound on the aborted-frame
        // path. Purely a hoist — every consumer below (updateBound,
        // aliveEstimate, translucentUpper) already saw these spawns included.
        this.spawnDelta += totalSpawn;
        this.translucentSpawnDelta += translucentSpawnTotal;
        // Handoff for runDraws' empty-guard: final count after free-pool capping.
        this.frameEntryCount = entryCount;

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
        this.frameSorted = sorted; // draw-phase handoff (see runDraws)

        // 5. Compute passes: reset -> [grid] -> update -> emit -> keygen
        //    (+ sort) -> capture. The elapsed-time query brackets ALL GPU work
        //    of THIS phase only; the draw phase runs its own ring (drawTimer)
        //    and endFrame sums both as the throttle input.
        // Upper bound on the read buffer's live count (snapshot + spawns since
        // — deaths only shrink it); update threads beyond the GPU-exact count
        // (read from the LAST COMMITTED frame's counter slot, binding 14 — see
        // lastGoodSlot) exit immediately.
        int updateBound = Math.min(cap, this.aliveKnown + this.spawnDelta + 64);
        this.computeTimer.ensureCreated();
        // From the timer-query begin to the phase-tail bookkeeping everything
        // runs under try/finally: a mid-phase failure ends the partial bracket,
        // restores program/VAO/SSBO/texture state, and skips the pool
        // swap — the last fully-written pool stays the next read source.
        boolean queryActive = false;
        try {
            this.computeTimer.begin();
            queryActive = true;
            GL20.glUseProgram(this.programs.reset());
            this.gpu.bindIndirect(2);
            this.gpu.bindCounter(3, slot);
            GL43.glDispatchCompute(1, 1, 1);
            GL42.glMemoryBarrier(GL43.GL_SHADER_STORAGE_BARRIER_BIT | GL42.GL_ATOMIC_COUNTER_BARRIER_BIT);

            // Boids spatial hash (storm swarms): built from the SAME committed
            // read pool + prev-counter slot update.comp consumes below. Gated to
            // frames where storm steering can actually execute: an active storm,
            // or a kill request still standing (a stop whose frame aborted
            // before the swap leaves its targets alive in the READ pool — the
            // re-fired uKillEmit below expires them the moment a frame commits,
            // and any thread reaching the storm blocks then finds THIS frame's
            // freshly built grid). Non-storm emitters carry motion mode 0 in
            // header slot 18 and never touch the structure, so skipping both the
            // heads clear and the dispatch on stormless frames is safe.
            if (this.stormActive || this.stormKillEmitId >= 0) {
                this.gpu.clearGridHeads();
                GL20.glUseProgram(this.programs.grid());
                this.gpu.bindParticleRead(0);
                this.gpu.bindPrevCounter(ParticleBuffers.PREVCOUNTER_BINDING, this.lastGoodSlot);
                this.gpu.bindEmitters(5);
                this.gpu.bindGrid();
                GL43.glDispatchCompute(Math.max(1, (updateBound + 63) / 64), 1, 1);
                GL42.glMemoryBarrier(GL43.GL_SHADER_STORAGE_BARRIER_BIT);
            }

            GL20.glUseProgram(this.programs.update());
            this.gpu.bindParticleRead(0);
            this.gpu.bindParticleWrite(1);
            this.gpu.bindGrid();
            this.gpu.bindCounter(3, slot);
            // NOT slot - 1: an aborted previous frame leaves its own counter
            // slot half-written; the read pool still belongs to the last
            // swapped (good) frame, so gate compaction on THAT frame's slot.
            this.gpu.bindPrevCounter(ParticleBuffers.PREVCOUNTER_BINDING, this.lastGoodSlot);
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
            setFloatUniform(this.programs.update(), "uTimeSec", this.timeSec);
            var pl = Minecraft.getInstance().player;
            if (pl != null) {
                var pp = pl.position();
                setFloatUniform(this.programs.update(), "uPlayerPos", (float) pp.x, (float) pp.y, (float) pp.z);
                setIntUniform(this.programs.update(), "uPlayerOn", 1);
            } else {
                setIntUniform(this.programs.update(), "uPlayerOn", 0);
            }
            setUIntUniform(this.programs.update(), "uKillEmit", this.stormKillEmitId);
            setFloatUniform(this.programs.update(), "uStormOmega", this.stormOmega);
            GL43.glDispatchCompute(Math.max(1, (updateBound + 63) / 64), 1, 1);
            GL42.glMemoryBarrier(GL43.GL_SHADER_STORAGE_BARRIER_BIT | GL42.GL_ATOMIC_COUNTER_BARRIER_BIT);
            // uKillEmit is retired at the SUCCESS TAIL (after the pool swap), not
            // here: an aborted frame discards its output pool, so a stop whose
            // kill was consumed by this dispatch must refire next frame or the
            // untouched storm members would survive on a never-rebuilt grid.

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

            // 6. keygen: GPU frustum cull + partition of both permutations. Runs in
            //    BOTH paths (it is the fast path's cull pass); it owns the draw
            //    counts, so update/emit never touch the indirect buffer.
            int finalPerm = -1;
            // spawnDelta now includes this frame's spawns; aliveEstimate is an
            // upper bound on the freshly written pool's live count
            int aliveEstimate = this.aliveKnown + this.spawnDelta;
            this.frameAliveEstimate = aliveEstimate; // draw-phase handoff
            if (aliveEstimate > 0 || entryCount > 0) {
                int sortUpper = Math.min(cap, Math.max(0, aliveEstimate + 64));

                // keygen: additive -> orderAdd[8], opaque sprites -> orderOpaque[15],
                // MODEL items -> sortData[7] lower partition (type bit 0),
                // ALPHA sprites -> sortData[7] upper partition (type bit 1)
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
                    // single counting-sort pass over ALL translucent items
                    // (MODEL parts + ALPHA sprites, one item per particle); the
                    // 9-bit key partitions the output by type as well as depth.
                    // Dispatch sized by the (possibly stale) census plus
                    // translucent spawns since — always an upper bound
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
            this.frameFinalPerm = finalPerm; // draw-phase handoff (-1 = fast path)

            // 7. Census capture + commit markers. NO DRAW SUBMISSION happens in
            //    this phase any more — the old "Draw" section moved to runDraws
            //    (AFTER_LEVEL), so on pack-active frames the merge hook fires
            //    BETWEEN this phase and those draws, mid-renderLevel, and binds
            //    THIS frame's camera-culled permutation (was: previous frame's,
            //    the one-frame entry-lag this split exists to remove). Draw
            //    ordering inside runDraws still puts every depth-writing pass
            //    first (OPAQUE cutout sprites, then ONE merged model multi-draw)
            //    so early-Z rejects occluded fragments of later blended passes;
            //    ALPHA sprites follow back-to-front through the combined sort;
            //    additive stays LAST — order-independent, and ghost depth
            //    occludes glow behind cloaks.
            //
            //    Capture this frame's UNculled translucent census
            //    (counter.translucentCensus, maintained by keygen before the
            //    frustum test) into the counter ring spare, then fence the
            //    phase: next frame POLLS the fence and only reads the counters
            //    once the GPU has finished writing them (a raw readback is a
            //    pipeline stall, however lagged the slot).
            GL20.glUseProgram(this.programs.capture());
            this.gpu.bindCounter(3, slot);
            // Publish N_model into the metadata tail slot of a sort buffer. When
            // this phase ran a sort, that is THE committed permutation buffer
            // (the same id lastFinalPermId promotes below), so count and items
            // stay structurally same-generation; on the fast path there are no
            // MODEL items at all and scratch buffer zero is a harmless dummy.
            GL30.glBindBufferBase(GL43.GL_SHADER_STORAGE_BUFFER,
                    ParticleBuffers.SORTWRITE_BINDING,
                    finalPerm >= 0 ? finalPerm : this.gpu.sortBuffer(0));
            setIntUniform(this.programs.capture(), "uMetaSlot", cap);
            GL43.glDispatchCompute(1, 1, 1);
            GL42.glMemoryBarrier(GL43.GL_SHADER_STORAGE_BARRIER_BIT | GL42.GL_ATOMIC_COUNTER_BARRIER_BIT);
            this.computeTimer.endBracket();
            queryActive = false;
            if (this.pendingFence != 0)
                GL32.glDeleteSync(this.pendingFence);
            this.pendingFence = GL32.glFenceSync(GL32.GL_SYNC_GPU_COMMANDS_COMPLETE, 0);
            this.pendingSlot = slot;

            // Collect the oldest sample in the compute ring (issued TIMER_RING-1
            // phases ago, virtually always complete): the true GPU-side cost of
            // this phase. Never blocks — skipped when the GPU runs that far
            // behind. The draw ring polls itself after runDraws; endFrame sums
            // both as the throttle input.
            this.computeTimer.rotateAndPoll();

            // 8. Swap the ping-pong pool — success only: an aborted phase keeps
            // the last fully-written pool as the next read source, and
            // the finally below restores the post-phase GL state instead.
            this.gpu.swap();
            // The swap committed this frame's output as the next read source —
            // only NOW does this frame's counter slot become the authoritative
            // live count for update.comp (see lastGoodSlot). Assigned after the
            // swap so an abort anywhere above can never promote a slot whose
            // pool was discarded.
            this.lastGoodSlot = slot;
            // Same success-only discipline: the sort permutation id is promoted
            // only when the frame fully committed (finalPerm is -1 on the fast
            // path, leaving the previous generation's binding in place). The
            // simFrame stamp beside it feeds the consumption-age diagnostic:
            // hooks run later in this SAME frame report age 0; the shadow track
            // (before renderSky, i.e. before any stage event) reports >= 1.
            if (finalPerm >= 0) {
                this.lastFinalPermId = finalPerm;
                this.lastFinalPermFrameSim = this.simFrame;
            }
            // Same success-only discipline retires uKillEmit: the just-swapped
            // pool is now the authoritative read source, so every kill target
            // provably compacted away. Until then the pending id keeps the
            // grid pass armed and refires the kill (see the update dispatch).
            this.stormKillEmitId = -1;

            // Compute phase committed in full: pool swapped, permutation
            // promoted, kill retired. Only NOW may the draw phase submit — any
            // failure above leaves frameArmed clear so runDraws is skipped and
            // the last fully-committed generation persists one more frame.
            this.frameArmed = true;
        } finally {
            if (queryActive) {
                // Mid-phase failure: end the partial bracket so the query object
                // stays reusable, and leave the ring cursor alone — the slot is
                // simply reused (its partial result overwritten) next phase
                // rather than feeding a bogus time into the throttle.
                try {
                    this.computeTimer.endBracket();
                } catch (RuntimeException | LinkageError ignoredCleanup) {
                    // context is going away; nothing further to do
                }
            }
            // Restore the exact post-phase state the success path leaves behind.
            // Blend/depth state is deliberately NOT touched here — the compute
            // phase issues no draws and cannot modify it; runDraws owns those
            // restores. Each group stays isolated: a secondary failure must not
            // mask the original exception nor skip the remaining groups.
            try {
                GL20.glUseProgram(0);
                GL30.glBindVertexArray(0);
            } catch (RuntimeException | LinkageError ignoredCleanup) {
                // see above
            }
            try {
                // SSBO bases 0-15 (permutations, sort data, counters, model geo…)
                this.gpu.unbindShaders();
            } catch (RuntimeException | LinkageError ignoredCleanup) {
                // see above
            }
            try {
                GL13.glActiveTexture(GL13.GL_TEXTURE0);
                GL11.glBindTexture(GL12.GL_TEXTURE_3D, 0);
                GL13.glActiveTexture(GL13.GL_TEXTURE1);
                GL11.glBindTexture(GL11.GL_TEXTURE_2D, 0);
                GL13.glActiveTexture(GL13.GL_TEXTURE0);
            } catch (RuntimeException | LinkageError ignoredCleanup) {
                // see above
            }
        }
    }

    /**
     * Phase D body (AFTER_LEVEL): submits the ENTIRE draw schedule. Ordering
     * rationale: every DEPTH-WRITING pass first (OPAQUE cutout sprites, then
     * ONE merged model multi-draw: opaque cutout segment -> translucent ghost
     * segment), so early-Z rejects occluded fragments of all later blended
     * passes. When translucent items exist, the combined sort runs and the
     * ALPHA sprites follow back-to-front (blend, no depth write; ghosts before
     * sprites is the documented tradeoff). Additive draws LAST: it is order-
     * independent, so position costs nothing but lets ghost depth occlude glow
     * behind cloaks.
     * <p>
     * Every draw reads the pool the compute phase just committed (write side,
     * unswapped until next frame's AFTER_SKY), plus the promotion of
     * {@code lastFinalPermId} that happened at compute-tail — on pack frames
     * the merge hook already drew the MODEL segments against THAT permutation
     * earlier this frame; its latch makes us skip our own drawModels so the
     * model geometry is submitted exactly once per frame regardless of path.
     */
    private void runDraws(Camera camera, Matrix4fc view, Matrix4fc projectionMatrix) {
        boolean queryActive = false;
        this.drawTimer.ensureCreated();
        try {
            this.drawTimer.begin();
            queryActive = true;

            // Repeat of the compute section's empty-guard from the handoff
            // values: nothing alive and nothing spawned skips vertex work while
            // keeping bindings correct for a valid buffer anyway.
            if (this.frameAliveEstimate > 0 || this.frameEntryCount > 0) {
                // Bind the FINAL sorted permutation up front: both translucent
                // draws (model ghost segment here, ALPHA sprites below) read their
                // own contiguous partition of it. On the fast path nothing reads
                // it, but the programs statically declare the sort SSBO, so keep a
                // valid buffer bound regardless.
                this.gpu.bindSort(ParticleBuffers.SORTWRITE_BINDING,
                        this.frameSorted ? this.frameFinalPerm : this.gpu.sortBuffer(0));
                this.gpu.bindOrderOpaque();
                drawPass(1, view, projectionMatrix, camera);
                if (this.hookModelsDrawn) {
                    // The pack entity merge hook drew the MODEL segments earlier
                    // this frame against the freshly promoted permutation;
                    // skipping ours prevents a double-draw (render arbitration).
                } else {
                    drawModels(view, projectionMatrix, camera);
                }
                if (this.frameSorted) {
                    drawPass(2, view, projectionMatrix, camera);
                }
                this.gpu.bindOrderAdd();
                drawPass(0, view, projectionMatrix, camera);
            }

            // Clear the merge-hook arbitration latch at the DRAW TAIL: the hook
            // fires mid-renderLevel — between our two phases within one frame —
            // and will set it again before endFrame's skip-check below runs.
            this.hookModelsDrawn = false;

            this.drawTimer.endBracket();
            queryActive = false;
            this.drawTimer.rotateAndPoll();
        } finally {
            if (queryActive) {
                // Same partial-bracket discipline as the compute phase.
                try {
                    this.drawTimer.endBracket();
                } catch (RuntimeException | LinkageError ignoredCleanup) {
                    // context is going away; nothing further to do
                }
            }
            try {
                GL20.glUseProgram(0);
                GL30.glBindVertexArray(0);
            } catch (RuntimeException | LinkageError ignoredCleanup) {
                // see above
            }
            try {
                // SSBO bases 0-15 (permutations, sort data, counters, model geo…)
                this.gpu.unbindShaders();
            } catch (RuntimeException | LinkageError ignoredCleanup) {
                // see above
            }
            try {
                GL13.glActiveTexture(GL13.GL_TEXTURE0);
                GL11.glBindTexture(GL12.GL_TEXTURE_3D, 0);
                GL13.glActiveTexture(GL13.GL_TEXTURE1);
                GL11.glBindTexture(GL11.GL_TEXTURE_2D, 0);
                GL13.glActiveTexture(GL13.GL_TEXTURE0);
            } catch (RuntimeException | LinkageError ignoredCleanup) {
                // see above
            }
            try {
                RenderSystem.depthMask(true);
                RenderSystem.defaultBlendFunc();
                RenderSystem.disableBlend();
            } catch (RuntimeException | LinkageError ignoredCleanup) {
                // see above
            }
        }
    }

    /**
     * Draws BOTH MODEL segments through ONE glMultiDrawElementsIndirect: the
     * opaque segment (cutout + depth writes) then the translucent cloak+wings
     * segment. Both commands cover the exact MODEL partition of the sorted
     * array (instanceCount = N_model, plain sortedKv[gl_InstanceID] fetch)
     * and differ only in their element-buffer index range, so no per-draw
     * uniform or attribute is needed (a baseInstance/divisor-1 selector was
     * tried here and REJECTED: instanced attribute fetch walks baseInstance +
     * instanceID, so with more than one model particle later instances read
     * wrong/OOB entries — adjacent baseInstances' fetch ranges overlap, which
     * no buffer content can disambiguate). The translucent segment blends
     * WITH depth writes:
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
        // Draw the NEWEST committed generation. Two mistakes have lived on this
        // line across the frame split — learn them both: (1) generation — after
        // the AFTER_SKY swap the fresh data lives on the read side, binding the
        // stale write side flickered the swarm; (2) binding POINT — the render
        // vsh block declares PARTICLE_BB_WRITE (misleadingly named "fresh
        // data"), so the buffer must attach AT THAT point even though its side
        // is the post-swap read one; attaching it at binding 0 left the
        // declared slot empty and made every L0 allay invisible. The pack path
        // was immune to both: its TBO view always pinned particleReadBufferId().
        this.gpu.bindNewestPool(ParticleBuffers.PARTICLE_BB_WRITE);
        this.gpu.bindEmitters(5);
        this.gpu.bindModelGeo(); // unbindShaders() clears binding 12 every frame
        this.gpu.bindVao();

        setMat4Uniform(prog, "ModelViewMat", view);
        setMat4Uniform(prog, "ProjMat", projectionMatrix);
        Vec3 pos = camera.getPosition();
        setFloatUniform(prog, "uCamPos", (float) pos.x, (float) pos.y, (float) pos.z);
        setFloatUniform(prog, "uFadeDist", (float) ClientConfig.particleFadeDistance);
        setFloatUniform(prog, "uTimeSec", this.timeSec);
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
     * blended sprite (walks the sprite partition of the type-partitioned
     * sort array, offset by the exact model count from cmd[IDX_CNT_MODELOP];
     * no depth write). Bucket 1 runs before every blended pass so its depth
     * writes feed early-Z; buckets 0/2 never write depth.
     */
    private void drawPass(int mode,
            Matrix4fc view, Matrix4fc projectionMatrix, Camera camera) {
        boolean textured = mode != 0;
        int prog = textured ? this.programs.texturedRender() : this.programs.render();
        if (prog == 0)
            return;
        GL20.glUseProgram(prog);
        // Newest committed generation at the binding POINT the vsh declares
        // (PARTICLE_BB_WRITE); generation and point selection rationale lives
        // on the drawModels twin of this line.
        this.gpu.bindNewestPool(ParticleBuffers.PARTICLE_BB_WRITE);
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

        if (mode == 2) {
            // textured.vsh mode 0 reads the ALPHA partition start from cmd[IDX_CNT_MODELOP]
            this.gpu.bindIndirect(ParticleBuffers.INDIRECT_BB);
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
    // ---- Allay Storm internals (render thread) ------------------------------

    private void startStormInternal(Vec3 origin, int count, double radius, int mode, float omega) {
        stopStormInternal(); // one storm at a time: the anchor rides a per-id header
        this.stormActive = true;
        this.stormAnchor = origin;
        this.stormRadius = radius;
        // handedness is a per-storm coin flip; the sign rides inside omega
        this.stormOmega = ((this.frameSeed & 1) == 0 ? 1f : -1f)
                * (mode == 2 ? omega : 0f);
        this.pendingStormSpawns = count;
        this.stormHeaderWritten = false;
    }

    private void stopStormInternal() {
        if (this.stormActive && this.stormEmitId >= 0)
            this.stormKillEmitId = this.stormEmitId; // members expire on the next update
        this.stormActive = false;
        this.stormOmega = 0f;
        this.pendingStormSpawns = 0;
    }

    /** Full storm teardown (level clear/shutdown): forget the emitter id too. */
    private void resetStormState() {
        this.stormActive = false;
        this.pendingStormSpawns = 0;
        this.stormHeaderWritten = false;
        this.stormEmitId = -1;
        this.stormKillEmitId = -1;
        this.stormOmega = 0f;
    }

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
