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
import org.lwjgl.opengl.GL20;
import org.lwjgl.opengl.GL30;
import org.lwjgl.opengl.GL40;
import org.lwjgl.opengl.GL42;
import org.lwjgl.opengl.GL43;
import org.lwjgl.system.MemoryStack;

/**
 * Client-side GPU particle engine (singleton), self-hosted GL.
 * <p>
 * Pure-GPU pipeline: a compute pass integrates/compacts the live particle pool
 * between two double-buffered SSBOs and appends newly requested particles; a
 * packless VAO draws instanced camera-facing billboards through
 * {@code glDrawArraysIndirect}, reading per-instance data by {@code gl_InstanceID}
 * from the freshly written SSBO. The CPU only writes tiny emit-command entries
 * each frame, and all GL programs are created from the mod's bundled GLSL with
 * raw LWJGL ({@link ParticlePrograms}) — no dependency on Veil's shader manager
 * (whose compute dispatches proved inert in this environment).
 * <p>
 * Frame hook: {@code RenderLevelStageEvent.AFTER_LEVEL} via the native NeoForge
 * event, so it works with or without Veil. (Iris shaderpack routing is a later
 * iteration — the vanilla/no-pack path is covered here.)
 */
public final class CMIParticleEngine {

    public static final CMIParticleEngine INSTANCE = new CMIParticleEngine();

    private static final int MAX_EMITTERS = 128;
    private static final int SAFETY_MARGIN = 2048;
    private static final float DEFAULT_FADE_DIST = 40.0f;

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
    private final List<Stream> streams = new ArrayList<>();
    private final ParticleBuffers gpu = new ParticleBuffers();
    private final ParticlePrograms programs = new ParticlePrograms();
    private final ParticleFrameProfiler profiler = new ParticleFrameProfiler();

    private final FloatBuffer emitFront = BufferUtils.createFloatBuffer(ParticleBuffers.MAX_EMIT_COMMANDS * 8);
    private final Vec3[] emitOrigins = new Vec3[ParticleBuffers.MAX_EMIT_COMMANDS];
    private final int[] emitIds = new int[ParticleBuffers.MAX_EMIT_COMMANDS];
    private final int[] emitCounts = new int[ParticleBuffers.MAX_EMIT_COMMANDS];

    private boolean initialized = false;
    private boolean disabled = false;
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
        this.pending.clear();
        this.streams.clear();
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

