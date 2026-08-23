package com.iridium126.createmanaindustry.client.particles.engine;

import java.nio.ByteBuffer;
import java.nio.FloatBuffer;

import com.iridium126.createmanaindustry.CreateManaIndustry;
import com.iridium126.createmanaindustry.client.particles.emitter.EmitterSpec;

import org.lwjgl.BufferUtils;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL15;
import org.lwjgl.opengl.GL30;
import org.lwjgl.opengl.GL40;
import org.lwjgl.opengl.GL43;

/**
 * Owns all GPU resources of the particle engine: the double-buffered particle
 * SSBOs (64 B/particle = 4 vec4), the emit-command ring, the emitter header
 * SSBO, the indirect draw command buffer (two commands: additive + sorted alpha),
 * the counter ring, the counting-sort data/histogram/offset buffers, and the
 * collision bake-meta SSBO — plus the packless vertex array used for the
 * instanced draw.
 * <p>
 * All callers must be on the render thread with a current GL context. Lazily
 * created by {@code CMIParticleEngine} on the first frame; effective capacity
 * is capped by {@code GL_MAX_SHADER_STORAGE_BLOCK_SIZE}.
 */
public final class ParticleBuffers {

    public static final int VEC4_PER_PARTICLE = 4;  // 4 vec4 = 16 floats
    /** Bytes per particle in the double-buffered particle SSBOs (4 vec4 × 4 floats × 4 B). */
    public static final int BYTES_PER_PARTICLE = VEC4_PER_PARTICLE * 4 * 4; // 64 B
    public static final int MAX_EMIT_COMMANDS = 256;
    public static final int EMIT_RING_SIZE = 3;
    /** Emitter header in vec4 — mirrors EmitterSpec.VEC4_PER_EMITTER. */
    public static final int VEC4_PER_EMITTER = EmitterSpec.VEC4_PER_EMITTER;
    /**
     * Counter-buffer ring. Each frame's counters live in one slot; the CPU reads
     * the slot written by the previous frame (1 frame old, producing no
     * read-after-write hazard against this frame's reset). 4 slots give the
     * previous frame's value plenty of margin before it is reused.
     */
    public static final int COUNTER_RING = 4;
    /** Number of draw commands in the indirect buffer (additive, alpha, model). */
    public static final int INDIRECT_COMMANDS = 3;
    /** Counting-sort passes over the 8-bit depth-band key (one, by design). */
    public static final int RADIX_PASSES = 1;
    /** Radix digit size (bins). */
    public static final int RADIX_BINS = 256;

    private static final int PARTICLE_BB_READ = 0;
    private static final int PARTICLE_BB_WRITE = 1;
    private static final int INDIRECT_BB = 2;
    private static final int COUNTER_BB = 3;
    private static final int EMIT_BB = 4;
    private static final int EMITTER_BB = 5;
    // 6/7 = LSD radix sort data (read/write), rebound per pass
    // 8 = additive-only permutation (orderAdd) for the additive draw
    // 9 = radix histogram, 10 = radix offsets, 11 = collision bake meta
    public static final int SORTREAD_BINDING = 6;
    public static final int SORTWRITE_BINDING = 7;
    public static final int ORDERADD_BINDING = 8;
    public static final int HIST_BINDING = 9;
    public static final int OFFSET_BINDING = 10;
    public static final int BAKEMETA_BINDING = 11;
    /** Static baked model geometry (flat float array, MODEL particles). */
    public static final int MODELGEO_BINDING = 12;
    /** Dense permutation of visible MODEL particles (keygen's third bucket). */
    public static final int ORDERMODEL_BINDING = 13;

    private final int[] particleSSBOs = new int[2];
    private final int[] emitSSBOs = new int[EMIT_RING_SIZE];
    private final int[] counterSSBOs = new int[COUNTER_RING];
    private final int[] sortSSBOs = new int[2];
    private int emitterSSBO = -1;
    private int orderAddSSBO = -1;
    private int indirectSSBO = -1;
    private int histSSBO = -1;
    private int offsetSSBO = -1;
    private int bakeMetaSSBO = -1;
    private int modelGeoSSBO = -1;
    private int orderModelSSBO = -1;
    private int vao = -1;

    private int readIndex = 0;
    private int ringIndex = 0;
    private int capacity = 0;
    private int maxEmitters = 0;
    private boolean initialized = false;

