package com.iridium126.createmanaindustry.client.dimension.render;

import it.unimi.dsi.fastutil.ints.IntArrayList;
import it.unimi.dsi.fastutil.ints.IntList;
import it.unimi.dsi.fastutil.ints.IntOpenHashSet;
import it.unimi.dsi.fastutil.ints.IntSet;
import it.unimi.dsi.fastutil.longs.Long2IntOpenHashMap;
import net.minecraft.core.BlockPos;

/**
 * CPU-side registry + packed mirror of the ALLVR node SSBO (doc §7.2).
 * <p>
 * A node is 32 bytes (two uvec4). The first layout packed the cube position
 * as 21-bit-biased fields inside a.x/a.y and OVERLAPPED (42 bit of y+z in a
 * 32-bit uint) — the smoke test's node dump caught it (positions collapsed to
 * ±几千, every node beyond the far plane, terrain invisible). The corrected
 * layout stores the ABSOLUTE BLOCK origin as three signed int32 (±30M &lt;
 * 2³¹, no biasing) and moves level/flags into b.w's high bits:
 * <pre>
 * a.x = absBlockX (signed int32)      b.x = quadCount
 * a.y = absBlockY (signed int32)      b.y = visibleFrameId (GPU-written)
 * a.z = absBlockZ (signed int32)      b.z = quadStart (arena quad index)
 * a.w = childPtr (0 = none, 4c)       b.w = slot(17b) | level(3b)&lt;&lt;17 | flags(8b)&lt;&lt;20
 * </pre>
 * Mirror longs (little-endian → uint pairs): [a.x|a.y, a.z|a.w, b.x|b.y,
 * b.z|b.w]. Nodes are keyed by cube long; freed indices recycle via a free
 * list and keep {@code FLAG_DEAD} in the mirror until reused (the traversal
 * skips them — the dispatch covers {@code highWater}, not a compacted count).
 * <p>
 * All mutation happens on the render thread; the mirror is uploaded to GL by
 * {@code AllvrBuffers#syncNodes} from the dirty set (per-node 32 B chunks, or
 * a full re-upload when the capacity grew).
 */
public final class AllvrNodeStore {

    public static final int FLAG_HAS_MESH = 1;
    public static final int FLAG_DEAD = 2;

    /** b.w field packing — single source shared with chunks/node_common.glsl. */
    public static final int SLOT_BITS = 17;
    public static final int LEVEL_SHIFT = 17;
    public static final int FLAGS_SHIFT = 20;

    /** Hard ceiling (doc §7.4 node SSBO budget ≈ 2²¹ nodes). */
    public static final int MAX_NODES = 1 << 21;

    /** Longs per node in the mirror (8 uints). */
    public static final int LONGS_PER_NODE = 4;

    private long[] mirror = new long[1024 * LONGS_PER_NODE];
    private int capacity = 1024;
    private int highWater;
    private final Long2IntOpenHashMap byCubeKey = new Long2IntOpenHashMap();
    /**
     * LOD node maps, one per level 0..3 (doc §13 4c). Level maps are
     * load-bearing for correctness, not just organization: an L0 cell long
     * ALIASES a full-res cube long (same 21-bit packing), so a combined map
     * would collide the two node kinds.
     */
    private final Long2IntOpenHashMap[] lodByCubeKey = new Long2IntOpenHashMap[4];
    private final IntList freeIndices = new IntArrayList();
    private final IntSet dirty = new IntOpenHashSet();

    public AllvrNodeStore() {
        this.byCubeKey.defaultReturnValue(-1);
        for (int i = 0; i < this.lodByCubeKey.length; i++) {
            this.lodByCubeKey[i] = new Long2IntOpenHashMap();
            this.lodByCubeKey[i].defaultReturnValue(-1);
        }
    }

    public int capacity() {
        return this.capacity;
    }

    public int highWater() {
        return this.highWater;
    }

    public int nodeCount() {
        int n = this.byCubeKey.size();
        for (Long2IntOpenHashMap map : this.lodByCubeKey) {
            n += map.size();
        }
        return n;
    }

    public long[] mirror() {
        return this.mirror;
    }

    /** Node index for a cube key, or -1. */
    public int nodeOf(long cubeKey) {
        return this.byCubeKey.get(cubeKey);
    }

    /** Allocates (or returns) the node for a cube; -1 when the node space is full. */
    public int allocNode(long cubeKey, BlockPos cubeMinBlock) {
        return this.allocIn(this.byCubeKey, cubeKey, cubeMinBlock);
    }

    /** Allocates (or returns) the node for {@code key} in {@code map}. */
    private int allocIn(Long2IntOpenHashMap map, long key, BlockPos cubeMinBlock) {
        int existing = map.get(key);
        if (existing >= 0) {
            return existing;
        }
        int idx;
        if (!this.freeIndices.isEmpty()) {
            idx = this.freeIndices.removeInt(this.freeIndices.size() - 1);
        } else if (this.highWater < this.capacity) {
            idx = this.highWater++;
        } else if (this.capacity < MAX_NODES) {
            this.grow(this.capacity * 2);
            idx = this.highWater++;
        } else {
            return -1;
        }
        map.put(key, idx);
        this.writePosition(idx, cubeMinBlock);
        this.dirty.add(idx);
        return idx;
    }

