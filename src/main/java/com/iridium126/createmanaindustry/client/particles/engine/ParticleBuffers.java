package com.iridium126.createmanaindustry.client.particles.engine;

import java.nio.ByteBuffer;
import java.nio.FloatBuffer;

import com.iridium126.createmanaindustry.CreateManaIndustry;

import org.lwjgl.BufferUtils;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL15;
import org.lwjgl.opengl.GL30;
import org.lwjgl.opengl.GL40;
import org.lwjgl.opengl.GL43;

/**
 * Owns all GPU resources of the particle engine: the double-buffered particle
 * SSBOs (64 B/particle = 4 vec4), the emit-command ring, the emitter header
 * SSBO, the indirect draw command buffer and the counter buffer, plus the
 * packless vertex array used for the instanced draw.
 * <p>
 * All callers must be on the render thread with a current GL context. Lazily
 * created by {@code CMIParticleEngine} on the first frame; effective capacity
 * is capped by {@code GL_MAX_SHADER_STORAGE_BLOCK_SIZE}.
 */
public final class ParticleBuffers {

    public static final int VEC4_PER_PARTICLE = 4;  // 4 vec4 = 64 B per particle
    public static final int MAX_EMIT_COMMANDS = 256;
    public static final int EMIT_RING_SIZE = 3;
    public static final int VEC4_PER_EMITTER = 16;  // emitter header = 256 B
    /**
     * Counter-buffer ring. Each frame's counters live in one slot; the CPU reads
     * the slot written by the previous frame (1 frame old, producing no
     * read-after-write hazard against this frame's reset). 4 slots give the
     * previous frame's value plenty of margin before it is reused.
     */
    public static final int COUNTER_RING = 4;

    private static final int PARTICLE_BB_READ = 0;
    private static final int PARTICLE_BB_WRITE = 1;
    private static final int INDIRECT_BB = 2;
    private static final int COUNTER_BB = 3;
    private static final int EMIT_BB = 4;
    private static final int EMITTER_BB = 5;

    private final int[] particleSSBOs = new int[2];
    private final int[] emitSSBOs = new int[EMIT_RING_SIZE];
    private final int[] counterSSBOs = new int[COUNTER_RING];
    private int emitterSSBO = -1;
    private int indirectSSBO = -1;
    private int vao = -1;

    private int readIndex = 0;
    private int ringIndex = 0;
    private int capacity = 0;
    private int maxEmitters = 0;
    private boolean initialized = false;

    private float[] emitterMirror;
    private boolean emittersDirty = false;

    // Reusable scratch for tiny uploads: indirect command (4 ints) and counter
    // pair (2 ints); 32 bytes covers both safely.
    private final ByteBuffer tmp4 = BufferUtils.createByteBuffer(32);

    // Dedicated 4-byte read-back target: glGetBufferSubData reads exactly
    // buffer.remaining() bytes, so this buffer must be sized to the value width.
    private final ByteBuffer readTmp = BufferUtils.createByteBuffer(4);

    /**
     * Allocates all buffers. Fails (logs + returns false) if the GPU cannot
     * provide a usable SSBO capacity or the max-width it supports is tiny.
     */
    public boolean init(int maxParticles, int maxEmitters) {
        int maxSSBO = GL11.glGetInteger(GL43.GL_MAX_SHADER_STORAGE_BLOCK_SIZE);
        int cap = Math.min(maxParticles, Math.max(0, maxSSBO / (VEC4_PER_PARTICLE * 4)));
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
            this.particleSSBOs[i] = createBuffer(cap * VEC4_PER_PARTICLE * 4L, null);
        }
        for (int i = 0; i < EMIT_RING_SIZE; i++) {
            this.emitSSBOs[i] = createBuffer((long) MAX_EMIT_COMMANDS * 8 * 4, null);
        }
        this.emitterSSBO = createBuffer((long) maxEmitters * VEC4_PER_EMITTER * 4 * 4, null);
        this.indirectSSBO = createBuffer(16, null);
        for (int i = 0; i < COUNTER_RING; i++) {
            this.counterSSBOs[i] = createBuffer(16, null);
        }

        // initial indirect command: 6 vertices, 0 instances, first 0, baseInstance 0
        this.tmp4.clear();
        this.tmp4.putInt(6).putInt(0).putInt(0).putInt(0);
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

    /** Replaces the given emit-command ring slot contents (data already flipped). */
    public void uploadEmits(int bufferId, FloatBuffer data) {
        GL30.glBindBuffer(GL43.GL_SHADER_STORAGE_BUFFER, bufferId);
        GL15.glBufferSubData(GL43.GL_SHADER_STORAGE_BUFFER, 0, data);
    }

    /** Records one emitter header; uploaded lazily on the next frame. */
    public void setEmitterHeader(int id, float[] block64) {
        if (id < 0 || id >= this.maxEmitters)
            return;
        System.arraycopy(block64, 0, this.emitterMirror, id * VEC4_PER_EMITTER * 4, VEC4_PER_EMITTER * 4);
        this.emittersDirty = true;
    }

    /**
     * Uploads dirty emitter headers into the existing SSBO (a single 32 KB
     * re-upload of the mirror when anything changed; no delete/orphan churn).
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

    /** Reads back the live particle count stored in the given counter slot (previous frame). */
    public int readbackLive(int slot) {
        this.readTmp.clear();
        GL30.glBindBuffer(GL43.GL_SHADER_STORAGE_BUFFER, this.counterSSBOs[slot % COUNTER_RING]);
        GL15.glGetBufferSubData(GL43.GL_SHADER_STORAGE_BUFFER, 0, this.readTmp);
        // reads exactly 4 bytes (remaining()); absolute read avoids position juggling
        return this.readTmp.getInt(0);
    }

    /** Instantly drops every particle (all counter slots + instance count zeroed). */
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
        GL15.glBufferSubData(GL40.GL_DRAW_INDIRECT_BUFFER, 4, this.tmp4);
    }

    /**
     * Unbinds the engine's SSBO bases (0..5) so they don't linger bound into the
     * rest of the world's rendering after our frame.
     */
    public void unbindShaders() {
        for (int i = 0; i <= 5; i++) {
            GL30.glBindBufferBase(GL43.GL_SHADER_STORAGE_BUFFER, i, 0);
        }
    }

    public void bindDrawIndirect() {
        GL30.glBindBuffer(GL40.GL_DRAW_INDIRECT_BUFFER, this.indirectSSBO);
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
        this.initialized = false;
    }
}