    private float[] emitterMirror;
    private boolean emittersDirty = false;

    // Reusable scratch for tiny uploads: the initial indirect payload is the
    // largest writer (INDIRECT_COMMANDS x 16 B = 48 B); 64 B covers it and the
    // counter pairs with headroom.
    private final ByteBuffer tmp4 = BufferUtils.createByteBuffer(64);
    private final ByteBuffer zero1024 = BufferUtils.createByteBuffer(1024);
    /** Matches the "major.minor" prefix of a GL_VERSION string. */
    private static final java.util.regex.Pattern GL_VERSION_PATTERN =
            java.util.regex.Pattern.compile("(\\d+)\\.(\\d+)");

    // Dedicated read-back targets: glGetBufferSubData reads exactly
    // buffer.remaining() bytes, so these must be sized to the value widths.
    private final ByteBuffer readTmp = BufferUtils.createByteBuffer(4);
    private final ByteBuffer readTmp8 = BufferUtils.createByteBuffer(8);

    /**
     * Allocates all buffers. Fails (logs + returns false) if the GPU cannot
     * provide a usable SSBO capacity or the max-width it supports is tiny.
     */
    public boolean init(int maxParticles, int maxEmitters) {
        // The pipeline needs OpenGL 4.3 (compute shaders + robust SSBOs). On an
        // older context the SSBO-size query below would just return 0/garbage —
        // fail with a clear message instead so the engine disables cleanly.
        String glVersion = GL11.glGetString(GL11.GL_VERSION);
        if (glVersion != null) {
            java.util.regex.Matcher m = GL_VERSION_PATTERN.matcher(glVersion);
            if (m.find()) {
                int major = Integer.parseInt(m.group(1));
                int minor = Integer.parseInt(m.group(2));
                if (major < 4 || (major == 4 && minor < 3)) {
                    CreateManaIndustry.LOGGER.warn(
                            "[CMI particles] OpenGL {}.{} found, compute shaders need 4.3+; engine disabled", major, minor);
                    return false;
                }
            }
        }
        int maxSSBO = GL11.glGetInteger(GL43.GL_MAX_SHADER_STORAGE_BLOCK_SIZE);
        int cap = Math.min(maxParticles, Math.max(0, maxSSBO / BYTES_PER_PARTICLE));
        if (cap < 1000) {
            CreateManaIndustry.LOGGER.warn(
                    "[CMI particles] SSBO max size {} B too small for a usable particle pool (started {}); engine disabled",
                    maxSSBO, cap);
            return false;
        }
        this.capacity = cap;
        this.maxEmitters = maxEmitters;
        this.emitterMirror = new float[maxEmitters * VEC4_PER_EMITTER * 4];

        this.vao = GL30.glGenVertexArrays();
        for (int i = 0; i < 2; i++) {
            this.particleSSBOs[i] = createBuffer(cap * (long) BYTES_PER_PARTICLE, null);
        }
        for (int i = 0; i < EMIT_RING_SIZE; i++) {
            this.emitSSBOs[i] = createBuffer((long) MAX_EMIT_COMMANDS * 8 * 4, null);
        }
        this.emitterSSBO = createBuffer((long) maxEmitters * VEC4_PER_EMITTER * 4 * 4, null);
        this.indirectSSBO = createBuffer((long) INDIRECT_COMMANDS * 16, null);
        for (int i = 0; i < COUNTER_RING; i++) {
            this.counterSSBOs[i] = createBuffer(16, null);
        }
        // Radix sort data: (key, order) per ALPHA particle, double-buffered.
        for (int i = 0; i < 2; i++) {
            this.sortSSBOs[i] = createBuffer(cap * 8L, null);
        }
        // Additive permutation (dense, uint per additive particle).
        this.orderAddSSBO = createBuffer(cap * 4L, null);
        // Model permutation (dense, uint per visible MODEL particle).
        this.orderModelSSBO = createBuffer(cap * 4L, null);
        this.histSSBO = createBuffer((long) RADIX_BINS * 4, null);
        this.offsetSSBO = createBuffer((long) RADIX_BINS * 4, null);
        this.bakeMetaSSBO = createBuffer(CollisionBake.MAX_SLICES * 16L, null);

        // initial indirect payload: two draw commands (6 verts / 0 inst each)
        this.tmp4.clear();
        for (int i = 0; i < INDIRECT_COMMANDS; i++) {
            this.tmp4.putInt(6).putInt(0).putInt(0).putInt(0);
        }
        this.tmp4.flip();
        GL30.glBindBuffer(GL40.GL_DRAW_INDIRECT_BUFFER, this.indirectSSBO);
        GL15.glBufferSubData(GL40.GL_DRAW_INDIRECT_BUFFER, 0, this.tmp4);

        // zero all counter slots
        this.tmp4.clear();
        this.tmp4.putInt(0).putInt(0);
        this.tmp4.flip();
        for (int id : this.counterSSBOs) {
            GL30.glBindBuffer(GL43.GL_SHADER_STORAGE_BUFFER, id);
            GL15.glBufferSubData(GL43.GL_SHADER_STORAGE_BUFFER, 0, this.tmp4);
        }

        this.initialized = true;
        return true;
    }

