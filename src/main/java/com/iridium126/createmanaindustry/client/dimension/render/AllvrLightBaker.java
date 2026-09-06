package com.iridium126.createmanaindustry.client.dimension.render;

import java.util.ArrayList;
import java.util.List;

import com.iridium126.createmanaindustry.client.dimension.AllvrClientCubeCache;
import com.iridium126.createmanaindustry.dimension.cube.AllvrCube;
import com.iridium126.createmanaindustry.dimension.cube.AllvrCubePos;
import com.iridium126.createmanaindustry.dimension.mesh.AllvrMesher;

import it.unimi.dsi.fastutil.ints.Int2IntMap;
import net.minecraft.world.level.chunk.LevelChunkSection;

/**
 * Mesher-time light bake (doc §13 iris slice, grilling decision ⑥): the two
 * 4-bit channels packed into the quad word's reserved bits —
 * <ul>
 *   <li><b>sky</b>: binary column exposure — a voxel is sky-lit (15) when no
 *       full occluder exists within the 128-block window straight above it.
 *       The window covers the cube itself (from the mesher snapshot, pad rows
 *       inclusive) plus the four cubes above; per-section {@code hasOnlyAir()}
 *       early-outs keep the scan short in the sparse allay void. No horizontal
 *       propagation — the bounded-column approximation from the grilling.</li>
 *   <li><b>block</b>: manhattan max-decay over the emitters of the 3×3×3 cube
 *       neighborhood ({@code max(emission − manhattanDistance)}), no occlusion
 *       walk — the approximation the pack-side patch was scoped against.</li>
 * </ul>
 * <p>
 * World reads happen once per mesh job inside one
 * {@link AllvrClientCubeCache#LOCK} section (sky columns + emitter gather);
 * the per-quad sampling is then lock-free over the gathered arrays. Sample
 * point per greedy rect is the air voxel adjacent to the face at the rect's
 * center — flat light per quad (rects merge across light variation; the
 * documented flat-light trade-off).
 */
public final class AllvrLightBaker implements com.iridium126.createmanaindustry.dimension.mesh.AllvrMeshLight {

    private static final int SKY_WINDOW = 128;
    private static final int EMITTER_RANGE = 15;
    private static final int MAX_EMITTERS = 4096;
    private static final int MAX_OCCLUDERS_PER_COLUMN = 64;

    /** Sky occlusion window in blocks (public: the renderer's light-dirty
     *  rule derives its cube span from it). */
    public static final int SKY_WINDOW_BLOCKS = SKY_WINDOW;

    private final long cubeMinX;
    private final long cubeMinY;
    private final long cubeMinZ;
    /** Per (localX+1) + (localZ+1)*34 column: absolute Ys of occluders within
     *  [localY −1 .. 32] ∪ [cubeTop+1 .. cubeTop+SKY_WINDOW]. */
    private final long[][] columnOccluders = new long[34 * 34][];
    /** Flat quadruples (absX, absY, absZ, emission) of neighborhood emitters. */
    private final long[] emitters;
    private final int emitterCount;

    private AllvrLightBaker(long cubeMinX, long cubeMinY, long cubeMinZ,
                            long[] emitters, int emitterCount) {
        this.cubeMinX = cubeMinX;
        this.cubeMinY = cubeMinY;
        this.cubeMinZ = cubeMinZ;
        this.emitters = emitters;
        this.emitterCount = emitterCount;
    }

    /**
     * Gathers the light context for the cube at {@code key}. {@code occludes}
     * is the mesher's padded snapshot occluder array (private to the job);
     * everything crossing the cube border is read inside the cache lock.
     */
    public static AllvrLightBaker capture(long key, byte[] occludes) {
        AllvrCubePos cpos = AllvrCubePos.fromLong(key);
        long minX = blockMin(cpos.getX());
        long minY = blockMin(cpos.getY());
        long minZ = blockMin(cpos.getZ());

        long[] emitters = new long[MAX_EMITTERS * 4];
        int emitterCount = 0;
        long[][] columns = new long[34 * 34][];

        synchronized (AllvrClientCubeCache.LOCK) {
            // emitters: 27-cube neighborhood — an emitter at a cube corner
            // reaches into every diagonal neighbor within manhattan 15
            for (int dy = -1; dy <= 1 && emitterCount < MAX_EMITTERS; dy++) {
                for (int dz = -1; dz <= 1 && emitterCount < MAX_EMITTERS; dz++) {
                    for (int dx = -1; dx <= 1 && emitterCount < MAX_EMITTERS; dx++) {
                        AllvrCube cube = AllvrClientCubeCache.peekCube(
                            AllvrCubePos.of(cpos.getX() + dx, cpos.getY() + dy, cpos.getZ() + dz).asLong());
                        if (cube == null) {
                            continue;
                        }
                        long cminX = blockMin(cpos.getX() + dx);
                        long cminY = blockMin(cpos.getY() + dy);
                        long cminZ = blockMin(cpos.getZ() + dz);
                        for (Int2IntMap.Entry e : cube.getEmitters().int2IntEntrySet()) {
                            if (emitterCount >= MAX_EMITTERS) {
                                break;
                            }
                            int idx = e.getIntKey();
                            int o = emitterCount * 4;
                            emitters[o] = cminX + (idx & 31);
                            emitters[o + 1] = cminY + ((idx >> 10) & 31);
                            emitters[o + 2] = cminZ + ((idx >> 5) & 31);
                            emitters[o + 3] = e.getIntValue();
                            emitterCount++;
                        }
                    }
                }
            }
            // sky columns: the padded column above every (x,z), pad ring
            // included (face-adjacent sample voxels live at ±1/32)
            for (int x = -1; x <= 32; x++) {
                for (int z = -1; z <= 32; z++) {
                    columns[(x + 1) + (z + 1) * 34] =
                        collectColumn(cpos, x, z, occludes, minY);
                }
            }
        }

        AllvrLightBaker baker = new AllvrLightBaker(minX, minY, minZ, emitters, emitterCount);
        System.arraycopy(columns, 0, baker.columnOccluders, 0, columns.length);
        return baker;
    }

