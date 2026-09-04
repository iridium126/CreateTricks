package com.iridium126.createmanaindustry.client.dimension.render;

import static org.lwjgl.opengl.GL11.GL_TRIANGLES;
import static org.lwjgl.opengl.GL11.GL_UNSIGNED_INT;
import static org.lwjgl.opengl.GL15.GL_ARRAY_BUFFER;
import static org.lwjgl.opengl.GL15.GL_DYNAMIC_DRAW;
import static org.lwjgl.opengl.GL15.GL_ELEMENT_ARRAY_BUFFER;
import static org.lwjgl.opengl.GL15.GL_STATIC_DRAW;
import static org.lwjgl.opengl.GL15.glBindBuffer;
import static org.lwjgl.opengl.GL15.glBufferData;
import static org.lwjgl.opengl.GL15.glBufferSubData;
import static org.lwjgl.opengl.GL15.glDeleteBuffers;
import static org.lwjgl.opengl.GL15.glGenBuffers;
import static org.lwjgl.opengl.GL31.GL_COPY_READ_BUFFER;
import static org.lwjgl.opengl.GL30.glBindVertexArray;
import static org.lwjgl.opengl.GL30.glDeleteVertexArrays;
import static org.lwjgl.opengl.GL30.glGenVertexArrays;
import static org.lwjgl.opengl.GL31.GL_TEXTURE_BUFFER;
import static org.lwjgl.opengl.GL31.glCopyBufferSubData;
import static org.lwjgl.opengl.GL40.GL_DRAW_INDIRECT_BUFFER;
import static org.lwjgl.opengl.GL43.GL_SHADER_STORAGE_BUFFER;
import static org.lwjgl.opengl.GL43.glMultiDrawElementsIndirect;

import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL13;
import org.lwjgl.opengl.GL30;
import org.lwjgl.opengl.GL31;

/**
 * V0 GPU store for the ALLVR terrain pass (doc §7 / §9.4 Tier B): the packed
 * 8-byte quad arena (SSBO), the per-cube-slot origin table (SSBO, absolute
 * int coords — the shader does camera-relative math in integer space, doc §9.5),
 * the render-state material table (RGBA32F texture buffer), one shared
 * relative index pattern, and the per-frame MDI command buffer.
 * <p>
 * Command geometry: {@code firstIndex=0, baseVertex=quadStart*4,
 * baseInstance=cubeSlot} — the vertex shader resolves the quad via
 * {@code (gl_VertexID - gl_BaseVertex) >> 2 + (gl_BaseVertex >> 2)} and the
 * origin via {@code gl_BaseInstance}, so per-cube data never reaches the quad
 * stream. Requires GL 4.6 (or GL 4.5 + ARB_shader_draw_parameters) — the
 * {@code AllvrRenderer} capability gate refuses lower tiers (doc §6.2 Tier C).
 */
public final class AllvrBuffers {

    /** Binding constants — single source of truth for the GLSL prelude. */
    public static final int BIND_QUADS = 0;
    public static final int BIND_CUBEINFO = 1;
    /** Texture unit of the state material TBO. */
    public static final int STATE_TBO_UNIT = 2;

    public static final int MAX_QUADS_PER_COMMAND = 8192; // ≥ 32²×6 worst case
    public static final int COMMAND_STRIDE = 5;           // uints per MDI command
    public static final int MAX_COMMANDS = 8192;
    public static final int MAX_SLOTS = 65536;

    private static final int COMMAND_BYTES = COMMAND_STRIDE * 4;

    private int vao;
    private int indexBuffer;
    private int arenaBuffer;
    private int cubeInfoBuffer;
    private int commandBuffer;
    private int stateTbo;
    private int stateTableBuffer;

    private long arenaQuads;         // current capacity, quads
    private long arenaUsed;          // bump pointer, quads
    /** Free arena ranges {start, size} — first-fit on alloc. */
    private final java.util.ArrayList<long[]> freeRanges = new java.util.ArrayList<>();
    private final int[] freeSlots = new int[MAX_SLOTS];
    private int freeSlotCount;
    private int nextSlot = 1;        // 0 reserved as "no slot"
    private int stateTboEntries;     // entries packed into the TBO