    private static int createBuffer(long sizeBytes, FloatBuffer data) {
        int id = GL15.glGenBuffers();
        GL30.glBindBuffer(GL43.GL_SHADER_STORAGE_BUFFER, id);
        if (data == null) {
            GL15.glBufferData(GL43.GL_SHADER_STORAGE_BUFFER, sizeBytes, GL15.GL_DYNAMIC_DRAW);
        } else {
            GL15.glBufferData(GL43.GL_SHADER_STORAGE_BUFFER, data, GL15.GL_DYNAMIC_DRAW);
        }
        return id;
    }

    public boolean initialized() {
        return this.initialized;
    }

    public int capacity() {
        return this.capacity;
    }

    public int maxEmitters() {
        return this.maxEmitters;
    }

    public int readIndex() {
        return this.readIndex;
    }

    public int writeIndex() {
        return 1 - this.readIndex;
    }

    /** Call after a full frame: the freshly written buffer becomes the next read source. */
    public void swap() {
        this.readIndex = 1 - this.readIndex;
    }

    public void bindParticleRead(int binding) {
        GL30.glBindBufferBase(GL43.GL_SHADER_STORAGE_BUFFER, binding, this.particleSSBOs[this.readIndex]);
    }

    public void bindParticleWrite(int binding) {
        GL30.glBindBufferBase(GL43.GL_SHADER_STORAGE_BUFFER, binding, this.particleSSBOs[1 - this.readIndex]);
    }

    public void bindIndirect(int binding) {
        GL30.glBindBufferBase(GL43.GL_SHADER_STORAGE_BUFFER, binding, this.indirectSSBO);
    }

    public void bindCounter(int binding, int slot) {
        GL30.glBindBufferBase(GL43.GL_SHADER_STORAGE_BUFFER, binding, this.counterSSBOs[slot % COUNTER_RING]);
    }

    public void bindEmitters(int binding) {
        GL30.glBindBufferBase(GL43.GL_SHADER_STORAGE_BUFFER, binding, this.emitterSSBO);
    }

    /** Advances the emit-command ring and returns the next buffer id. */
    public int nextEmitBuffer() {
        this.ringIndex = (this.ringIndex + 1) % EMIT_RING_SIZE;
        return this.emitSSBOs[this.ringIndex];
    }

    public void bindEmitBuffer(int binding, int bufferId) {
        GL30.glBindBufferBase(GL43.GL_SHADER_STORAGE_BUFFER, binding, bufferId);
    }

    public void bindSort(int binding, int sortBufferId) {
        GL30.glBindBufferBase(GL43.GL_SHADER_STORAGE_BUFFER, binding, sortBufferId);
    }

    /** Physical GL buffer id of one of the two radix sort data buffers. */
    public int sortBuffer(int i) {
        return this.sortSSBOs[i];
    }

    /** Binds the additive-permutation buffer at its fixed binding for keygen/draw. */
    public void bindOrderAdd() {
        GL30.glBindBufferBase(GL43.GL_SHADER_STORAGE_BUFFER, ORDERADD_BINDING, this.orderAddSSBO);
    }

    /** Binds the model-permutation buffer at its fixed binding for keygen/draw. */
    public void bindOrderModel() {
        GL30.glBindBufferBase(GL43.GL_SHADER_STORAGE_BUFFER, ORDERMODEL_BINDING, this.orderModelSSBO);
    }