    /** Fills the mesh fields (and HAS_MESH flag); allocates the node if absent. */
    public void setMesh(long cubeKey, BlockPos cubeMinBlock, int quadStart, int quadCount, int slot) {
        int idx = this.allocNode(cubeKey, cubeMinBlock);
        if (idx < 0) {
            return;
        }
        int o = idx * LONGS_PER_NODE;
        int word = packWord(slot, 0, FLAG_HAS_MESH);
        // b.x = quadCount (b.y stamp preserved), b.z = quadStart, b.w = word
        this.mirror[o + 2] = (this.mirror[o + 2] & 0xFFFFFFFF00000000L)
            | ((long) quadCount & 0xFFFFFFFFL);
        this.mirror[o + 3] = ((long) quadStart & 0xFFFFFFFFL) | ((long) word << 32);
        this.dirty.add(idx);
    }

    /** Marks the node dead and recycles its index. */
    public void freeNode(long cubeKey) {
        int idx = this.byCubeKey.remove(cubeKey);
        if (idx < 0) {
            return;
        }
        this.retire(idx);
    }

    /**
     * LOD variant of {@link #setMesh}: allocates/publishes the node in the
     * level's own map (an L0 cell long aliases full-res cube longs — see the
     * field doc) and stores {@code level} in the b.w word, which the vertex
     * shader reads back as the local-coordinate scale {@code 1 << level}.
     */
    public void setLodMesh(int level, long cellLong, BlockPos nodeMinBlock, int quadStart, int quadCount, int slot) {
        int idx = this.allocIn(this.lodByCubeKey[level], cellLong, nodeMinBlock);
        if (idx < 0) {
            return;
        }
        int o = idx * LONGS_PER_NODE;
        int word = packWord(slot, level, FLAG_HAS_MESH);
        this.mirror[o + 2] = (this.mirror[o + 2] & 0xFFFFFFFF00000000L)
            | ((long) quadCount & 0xFFFFFFFFL);
        this.mirror[o + 3] = ((long) quadStart & 0xFFFFFFFFL) | ((long) word << 32);
        this.dirty.add(idx);
    }

    /** Drops one LOD node (per-level map). No-op for never-published nodes. */
    public void freeLodNode(int level, long cellLong) {
        int idx = this.lodByCubeKey[level].remove(cellLong);
        if (idx < 0) {
            return;
        }
        this.retire(idx);
    }

    /** Marks a node dead and recycles its index (map entry already removed). */
    private void retire(int idx) {
        int o = idx * LONGS_PER_NODE;
        int word = (int) (this.mirror[o + 3] >>> 32);
        word |= FLAG_DEAD << FLAGS_SHIFT;
        this.mirror[o + 3] = (this.mirror[o + 3] & 0xFFFFFFFFL) | ((long) word << 32);
        this.dirty.add(idx);
        this.freeIndices.add(idx);
    }

    /** Drops every node (level unload / GPU-mode switch; rebuild via setMesh). */
    public void clear() {
        java.util.Arrays.fill(this.mirror, 0L);
        this.highWater = 0;
        this.byCubeKey.clear();
        for (Long2IntOpenHashMap map : this.lodByCubeKey) {
            map.clear();
        }
        this.freeIndices.clear();
        this.dirty.clear();
    }

    /** Takes the dirty set (drains it); fastutil's shared empty set when clean. */
    public IntSet takeDirty() {
        if (this.dirty.isEmpty()) {
            return it.unimi.dsi.fastutil.ints.IntSets.EMPTY_SET;
        }
        IntSet out = new IntOpenHashSet(this.dirty);
        this.dirty.clear();
        return out;
    }

    public void clearDirty() {
        this.dirty.clear();
    }

    private void grow(int newCapacity) {
        long[] grown = new long[newCapacity * LONGS_PER_NODE];
        System.arraycopy(this.mirror, 0, grown, 0, this.mirror.length);
        this.mirror = grown;
        this.capacity = newCapacity;
    }

    /** a.x/a.y/a.z = absolute block min corner (signed int32, no biasing). */
    private void writePosition(int idx, BlockPos cubeMinBlock) {
        int o = idx * LONGS_PER_NODE;
        long ax = (long) cubeMinBlock.getX() & 0xFFFFFFFFL;
        long ay = (long) cubeMinBlock.getY() & 0xFFFFFFFFL;
        long az = (long) cubeMinBlock.getZ() & 0xFFFFFFFFL;
        this.mirror[o] = ax | (ay << 32);
        this.mirror[o + 1] = az;          // a.w = childPtr 0
        this.mirror[o + 2] = 0L;          // b.x/b.y unset
        this.mirror[o + 3] = 0L;          // b.z/b.w unset
    }

    /** b.w packing: slot | level&lt;&lt;LEVEL_SHIFT | flags&lt;&lt;FLAGS_SHIFT (≤ 27 bits). */
    public static int packWord(int slot, int level, int flags) {
        return (slot & ((1 << SLOT_BITS) - 1)) | ((level & 7) << LEVEL_SHIFT) | (flags << FLAGS_SHIFT);
    }
}