    private static long blockMin(int cubeCoord) {
        return (long) cubeCoord << 5;
    }

    /** Absolute Y of the center cube's bottom — the mesher converts its local
     *  sample Y with this. */
    public long cubeMinY() {
        return this.cubeMinY;
    }

    @Override
    public long originY() {
        return this.cubeMinY;
    }

    /** One column's occluder Ys: snapshot range (local −1..32) plus the
     *  128-block window above, crossing the neighbor cubes above. */
    private static long[] collectColumn(AllvrCubePos cpos, int x, int z,
                                        byte[] occludes, long minY) {
        long[] buf = new long[MAX_OCCLUDERS_PER_COLUMN];
        int n = 0;
        for (int ly = -1; ly <= 32; ly++) {
            if (occludes[AllvrMesher.paddedIndex(x, ly, z)] != 0) {
                if (n == buf.length) {
                    break; // pathological column — capped, deep occluders dropped
                }
                buf[n++] = minY + ly;
            }
        }
        // window above: cubes (cx + (x>>5), cy+k, cz + (z>>5)), k = 1..4 —
        // the arithmetic shift maps the pad ring (−1 → −1 cube, 32 → +1 cube)
        int nx = x >> 5;
        int nz = z >> 5;
        int lx = x & 31;
        int lz = z & 31;
        for (int k = 1; k <= SKY_WINDOW >> 5; k++) {
            AllvrCube cube = AllvrClientCubeCache.peekCube(AllvrCubePos
                .of(cpos.getX() + nx, cpos.getY() + k, cpos.getZ() + nz).asLong());
            if (cube == null) {
                continue;
            }
            LevelChunkSection[] sections = cube.getSections();
            long cubeMinY = blockMin(cpos.getY() + k);
            for (int ssy = 0; ssy < 2; ssy++) {
                LevelChunkSection section = sections[AllvrCube.sliceIndex(lx >> 4, ssy, lz >> 4)];
                if (section.hasOnlyAir()) {
                    continue;
                }
                var states = section.getStates();
                int sx = lx & 15;
                int sz = lz & 15;
                for (int ly = 0; ly < 16; ly++) {
                    if (AllvrMesher.occludesAt(states.get(sx, ly, sz)) != 0) {
                        if (n == buf.length) {
                            return cappedColumn(buf, n);
                        }
                        buf[n++] = cubeMinY + (ssy << 4) + ly;
                    }
                }
            }
        }
        long[] out = new long[n];
        System.arraycopy(buf, 0, out, 0, n);
        return out;
    }

    private static long[] cappedColumn(long[] buf, int n) {
        long[] out = new long[n];
        System.arraycopy(buf, 0, out, 0, n);
        return out;
    }

    /** Binary sky exposure at a sample voxel: 15 when the column above is
     *  clear within the window, else 0. {@code lx}/{@code lz} are the cube's
     *  local coords (−1..32), {@code y} absolute. */
    public int sky(int lx, int lz, long y) {
        long[] column = this.columnOccluders[(lx + 1) + (lz + 1) * 34];
        if (column != null) {
            for (long o : column) {
                long d = o - y;
                if (d > 0 && d <= SKY_WINDOW) {
                    return 0;
                }
            }
        }
        return 15;
    }

    /** Block light at a sample voxel: manhattan max-decay over the gathered
     *  neighborhood emitters, 0 when none reach. */
    public int block(int lx, int lz, long y) {
        if (this.emitterCount == 0) {
            return 0;
        }
        long x = this.cubeMinX + lx;
        long z = this.cubeMinZ + lz;
        int best = 0;
        for (int i = 0; i < this.emitterCount; i++) {
            int o = i * 4;
            long dx = Math.abs(this.emitters[o] - x);
            if (dx > EMITTER_RANGE) {
                continue;
            }
            long dy = Math.abs(this.emitters[o + 1] - y);
            if (dy > EMITTER_RANGE) {
                continue;
            }
            long dz = Math.abs(this.emitters[o + 2] - z);
            if (dx + dy + dz > EMITTER_RANGE) {
                continue;
            }
            int value = (int) (this.emitters[o + 3] - dx - dy - dz);
            if (value > best) {
                best = value;
            }
        }
        return Math.min(15, best);
    }
}