    /** Binds the static model geometry for the model draw pass (must be re-bound every frame — see {@link #unbindShaders()}). */
    public void bindModelGeo() {
        GL30.glBindBufferBase(GL43.GL_SHADER_STORAGE_BUFFER, MODELGEO_BINDING, this.modelGeoSSBO);
    }

    /**
     * Uploads the static baked model geometry (flat float array, see
     * {@link AllayModelGeometry#VERTEX_FLOATS} stride) once after init, and
     * rewrites draw command 2's vertexCount to the baked vertex count (the
     * model pass instances a fixed mesh, not 6-vertex billboards).
     */
    public void uploadModelGeometry(float[] baked) {
        try (var stack = org.lwjgl.system.MemoryStack.stackPush()) {
            FloatBuffer buf = stack.mallocFloat(baked.length);
            buf.put(baked).flip();
            this.modelGeoSSBO = createBuffer(4L * baked.length, buf);
        }
        GL30.glBindBufferBase(GL43.GL_SHADER_STORAGE_BUFFER, MODELGEO_BINDING, this.modelGeoSSBO);

        this.tmp4.clear();
        this.tmp4.putInt(baked.length / AllayModelGeometry.VERTEX_FLOATS).putInt(0).putInt(0).putInt(0);
        this.tmp4.flip();
        GL30.glBindBuffer(GL40.GL_DRAW_INDIRECT_BUFFER, this.indirectSSBO);
        GL15.glBufferSubData(GL40.GL_DRAW_INDIRECT_BUFFER, 2L * 16, this.tmp4);
    }

    public void bindHist() {
        GL30.glBindBufferBase(GL43.GL_SHADER_STORAGE_BUFFER, HIST_BINDING, this.histSSBO);
    }

    public void bindOffsets() {
        GL30.glBindBufferBase(GL43.GL_SHADER_STORAGE_BUFFER, OFFSET_BINDING, this.offsetSSBO);
    }

    public void bindBakeMeta() {
        GL30.glBindBufferBase(GL43.GL_SHADER_STORAGE_BUFFER, BAKEMETA_BINDING, this.bakeMetaSSBO);
    }

    /** Uploads the collision bake-meta array to its SSBO. */
    public void uploadBakeMeta(float[] meta) {
        try (var stack = org.lwjgl.system.MemoryStack.stackPush()) {
            FloatBuffer buf = stack.mallocFloat(meta.length);
            buf.put(meta).flip();
            GL30.glBindBuffer(GL43.GL_SHADER_STORAGE_BUFFER, this.bakeMetaSSBO);
            GL15.glBufferSubData(GL43.GL_SHADER_STORAGE_BUFFER, 0, buf);
        }
    }

    /** Zeroes the radix histogram (1 KB) before each pass. */
    public void clearHist() {
        GL30.glBindBuffer(GL43.GL_SHADER_STORAGE_BUFFER, this.histSSBO);
        GL15.glBufferSubData(GL43.GL_SHADER_STORAGE_BUFFER, 0, this.zero1024);
    }

    /**
     * Reads the previous frame's counters from the given ring slot in one
     * non-blocking (lagged) 8-byte readback: {@code {writeSlot, spare}} =
     * {@code {liveCount, alphaCensus}}. The alpha census is UNculled (every
     * live alpha particle, off-screen included) — keygen counts it before the
     * frustum test and {@code capture.comp} copies it into {@code spare} at
     * the end of each frame.
     */
    public int[] readbackCounts(int slot) {
        this.readTmp8.clear();
        GL30.glBindBuffer(GL43.GL_SHADER_STORAGE_BUFFER, this.counterSSBOs[slot % COUNTER_RING]);
        GL15.glGetBufferSubData(GL43.GL_SHADER_STORAGE_BUFFER, 0, this.readTmp8);
        return new int[] { this.readTmp8.getInt(0), this.readTmp8.getInt(4) };
    }

    /** Replaces the given emit-command ring slot contents (data already flipped). */
    public void uploadEmits(int bufferId, FloatBuffer data) {
        GL30.glBindBuffer(GL43.GL_SHADER_STORAGE_BUFFER, bufferId);
        GL15.glBufferSubData(GL43.GL_SHADER_STORAGE_BUFFER, 0, data);
    }

    /** Records one emitter header; uploaded lazily on the next frame. */
    public void setEmitterHeader(int id, float[] block) {
        if (id < 0 || id >= this.maxEmitters)
            return;
        System.arraycopy(block, 0, this.emitterMirror, id * VEC4_PER_EMITTER * 4, VEC4_PER_EMITTER * 4);
        this.emittersDirty = true;
    }

