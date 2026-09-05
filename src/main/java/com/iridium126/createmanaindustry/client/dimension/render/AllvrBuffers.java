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
import static org.lwjgl.opengl.GL43.GL_DISPATCH_INDIRECT_BUFFER;
import static org.lwjgl.opengl.GL40.GL_DRAW_INDIRECT_BUFFER;
import static org.lwjgl.opengl.GL43.GL_SHADER_STORAGE_BUFFER;
import static org.lwjgl.opengl.GL43.glMultiDrawElementsIndirect;

import org.lwjgl.opengl.ARBIndirectParameters;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL12;
import org.lwjgl.opengl.GL13;
import org.lwjgl.opengl.GL15;
import org.lwjgl.opengl.GL30;
import org.lwjgl.opengl.GL31;
import org.lwjgl.opengl.GL42;
import org.lwjgl.opengl.GL43;
import org.lwjgl.opengl.GL44;
import org.lwjgl.opengl.GL46;

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
    public static final int BIND_NODES = 3;
    public static final int BIND_QUEUE = 4;
    public static final int BIND_CMD_COUNT = 5;
    public static final int BIND_DISPATCH = 6;
    public static final int BIND_COMMANDS = 7;

    public static final int MAX_QUADS_PER_COMMAND = 8192; // ≥ 32²×6 worst case
    public static final int COMMAND_STRIDE = 5;           // uints per MDI command
    /**
     * 4a GPU path: cmdgen chunk-splits oversized cubes into consecutive
     * commands, so the cap no longer bounds per-cube geometry — it bounds the
     * whole visible command stream (65536 × 20 B = 1.28 MB, one-time alloc).
     */
    public static final int MAX_COMMANDS = 1 << 16;
    public static final int MAX_SLOTS = 65536;
    /**
     * Queue entries = node indices, two temporal segments (doc §9.2 two-phase
     * occlusion): [0] = last-frame-visible counter, [1] = newly-visible
     * counter, [2..2+CAP) = phase-1 entries, [2+CAP..2+2CAP) = phase-2
     * entries — cmdgen emits phase-1 commands first so their depth lands
     * before the new segments rasterize.
     */
    public static final int QUEUE_CAPACITY = 1 << 16;
    public static final int QUEUE_UINTS = 2 + 2 * QUEUE_CAPACITY;
    /** HiZ pyramid mip levels (static; extra levels are clamped at sample time). */
    public static final int HIZ_LEVELS = 12;
    /** Sampler units for the HiZ chain and the borrowed MC main depth. */
    public static final int HIZ_UNIT = 3;
    public static final int MC_DEPTH_UNIT = 4;

    private static final int COMMAND_BYTES = COMMAND_STRIDE * 4;

    private int vao;
    private int indexBuffer;
    private int arenaBuffer;
    private int cubeInfoBuffer;
    private int commandBuffer;
    private int stateTbo;
    private int stateTableBuffer;

    // GPU-cull path (4a): node tree mirror, visible queue, cmdgen dispatch
    // params, and the MDIC draw count (GL_PARAMETER_BUFFER; cmdgen atomicAdds it).
    private int nodeBuffer;
    private int nodeCapacity;        // nodes, matches the store's mirror capacity
    private int queueBuffer;
    private int dispatchBuffer;
    private int commandCountBuffer;

    // HiZ pyramid (4b): R32F mip chain at half main-target resolution, rebuilt
    // from the borrowed MC main depth after each terrain draw.
    private int hizTexture;
    private int hizWidth;
    private int hizHeight;
    /** Actual allocated mip count — glTexStorage2D rejects levels beyond
     *  1+floor(log2(maxDim)); an oversized request voids the whole texture
     *  (incomplete → samples 0.0 → HiZ would cull everything). */
    private int hizLevels;

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

    // ------------------------------------------------------------------
    // GPU-cull buffers (4a)
    // ------------------------------------------------------------------

    /** Allocates the compute-side buffers once; sizes are static in 4a. */
    public void ensureGpuCull() {
        if (this.nodeBuffer != 0) {
            return;
        }
        this.nodeBuffer = glGenBuffers();
        this.queueBuffer = glGenBuffers();
        this.dispatchBuffer = glGenBuffers();
        this.commandCountBuffer = glGenBuffers();

        glBindBuffer(GL_SHADER_STORAGE_BUFFER, this.queueBuffer);
        glBufferData(GL_SHADER_STORAGE_BUFFER, 4L * QUEUE_UINTS, GL_DYNAMIC_DRAW);
        glBindBuffer(GL_SHADER_STORAGE_BUFFER, this.dispatchBuffer);
        glBufferData(GL_SHADER_STORAGE_BUFFER, 16L, GL_DYNAMIC_DRAW);
        glBindBuffer(GL_SHADER_STORAGE_BUFFER, this.commandCountBuffer);
        glBufferData(GL_SHADER_STORAGE_BUFFER, 4L, GL_DYNAMIC_DRAW);
        glBindBuffer(GL_SHADER_STORAGE_BUFFER, 0);
    }

    public boolean gpuCullReady() {
        return this.nodeBuffer != 0;
    }

    /** Debug-readback access (5 s throttle, debug log gated — never per frame). */
    public int queueBuffer() {
        return this.queueBuffer;
    }

    /** Debug-readback access (5 s throttle, debug log gated — never per frame). */
    public int nodeBuffer() {
        return this.nodeBuffer;
    }

    /** Debug-readback access (5 s throttle, debug log gated — never per frame). */
    public int commandCountBuffer() {
        return this.commandCountBuffer;
    }

    /** Debug-readback access (5 s throttle, debug log gated — never per frame). */
    public int commandBuffer() {
        return this.commandBuffer;
    }

    public int nodeCapacity() {
        return this.nodeCapacity;
    }

    /** Forces the next {@link #syncNodes} to a full re-upload. Call after
     *  {@link AllvrNodeStore#clear()}: with the capacity unchanged the
     *  dirty-set path uploads nothing, so the GPU buffer keeps the previous
     *  level's live-looking nodes and the traversal's over-dispatch tail
     *  draws them as ghosts (HAS_MESH + stale arena offsets → garbage
     *  geometry until a capacity growth happens to re-upload everything). */
    public void invalidateNodeUpload() {
        this.nodeCapacity = -1;
    }

    /** Bump-pointer tail position, quads (debug command triage only). */
    public long arenaUsedQuads() {
        return this.arenaUsed;
    }

    /**
     * Uploads node mirror changes: a full re-upload when the store grew, else
     * per-node 32 B chunks for the drained dirty set. Render thread only.
     */
    public void syncNodes(AllvrNodeStore store) {
        this.ensureGpuCull();
        if (this.nodeCapacity != store.capacity()) {
            glBindBuffer(GL_SHADER_STORAGE_BUFFER, this.nodeBuffer);
            glBufferData(GL_SHADER_STORAGE_BUFFER, 32L * store.capacity(), GL_DYNAMIC_DRAW);
            glBufferSubData(GL_SHADER_STORAGE_BUFFER, 0, store.mirror());
            this.nodeCapacity = store.capacity();
            store.clearDirty();
            return;
        }
        it.unimi.dsi.fastutil.ints.IntSet dirty = store.takeDirty();
        if (dirty.isEmpty()) {
            return;
        }
        long[] chunk = new long[AllvrNodeStore.LONGS_PER_NODE];
        glBindBuffer(GL_SHADER_STORAGE_BUFFER, this.nodeBuffer);
        for (int idx : dirty) {
            System.arraycopy(store.mirror(), idx * AllvrNodeStore.LONGS_PER_NODE, chunk, 0, chunk.length);
            glBufferSubData(GL_SHADER_STORAGE_BUFFER, 32L * idx, chunk);
        }
    }

    // ------------------------------------------------------------------
    // HiZ pyramid (4b)
    // ------------------------------------------------------------------

    /** Allocates (or re-allocates on resize) the half-res R32F mip pyramid,
     *  cleared to 1.0 (far plane) — an unpopulated pyramid conservatively
     *  occludes nothing. Mip count is clamped to the size's legal maximum;
     *  an allocation failure self-checks and drops the texture so the caller
     *  degrades to frustum-only instead of sampling an incomplete texture. */
    public void allocHiz(int width, int height) {
        if (this.hizTexture != 0) {
            GL11.glDeleteTextures(this.hizTexture);
            this.hizTexture = 0;
        }
        this.hizWidth = width;
        this.hizHeight = height;
        this.hizLevels = 0;
        int w = Math.max(1, (width + 1) / 2);
        int h = Math.max(1, (height + 1) / 2);
        int maxDim = Math.max(w, h);
        int allowedLevels = 1 + (31 - Integer.numberOfLeadingZeros(maxDim));
        int levels = Math.min(HIZ_LEVELS, allowedLevels);
        this.hizTexture = GL11.glGenTextures();
        GL11.glBindTexture(GL11.GL_TEXTURE_2D, this.hizTexture);
        GL42.glTexStorage2D(GL11.GL_TEXTURE_2D, levels, GL30.GL_R32F, w, h);
        // allocation self-check: an incomplete texture samples as 0.0 (near
        // plane) — that culls the whole world, so fail loudly to frustum-only
        boolean ok = GL11.glGetError() == 0;
        if (ok) {
            GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MIN_FILTER,
                GL11.GL_NEAREST_MIPMAP_NEAREST);
            GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MAG_FILTER, GL11.GL_NEAREST);
            GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_WRAP_S, GL12.GL_CLAMP_TO_EDGE);
            GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_WRAP_T, GL12.GL_CLAMP_TO_EDGE);
            float[] far = {1.0f};
            for (int level = 0; level < levels; level++) {
                GL44.glClearTexImage(this.hizTexture, level, GL11.GL_RED, GL11.GL_FLOAT, far);
            }
            this.hizLevels = levels;
        } else {
            // drop the generated-but-incomplete texture so the id doesn't
            // linger bound-and-garbage until the next allocation attempt
            GL11.glDeleteTextures(this.hizTexture);
            this.hizTexture = 0;
            com.iridium126.createmanaindustry.CreateManaIndustry.LOGGER.error(
                "[Allvr] HiZ pyramid allocation failed ({}x{} levels {}) — HiZ disabled, frustum only",
                w, h, levels);
        }
        GL11.glBindTexture(GL11.GL_TEXTURE_2D, 0);
    }

    public boolean hizReady() {
        return this.hizTexture != 0 && this.hizLevels > 0;
    }

    /** Actual mip count of the allocated pyramid (shader top-level clamp). */
    public int hizLevels() {
        return this.hizLevels;
    }

    public int hizWidth() {
        return this.hizWidth;
    }

    public int hizHeight() {
        return this.hizHeight;
    }

    public int hizTexture() {
        return this.hizTexture;
    }

    /** Level dims of the pyramid: level 0 = half main-target res. */
    public static int hizLevelDim(int baseHalfRes, int level) {
        return Math.max(1, baseHalfRes >> level);
    }

    /** Binds the SSBO bases + GL_PARAMETER_BUFFER + dispatch-indirect source. */
    public void bindGpuCull() {
        GL30.glBindBufferBase(GL_SHADER_STORAGE_BUFFER, AllvrShaderCache.BIND_NODES, this.nodeBuffer);
        GL30.glBindBufferBase(GL_SHADER_STORAGE_BUFFER, AllvrShaderCache.BIND_QUEUE, this.queueBuffer);
        GL30.glBindBufferBase(GL_SHADER_STORAGE_BUFFER, AllvrShaderCache.BIND_CMD_COUNT, this.commandCountBuffer);
        GL30.glBindBufferBase(GL_SHADER_STORAGE_BUFFER, AllvrShaderCache.BIND_DISPATCH, this.dispatchBuffer);
        GL30.glBindBufferBase(GL_SHADER_STORAGE_BUFFER, AllvrShaderCache.BIND_COMMANDS, this.commandBuffer);
        GL15.glBindBuffer(ARBIndirectParameters.GL_PARAMETER_BUFFER_ARB, this.commandCountBuffer);
        glBindBuffer(GL_DISPATCH_INDIRECT_BUFFER, this.dispatchBuffer);
    }

    /** cmdgen dispatch: size comes from the queue counter (over-dispatch ≤63 idle groups). */
    public void dispatchCmdgenIndirect() {
        GL43.glDispatchComputeIndirect(0L);
    }

    /**
     * MDIC draw: the actual command count lives in the GL_PARAMETER_BUFFER
     * (cmdgen's atomicAdd) — the CPU never reads it back (doc §9.4).
     */
    public void drawIndirectCount(boolean coreGl46) {
        if (coreGl46) {
            GL46.glMultiDrawElementsIndirectCount(GL_TRIANGLES, GL_UNSIGNED_INT, 0L, 0L, MAX_COMMANDS,
                COMMAND_STRIDE * 4);
        } else {
            org.lwjgl.opengl.ARBIndirectParameters.glMultiDrawElementsIndirectCountARB(
                GL_TRIANGLES, GL_UNSIGNED_INT, 0L, 0L, MAX_COMMANDS, COMMAND_STRIDE * 4);
        }
    }

    /** Releases GPU-cull bindings (mirror of {@link #unbind}'s discipline). */
    public void unbindGpuCull() {
        GL30.glBindBufferBase(GL_SHADER_STORAGE_BUFFER, AllvrShaderCache.BIND_NODES, 0);
        GL30.glBindBufferBase(GL_SHADER_STORAGE_BUFFER, AllvrShaderCache.BIND_QUEUE, 0);
        GL30.glBindBufferBase(GL_SHADER_STORAGE_BUFFER, AllvrShaderCache.BIND_CMD_COUNT, 0);
        GL30.glBindBufferBase(GL_SHADER_STORAGE_BUFFER, AllvrShaderCache.BIND_DISPATCH, 0);
        GL30.glBindBufferBase(GL_SHADER_STORAGE_BUFFER, AllvrShaderCache.BIND_COMMANDS, 0);
        GL15.glBindBuffer(ARBIndirectParameters.GL_PARAMETER_BUFFER_ARB, 0);
        glBindBuffer(GL_DISPATCH_INDIRECT_BUFFER, 0);
        glBindBuffer(GL_SHADER_STORAGE_BUFFER, 0);
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

    /** Same test as {@link #allocRange}'s success condition: a contiguous free
     *  range, or untouched tail headroom, of at least {@code size} quads.
     *  {@code allocRange} can still fail after a true return only if the arena
     *  changed in between — the deferred-retry path in {@code AllvrRenderer}
     *  uses this to avoid retry-spin under fragmentation. */
    public boolean canFit(long size) {
        for (long[] r : this.freeRanges) {
            if (r[1] >= size) {
                return true;
            }
        }
        return this.arenaUsed + size <= this.arenaQuads;
    }

    /** Frees a range, coalescing with adjacent free ranges and reclaiming the
     *  bump-pointer tail — without this, cube remesh churn fragments the arena
     *  until even single-cube allocations fail. */
    public void freeRange(int start, int size) {
        if (size <= 0) {
            return;
        }
        long end = start + (long) size;
        for (int i = 0; i < this.freeRanges.size(); ) {
            long[] r = this.freeRanges.get(i);
            if (r[0] + r[1] == start) {
                // adjacent range ends where this begins — absorb it
                start = (int) r[0];
                size += (int) r[1];
                this.freeRanges.remove(i);
            } else if (r[0] == end) {
                // this ends where the adjacent range begins — absorb it
                end = r[0] + r[1];
                size += (int) r[1];
                this.freeRanges.remove(i);
            } else {
                i++;
            }
        }
        if (end == this.arenaUsed) {
            // touches the unallocated tail — shrink it instead of tracking
            this.arenaUsed = start;
        } else {
            this.freeRanges.add(new long[] {start, size});
        }
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
        uploadStateTable();
        this.stateTboEntries = entries;
    }

    /** Uploads the state TBO even when the entry count didn't grow — used
     *  when the customId column was re-resolved (pack switch, new ids). */
    public void invalidateStateTable() {
        this.stateTboEntries = 0;
    }

    private void uploadStateTable() {
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
