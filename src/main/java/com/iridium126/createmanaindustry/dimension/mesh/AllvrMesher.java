package com.iridium126.createmanaindustry.dimension.mesh;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.EmptyBlockGetter;
import net.minecraft.world.level.block.state.BlockState;

/**
 * Greedy mesher over a 34³ voxel snapshot (doc §8.1). The snapshot holds the
 * cube's 32³ states plus a 1-voxel border fetched from the neighborhood, so
 * face visibility across borders is resolved from array reads — no per-face
 * lookups.
 * <p>
 * Common to the client cube path and the server LOD path (doc §13 4c): state
 * identity and light come in through the {@link AllvrMeshCodec} and
 * {@link AllvrMeshLight} parameters, so neither side forks the sweep.
 * <p>
 * Face visibility mirrors vanilla {@code Block#shouldRenderFace} minus the
 * {@code hidesNeighborFace} rule (affects only partial models, which are
 * non-renderable in V0 anyway): cull when the neighbor occludes
 * ({@code canOcclude} + full-block collision shape, precomputed per voxel in
 * the snapshot) or when {@code skipRendering} pairs the states (glass/glass).
 * <p>
 * Output is the doc §7.3 packed 8-byte quad stream:
 * {@code axis(2) | dir(1) | uSize-1(5) | vSize-1(5) | u(5) | v(5) | w(5)
 *  | stateId(16) | sky(4) | block(4) | spare(12)}. Winding: the (u,v) basis is
 * chosen per (axis, dir) so u×v = the outward face normal; the shared index
 * buffer {@code 0,1,2,2,1,3} then produces CCW-outward triangles. The sky/block
 * nibbles are the mesher-time light bake, sampled at the air voxel adjacent to
 * the face center.
 */
public final class AllvrMesher {

    public static final int CUBE = 32;
    public static final int PADDED = 34;

    /** Snapshot array index for local coords −1..32 per axis (shared with
     *  snapshot builders on both sides). */
    public static int paddedIndex(int x, int y, int z) {
        return (y + 1) * (PADDED * PADDED) + (z + 1) * PADDED + (x + 1);
    }

    /** [axis*2 + dir] → the face Direction (dir 0 = positive axis). Also the
     *  per-face material table order, shared with {@code AllvrRenderStateMap}
     *  and the vertex shader's faceIdx. */
    public static final Direction[] FACES = {
        Direction.EAST, Direction.WEST,
        Direction.UP, Direction.DOWN,
        Direction.SOUTH, Direction.NORTH
    };

    /** Per (axis,dir) the u-axis and v-axis indices, chosen so u×v = face normal. */
    private static final byte[][] UV_AXES = {
        {1, 2}, // +x: u=y, v=z
        {2, 1}, // -x: u=z, v=y
        {2, 0}, // +y: u=z, v=x
        {0, 2}, // -y: u=x, v=z
        {0, 1}, // +z: u=x, v=y
        {1, 0}  // -z: u=y, v=x
    };

    private final BlockState[] states;
    private final byte[] occludes;
    private final int[] mask = new int[CUBE * CUBE];
    private final AllvrMeshCodec codec;
    private AllvrMeshLight light;
    private long[] out;
    private int outCount;

    private AllvrMesher(BlockState[] states, byte[] occludes, AllvrMeshCodec codec) {
        this.states = states;
        this.occludes = occludes;
        this.codec = codec;
    }

    /** Snapshot array index for local coords −1..32 per axis. */
    private static int idx(int x, int y, int z) {
        return paddedIndex(x, y, z);
    }

    private BlockState stateAt(int axisU, int axisV, int axisW, int u, int v, int w) {
        // map (u,v,w) in cube-local space back to (x,y,z)
        int x = axisU == 0 ? u : axisV == 0 ? v : w;
        int y = axisU == 1 ? u : axisV == 1 ? v : w;
        int z = axisU == 2 ? u : axisV == 2 ? v : w;
        return this.states[idx(x, y, z)];
    }

    private int cellIdx(int axisU, int axisV, int axisW, int u, int v, int w) {
        int x = axisU == 0 ? u : axisV == 0 ? v : w;
        int y = axisU == 1 ? u : axisV == 1 ? v : w;
        int z = axisU == 2 ? u : axisV == 2 ? v : w;
        return idx(x, y, z);
    }

    /**
     * Builds the quad stream for one padded snapshot. Returns a packed long[]
     * of exactly {@code result.length} quads (no trailing slack — sized via
     * the scratch grow loop, then trimmed). {@code light} may be null (zero
     * light nibbles).
     */
    public static long[] build(BlockState[] states, byte[] occludes, AllvrMeshLight light,
                               AllvrMeshCodec codec) {
        AllvrMesher m = new AllvrMesher(states, occludes, codec);
        m.light = light;
        m.out = new long[1024];
        for (int axis = 0; axis < 3; axis++) {
            for (int dir = 0; dir < 2; dir++) {
                m.sweep(axis, dir);
            }
        }
        long[] result = new long[m.outCount];
        System.arraycopy(m.out, 0, result, 0, m.outCount);
        return result;
    }