    public void ensure() {
        if (this.vao != 0) {
            return;
        }
        this.vao = glGenVertexArrays();
        glBindVertexArray(this.vao);

        // shared relative index pattern: quad k → 4k+0,4k+1,4k+2,4k+2,4k+1,4k+3
        int[] indices = new int[MAX_QUADS_PER_COMMAND * 6];
        for (int q = 0; q < MAX_QUADS_PER_COMMAND; q++) {
            int o = q * 6;
            int b = q * 4;
            indices[o] = b;
            indices[o + 1] = b + 1;
            indices[o + 2] = b + 2;
            indices[o + 3] = b + 2;
            indices[o + 4] = b + 1;
            indices[o + 5] = b + 3;
        }
        this.indexBuffer = glGenBuffers();
        glBindBuffer(GL_ELEMENT_ARRAY_BUFFER, this.indexBuffer);
        glBufferData(GL_ELEMENT_ARRAY_BUFFER, indices, GL_STATIC_DRAW);

        glBindVertexArray(0);

        this.arenaBuffer = glGenBuffers();
        this.cubeInfoBuffer = glGenBuffers();
        this.commandBuffer = glGenBuffers();

        growArena(1 << 20);
        glBindBuffer(GL_SHADER_STORAGE_BUFFER, this.cubeInfoBuffer);
        glBufferData(GL_SHADER_STORAGE_BUFFER, 16L * MAX_SLOTS, GL_DYNAMIC_DRAW);
        glBindBuffer(GL_DRAW_INDIRECT_BUFFER, this.commandBuffer);
        glBufferData(GL_DRAW_INDIRECT_BUFFER, (long) COMMAND_BYTES * MAX_COMMANDS, GL_DYNAMIC_DRAW);

        this.freeSlotCount = 0;
        this.nextSlot = 1;
        this.arenaUsed = 0;
        this.freeRanges.clear();
        this.stateTboEntries = 0;
    }

    public boolean ready() {
        return this.vao != 0;
    }

    /** Grows the arena, preserving content. Cap 64M quads (512 MB). */
    private void growArena(long minQuads) {
        long newQuads = Math.min(1L << 26, Math.max(minQuads, this.arenaQuads * 2));
        int old = this.arenaBuffer;
        int created = glGenBuffers();
        glBindBuffer(GL_SHADER_STORAGE_BUFFER, created);
        glBufferData(GL_SHADER_STORAGE_BUFFER, newQuads << 3, GL_DYNAMIC_DRAW);
        if (this.arenaQuads != 0 && old != 0) {
            glBindBuffer(GL_COPY_READ_BUFFER, old);
            glCopyBufferSubData(GL_COPY_READ_BUFFER, GL_SHADER_STORAGE_BUFFER, 0, 0, this.arenaUsed << 3);
        }
        glBindBuffer(GL_COPY_READ_BUFFER, 0);
        if (old != 0) {
            glDeleteBuffers(old);
        }
        this.arenaBuffer = created;
        this.arenaQuads = newQuads;
    }

    // ------------------------------------------------------------------
    // slots (cube info table)
    // ------------------------------------------------------------------

    /** Allocates a slot and writes the cube's absolute origin. -1 when full. */
    public int allocSlot(int x, int y, int z) {
        int slot;
        if (this.freeSlotCount > 0) {
            slot = this.freeSlots[--this.freeSlotCount];
        } else if (this.nextSlot < MAX_SLOTS) {
            slot = this.nextSlot++;
        } else {
            return -1;
        }
        glBindBuffer(GL_SHADER_STORAGE_BUFFER, this.cubeInfoBuffer);
        // NB: the shader declares this table as ivec4 — the data MUST be
        // written as int bit patterns. A float[] write read as
        // 1181224960-style garbage ints sent every vertex outside the clip
        // volume (the "terrain invisible in every config" bug).
        glBufferSubData(GL_SHADER_STORAGE_BUFFER, 16L * slot,
            new int[] {x, y, z, 1});
        return slot;
    }

    public void freeSlot(int slot) {
        if (slot > 0) {
            this.freeSlots[this.freeSlotCount++] = slot;
        }
    }

    // ------------------------------------------------------------------
    // quad arena
    // ------------------------------------------------------------------

    /** First-fit range of {@code size} quads; -1 when the arena must grow. */
    public int allocRange(long size) {
        for (int i = 0; i < this.freeRanges.size(); i++) {
            long[] r = this.freeRanges.get(i);
            if (r[1] == size) {
                this.freeRanges.remove(i);
                return (int) r[0];
            }
            if (r[1] > size) {
                r[1] -= size;
                return (int) (r[0] + r[1]);
            }
        }
        if (this.arenaUsed + size > this.arenaQuads) {
            growArena(this.arenaUsed + size);
            if (this.arenaUsed + size > this.arenaQuads) {
                return -1; // cap reached
            }
        }
        int start = (int) this.arenaUsed;
        this.arenaUsed += size;
        return start;
    }

    public void freeRange(int start, int size) {
        if (size <= 0) {
            return;
        }
        this.freeRanges.add(new long[] {start, size});
    }

    /** Uploads {@code quads} at arena offset {@code start} (render thread). */
    public void uploadQuads(int start, long[] quads) {
        glBindBuffer(GL_SHADER_STORAGE_BUFFER, this.arenaBuffer);
        glBufferSubData(GL_SHADER_STORAGE_BUFFER, (long) start << 3, quads);
    }