        // Counter ring: this frame writes slot `slot`; the read buffer's live
        // count was written by the previous frame, so we read that slot (never
        // the one we are about to reset, removing any read-after-write hazard).
        int slot = this.simFrame % ParticleBuffers.COUNTER_RING;
        int readSlot = (this.simFrame - 1 + ParticleBuffers.COUNTER_RING) % ParticleBuffers.COUNTER_RING;
        this.simFrame++;

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
            this.liveDisplay = 0;
        }

        // 2. Live count from last frame + budget/throttle.
        int cap = this.gpu.capacity();
        if (!doClear) {
            this.aliveRead = this.gpu.readbackLive(readSlot);
            if (this.aliveRead < 0 || this.aliveRead > cap)
                this.aliveRead = 0;
            this.liveDisplay = this.aliveRead;
        }

        float dt = clampDelta(deltaTracker);

        // 3. Build emit entries (bursts first, then streams).
        int entryCount = 0;
        int totalSpawn = 0;
        for (Burst b : bursts) {
            int id = ensureEmitter(b.spec);
            if (id < 0)
                continue;
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
            this.emitIds[entryCount] = id;
            this.emitCounts[entryCount] = n;
            this.emitOrigins[entryCount] = s.origin;
            totalSpawn += n;
            entryCount++;
        }

        this.streamCount = this.streams.size();

        // 4. Cap spawn request to the free pool (extra safety margin).
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

        // 5. Upload emit commands into the next ring slot.
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

        // 6. Compute passes: reset -> update -> emit.
        GL20.glUseProgram(this.programs.reset());
        this.gpu.bindIndirect(2);
        this.gpu.bindCounter(3, slot);
        GL43.glDispatchCompute(1, 1, 1);

        // reset's writeSlot=0 store must be visible to the update/emit atomics.
        GL42.glMemoryBarrier(GL43.GL_SHADER_STORAGE_BARRIER_BIT | GL42.GL_ATOMIC_COUNTER_BARRIER_BIT);

        GL20.glUseProgram(this.programs.update());
        this.gpu.bindParticleRead(0);
        this.gpu.bindParticleWrite(1);
        this.gpu.bindIndirect(2);
        this.gpu.bindCounter(3, slot);
        this.gpu.bindEmitters(5);
        setUIntUniform(this.programs.update(), "uAliveRead", this.aliveRead);
        setUIntUniform(this.programs.update(), "uCapacity", cap);
        setFloatUniform(this.programs.update(), "uDt", dt);
        GL43.glDispatchCompute(Math.max(1, (this.aliveRead + 63) / 64), 1, 1);

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
            GL43.glDispatchCompute(Math.max(1, (totalSpawn + 63) / 64), 1, 1);
        }

        GL42.glMemoryBarrier(GL43.GL_SHADER_STORAGE_BARRIER_BIT
                | GL42.GL_ATOMIC_COUNTER_BARRIER_BIT
                | GL42.GL_COMMAND_BARRIER_BIT);

        // 7. Draw the freshly written pool (also covers not-yet-synced live).
        if (this.aliveRead > 0 || entryCount > 0) {
            draw(view, projectionMatrix, camera);
        }
        // Leave no SSBO base bound into the rest of the world's rendering.
        this.gpu.unbindShaders();

        this.gpu.swap();
    }

    private void draw(Matrix4fc view, Matrix4fc projectionMatrix, Camera camera) {
        int prog = this.programs.render();
        if (prog == 0)
            return;
        GL20.glUseProgram(prog);
        // Draw the freshly updated pool — the WRITE buffer of this frame.
        this.gpu.bindParticleWrite(1);
        this.gpu.bindEmitters(5);
        this.gpu.bindVao();

        setMat4Uniform(prog, "ModelViewMat", view);
        setMat4Uniform(prog, "ProjMat", projectionMatrix);

        Vec3 pos = camera.getPosition();
        Vector3f up = camera.getUpVector();
        Vector3f left = camera.getLeftVector();
        // right = -left (Camera exposes the left vector; no right accessor)
        setFloatUniform(prog, "uCamPos", (float) pos.x, (float) pos.y, (float) pos.z);
        setFloatUniform(prog, "uCamRight", -left.x, -left.y, -left.z);
        setFloatUniform(prog, "uCamUp", up.x, up.y, up.z);
        setFloatUniform(prog, "uFadeDist", DEFAULT_FADE_DIST);
        setFloatUniform(prog, "uGlow", 1.0f);

        RenderSystem.enableBlend();
        RenderSystem.blendFuncSeparate(GL11.GL_ONE, GL11.GL_ONE, GL11.GL_ONE, GL11.GL_ONE);
        RenderSystem.enableDepthTest();
        RenderSystem.depthMask(false);

        this.gpu.bindDrawIndirect();
        GL40.glDrawArraysIndirect(GL11.GL_TRIANGLES, 0);

        RenderSystem.depthMask(true);
        RenderSystem.defaultBlendFunc();
        RenderSystem.disableBlend();
        GL20.glUseProgram(0);
        GL30.glBindVertexArray(0);
    }

    // ------------------------------------------------------------------
    // Private helpers
    // ------------------------------------------------------------------

    /**
     * Per-frame delta in REAL seconds. {@code getRealtimeDeltaTicks()} measures
     * actual wall-clock elapsed per frame (1 tick = 0.05 s); using it keeps both
     * the stream lifetime ("stream for N seconds" = N real seconds) and the
     * particle motion speed consistent regardless of FPS.
     * <p>
     * Do NOT use {@code getGameTimeDeltaPartialTick()} here — that returns the
     * tick-interpolation phase (0..1, the leftover fraction between two game
     * ticks), not a per-frame duration; it made stream lifetimes and speeds
     * FPS-dependent (a "100 s" stream could expire in ~50 s).
     */
    private static float clampDelta(DeltaTracker deltaTracker) {
        float ticks = deltaTracker.getRealtimeDeltaTicks();
        // 1 tick = 0.05 s; getRealtimeDeltaTicks() is already capped (0.5) after
        // very long stalls, so keep a generous upper clamp as a belt-and-braces.
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

    private static int loc(int prog, String name) {
        return GL20.glGetUniformLocation(prog, name);
    }

    private static void setUIntUniform(int prog, String name, int value) {
        int l = loc(prog, name);
        if (l >= 0)
            GL30.glUniform1ui(l, value);
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