    private void sweep(int axis, int dir) {
        int faceIdx = axis * 2 + dir;
        Direction face = FACES[faceIdx];
        int uAxis = UV_AXES[faceIdx][0];
        int vAxis = UV_AXES[faceIdx][1];

        for (int w = 0; w < CUBE; w++) {
            // build the visibility mask for this slice: value = packed state id
            boolean any = false;
            for (int v = 0; v < CUBE; v++) {
                for (int u = 0; u < CUBE; u++) {
                    int id = this.maskId(axis, dir, uAxis, vAxis, face, u, v, w);
                    this.mask[v * CUBE + u] = id;
                    any |= id != 0;
                }
            }
            if (!any) {
                continue;
            }
            // greedy rectangle merge
            for (int v = 0; v < CUBE; v++) {
                for (int u = 0; u < CUBE; u++) {
                    int m = this.mask[v * CUBE + u];
                    if (m == 0) {
                        continue;
                    }
                    int width = 1;
                    while (u + width < CUBE && this.mask[v * CUBE + u + width] == m) {
                        width++;
                    }
                    int height = 1;
                    outer:
                    while (v + height < CUBE) {
                        for (int du = 0; du < width; du++) {
                            if (this.mask[(v + height) * CUBE + u + du] != m) {
                                break outer;
                            }
                        }
                        height++;
                    }
                    // emit and clear
                    for (int dv = 0; dv < height; dv++) {
                        for (int du = 0; du < width; du++) {
                            this.mask[(v + dv) * CUBE + u + du] = 0;
                        }
                    }
                    // flat light bake at the air voxel adjacent to the face
                    // center (rects merge across light variation — documented)
                    int sky = 0;
                    int blk = 0;
                    if (this.light != null) {
                        int su = u + (width >> 1);
                        int sv = v + (height >> 1);
                        int wn = dir == 0 ? w + 1 : w - 1;
                        int sx = uAxis == 0 ? su : vAxis == 0 ? sv : wn;
                        int sy = uAxis == 1 ? su : vAxis == 1 ? sv : wn;
                        int sz = uAxis == 2 ? su : vAxis == 2 ? sv : wn;
                        long sampleY = this.light.originY() + sy;
                        sky = this.light.sky(sx, sz, sampleY);
                        blk = this.light.block(sx, sz, sampleY);
                    }
                    long quad = (long) axis
                        | ((long) dir << 2)
                        | ((long) (width - 1) << 3)
                        | ((long) (height - 1) << 8)
                        | ((long) u << 13)
                        | ((long) v << 18)
                        | ((long) w << 23)
                        | ((long) m << 28)
                        | ((long) sky << 44)
                        | ((long) blk << 48);
                    if (this.outCount == this.out.length) {
                        // no cap: craftable patterns (checkerboard) reach ~5×10⁴
                        // quads per cube; the oversized stream is split
                        // per-command at draw time
                        long[] grown = new long[this.out.length * 2];
                        System.arraycopy(this.out, 0, grown, 0, this.outCount);
                        this.out = grown;
                    }
                    this.out[this.outCount++] = quad;
                }
            }
        }
    }

    /**
     * Face-visibility for the face of voxel (u,v,w) toward dir along the sweep
     * axis; returns the codec's packed id or 0. Mirrors vanilla
     * {@code Block#shouldRenderFace} minus the {@code hidesNeighborFace} rule
     * (partial models don't render in V0, so the rule can only fire against
     * non-renderable neighbors).
     */
    private int maskId(int axis, int dir, int uAxis, int vAxis, Direction face, int u, int v, int w) {
        BlockState state = this.stateAt(uAxis, vAxis, axis, u, v, w);
        if (state.isAir()) {
            return 0;
        }
        int wn = dir == 0 ? w + 1 : w - 1;
        if (this.occludes[this.cellIdx(uAxis, vAxis, axis, u, v, wn)] != 0) {
            return 0;
        }
        BlockState neighbor = this.stateAt(uAxis, vAxis, axis, u, v, wn);
        if (state.skipRendering(neighbor, face)) {
            return 0;
        }
        return this.codec.packId(state);
    }

    /** Precomputed per-voxel occluder flag used by the sweep and the snapshot
     *  builders (both sides). */
    public static byte occludesAt(BlockState state) {
        if (!state.canOcclude()) {
            return 0;
        }
        return state.isCollisionShapeFullBlock(EmptyBlockGetter.INSTANCE, BlockPos.ZERO) ? (byte) 1 : 0;
    }
}