    // ------------------------------------------------------------------
    // state material TBO
    // ------------------------------------------------------------------

    /** Re-creates the state TBO when the id table outgrew it. */
    public void ensureStateTable(int entries) {
        if (entries <= this.stateTboEntries) {
            return;
        }
        float[] packed = AllvrRenderStateMap.packedTable();
        if (this.stateTableBuffer == 0) {
            this.stateTableBuffer = glGenBuffers();
        }
        glBindBuffer(GL_ARRAY_BUFFER, this.stateTableBuffer);
        glBufferData(GL_ARRAY_BUFFER, packed, GL_DYNAMIC_DRAW);
        glBindBuffer(GL_ARRAY_BUFFER, 0);
        if (this.stateTbo == 0) {
            this.stateTbo = GL11.glGenTextures();
        }
        GL11.glBindTexture(GL_TEXTURE_BUFFER, this.stateTbo);
        GL31.glTexBuffer(GL_TEXTURE_BUFFER, GL30.GL_RGBA32F, this.stateTableBuffer);
        GL11.glBindTexture(GL_TEXTURE_BUFFER, 0);
        this.stateTboEntries = entries;
    }

    // ------------------------------------------------------------------
    // per-frame commands + draw
    // ------------------------------------------------------------------

    /** Uploads {@code n} packed MDI commands ({@code commands} = 5 uints each). */
    public void uploadCommands(int[] commands, int n) {
        glBindBuffer(GL_DRAW_INDIRECT_BUFFER, this.commandBuffer);
        glBufferSubData(GL_DRAW_INDIRECT_BUFFER, 0, java.util.Arrays.copyOf(commands, n * COMMAND_STRIDE));
    }

    /** Binds everything the terrain program needs except the program itself. */
    public void bindForDraw() {
        glBindBuffer(GL_SHADER_STORAGE_BUFFER, this.arenaBuffer);
        GL30.glBindBufferBase(GL_SHADER_STORAGE_BUFFER, AllvrShaderCache.BIND_QUADS, this.arenaBuffer);
        GL30.glBindBufferBase(GL_SHADER_STORAGE_BUFFER, AllvrShaderCache.BIND_CUBEINFO, this.cubeInfoBuffer);
        GL13.glActiveTexture(GL13.GL_TEXTURE0 + STATE_TBO_UNIT);
        GL11.glBindTexture(GL_TEXTURE_BUFFER, this.stateTbo);
        GL13.glActiveTexture(GL13.GL_TEXTURE0);
        glBindVertexArray(this.vao);
        glBindBuffer(GL_DRAW_INDIRECT_BUFFER, this.commandBuffer);
    }

    /** One glMultiDrawElementsIndirect over {@code n} commands. */
    public void draw(int n) {
        glMultiDrawElementsIndirect(GL_TRIANGLES, GL_UNSIGNED_INT, 0L, n, COMMAND_STRIDE * 4);
    }

    /** GL state off-departure discipline (mirrors the particle engine). */
    public void unbind() {
        glBindBuffer(GL_DRAW_INDIRECT_BUFFER, 0);
        glBindVertexArray(0);
        GL30.glBindBufferBase(GL_SHADER_STORAGE_BUFFER, AllvrShaderCache.BIND_QUADS, 0);
        GL30.glBindBufferBase(GL_SHADER_STORAGE_BUFFER, AllvrShaderCache.BIND_CUBEINFO, 0);
        GL13.glActiveTexture(GL13.GL_TEXTURE0 + STATE_TBO_UNIT);
        GL11.glBindTexture(GL_TEXTURE_BUFFER, 0);
        GL13.glActiveTexture(GL13.GL_TEXTURE0);
        glBindBuffer(GL_SHADER_STORAGE_BUFFER, 0);
    }

    /** Drops every cube's geometry (level unload); GL objects persist. */
    public void reset() {
        this.arenaUsed = 0;
        this.freeRanges.clear();
        this.freeSlotCount = 0;
        this.nextSlot = 1;
    }

    public void destroy() {
        if (this.vao != 0) {
            glDeleteVertexArrays(this.vao);
            this.vao = 0;
        }
        for (int b : new int[] {this.indexBuffer, this.arenaBuffer, this.cubeInfoBuffer,
                this.commandBuffer, this.stateTableBuffer}) {
            if (b != 0) {
                glDeleteBuffers(b);
            }
        }
        if (this.stateTbo != 0) {
            GL11.glDeleteTextures(this.stateTbo);
            this.stateTbo = 0;
        }
        this.indexBuffer = this.arenaBuffer = this.cubeInfoBuffer = this.commandBuffer = this.stateTableBuffer = 0;
        this.stateTboEntries = 0;
        this.reset();
    }

    AllvrBuffers() {
    }
}
