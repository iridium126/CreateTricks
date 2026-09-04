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
 * A node is 32 bytes (two uvec4) — the doc's 16 B layout carried no quadCount
 * and its bit budget did not add up (4+26+24+24 &gt; 64); the roomy layout
 * trades 16 B/node for exact uint fields everywhere:
 * <pre>
 * a.x = zCoord(21b biased) | yCoord(21b biased) &lt;&lt; 21
 * a.y = xCoord(21b biased) | level(3b) &lt;&lt; 21 | flags(8b) &lt;&lt; 24
 * a.z = quadStart        (arena quad index)
 * a.w = childPtr         (node index of first of 8 children; 0 = none, 4c)
 * b.x = quadCount
 * b.y = visibleFrameId   (GPU-written by traversal; LRU input, 4c)
 * b.z = lastRequestFrame (GPU subdivision requests, 4c)
 * b.w = slot             (cubeInfo index, 0 = none — matches AllvrBuffers)
 * </pre>
 * Position is stored as CUBE coordinates for the node's level (level 0 in 4a);
 * the shader reconstructs the absolute block AABB as
 * {@code coord << (5 + level)}. Nodes are keyed by cube long on the Java side;
 * freed indices recycle via a free list and keep {@code FLAG_DEAD} in the
 * mirror until reused (the traversal skips them — the dispatch covers
 * {@code highWater}, not a compacted count).
 * <p>
 * All mutation happens on the render thread; the mirror is uploaded to GL by
 * {@code AllvrBuffers#syncNodes} from the dirty set (per-node 32 B chunks, or a
 * full re-upload when the capacity grew).
 */
public final class AllvrNodeStore {

    public static final int FLAG_HAS_MESH = 1;
    public static final int FLAG_DEAD = 2;

    /** Hard ceiling (doc §7.4 node SSBO budget ≈ 2²¹ nodes). */
    public static final int MAX_NODES = 1 << 21;

    /** Longs per node in the mirror (8 uints). */
    public static final int LONGS_PER_NODE = 4;

    private long[] mirror = new long[1024 * LONGS_PER_NODE];
    private int capacity = 1024;
    private int highWater;
    private final Long2IntOpenHashMap byCubeKey = new Long2IntOpenHashMap();
    private final IntList freeIndices = new IntArrayList();
    private final IntSet dirty = new IntOpenHashSet();

    public AllvrNodeStore() {
        this.byCubeKey.defaultReturnValue(-1);
    }

    public int capacity() {
        return this.capacity;
    }

    public int highWater() {
        return this.highWater;
    }

    public int nodeCount() {
        return this.byCubeKey.size();
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
        int existing = this.byCubeKey.get(cubeKey);
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
        this.byCubeKey.put(cubeKey, idx);
        // position = cube coords for level 0: block min corner >> 5
        this.writePosition(idx,
            cubeMinBlock.getX() >> 5, cubeMinBlock.getY() >> 5, cubeMinBlock.getZ() >> 5);
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
        long a = this.mirror[o];                      // a.x | a.y<<32
        long ay = (a >>> 32) & 0xFFFFFFFFL;
        // keep xCoord+level (bits 0..23), replace flags with HAS_MESH
        ay = (ay & 0x00FF_FFFFL) | ((long) FLAG_HAS_MESH << 24);
        this.mirror[o] = (a & 0xFFFFFFFFL) | (ay << 32);
        this.mirror[o + 1] = (long) quadStart & 0xFFFFFFFFL;      // a.z = quadStart, a.w = childPtr 0
        this.mirror[o + 2] = (long) quadCount & 0xFFFFFFFFL;      // b.x = quadCount (b.y stamp stays stale)
        this.mirror[o + 3] = ((long) slot & 0xFFFFFFFFL) << 32;   // b.w = slot
        this.dirty.add(idx);
    }

    /** Marks the node dead and recycles its index. */
    public void freeNode(long cubeKey) {
        int idx = this.byCubeKey.remove(cubeKey);
        if (idx < 0) {
            return;
        }
        int o = idx * LONGS_PER_NODE;
        long ay = (this.mirror[o] >>> 32) & 0xFFFFFFFFL;
        ay |= (long) FLAG_DEAD << 24;
        this.mirror[o] = (this.mirror[o] & 0xFFFFFFFFL) | (ay << 32);
        this.dirty.add(idx);
        this.freeIndices.add(idx);
    }

    /** Drops every node (level unload / GPU-mode switch; rebuild via setMesh). */
    public void clear() {
        java.util.Arrays.fill(this.mirror, 0L);
        this.highWater = 0;
        this.byCubeKey.clear();
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

    private void writePosition(int idx, int cx, int cy, int cz) {
        int o = idx * LONGS_PER_NODE;
        long ax = ((long) (cz & 0x1FFFFF)) | (((long) (cy & 0x1FFFFF)) << 21);
        long ay = (long) (cx & 0x1FFFFF); // level 0, flags 0
        this.mirror[o] = ax | (ay << 32);
        this.mirror[o + 1] = 0L;          // quadStart/childPtr unset
        this.mirror[o + 2] = 0L;
        this.mirror[o + 3] = 0L;
    }
}