    /** Re-writes one emitter header (e.g. a collision bake index changed). */
    public void updateEmitterHeader(int id, float[] block) {
        setEmitterHeader(id, block);
    }

    /**
     * Uploads dirty emitter headers into the existing SSBO (a single re-upload of
     * the mirror when anything changed; no delete/orphan churn).
     */
    public void uploadDirtyEmitters() {
        if (!this.emittersDirty)
            return;
        this.emittersDirty = false;
        try (var stack = org.lwjgl.system.MemoryStack.stackPush()) {
            FloatBuffer buf = stack.mallocFloat(this.maxEmitters * VEC4_PER_EMITTER * 4);
            buf.put(this.emitterMirror).flip();
            GL30.glBindBuffer(GL43.GL_SHADER_STORAGE_BUFFER, this.emitterSSBO);
            GL15.glBufferSubData(GL43.GL_SHADER_STORAGE_BUFFER, 0, buf);
        }
    }

    /** Instantly drops every particle (all counter slots + instance counts zeroed). */
    public void clearParticles() {
        this.tmp4.clear();
        this.tmp4.putInt(0).putInt(0);
        this.tmp4.flip();
        for (int id : this.counterSSBOs) {
            GL30.glBindBuffer(GL43.GL_SHADER_STORAGE_BUFFER, id);
            GL15.glBufferSubData(GL43.GL_SHADER_STORAGE_BUFFER, 0, this.tmp4);
        }

        this.tmp4.clear();
        this.tmp4.putInt(0);
        this.tmp4.flip();
        GL30.glBindBuffer(GL40.GL_DRAW_INDIRECT_BUFFER, this.indirectSSBO);
        for (int i = 0; i < INDIRECT_COMMANDS; i++) {
            GL15.glBufferSubData(GL40.GL_DRAW_INDIRECT_BUFFER, i * 16 + 4, this.tmp4);
        }
    }

    /**
     * Unbinds the engine's SSBO bases so they don't linger bound into the rest of
     * the world's rendering after our frame.
     */
    public void unbindShaders() {
        for (int i = 0; i <= 13; i++) {
            GL30.glBindBufferBase(GL43.GL_SHADER_STORAGE_BUFFER, i, 0);
        }
    }

    public void bindDrawIndirect() {
        GL30.glBindBuffer(GL40.GL_DRAW_INDIRECT_BUFFER, this.indirectSSBO);
    }

    /** Draws draw-command {@code cmd} (offset in the indirect buffer). */
    public void drawIndirect(int cmd) {
        GL40.glDrawArraysIndirect(GL11.GL_TRIANGLES, cmd * 16L);
    }

    public void bindVao() {
        GL30.glBindVertexArray(this.vao);
    }

    public void free() {
        if (this.vao >= 0)
            GL30.glDeleteVertexArrays(this.vao);
        for (int id : this.particleSSBOs)
            if (id > 0)
                GL15.glDeleteBuffers(id);
        for (int id : this.emitSSBOs)
            if (id > 0)
                GL15.glDeleteBuffers(id);
        if (this.emitterSSBO > 0)
            GL15.glDeleteBuffers(this.emitterSSBO);
        if (this.indirectSSBO > 0)
            GL15.glDeleteBuffers(this.indirectSSBO);
        for (int id : this.counterSSBOs)
            if (id > 0)
                GL15.glDeleteBuffers(id);
        for (int id : this.sortSSBOs)
            if (id > 0)
                GL15.glDeleteBuffers(id);
        if (this.orderAddSSBO > 0)
            GL15.glDeleteBuffers(this.orderAddSSBO);
        if (this.orderModelSSBO > 0)
            GL15.glDeleteBuffers(this.orderModelSSBO);
        if (this.modelGeoSSBO > 0)
            GL15.glDeleteBuffers(this.modelGeoSSBO);
        if (this.histSSBO > 0)
            GL15.glDeleteBuffers(this.histSSBO);
        if (this.offsetSSBO > 0)
            GL15.glDeleteBuffers(this.offsetSSBO);
        if (this.bakeMetaSSBO > 0)
            GL15.glDeleteBuffers(this.bakeMetaSSBO);
        this.initialized = false;
    }
}
