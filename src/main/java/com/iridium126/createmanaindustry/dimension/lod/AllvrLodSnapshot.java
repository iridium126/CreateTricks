package com.iridium126.createmanaindustry.dimension.lod;

import it.unimi.dsi.fastutil.ints.Int2IntMap;
import it.unimi.dsi.fastutil.ints.Int2ObjectOpenHashMap;
import it.unimi.dsi.fastutil.longs.LongArrayList;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;

import com.iridium126.createmanaindustry.dimension.cube.AllvrCube;
import com.iridium126.createmanaindustry.dimension.cube.AllvrCubeMap;
import com.iridium126.createmanaindustry.dimension.cube.AllvrCubePos;
import com.iridium126.createmanaindustry.dimension.gen.AllvrIslandFieldGenerator;
import com.iridium126.createmanaindustry.dimension.gen.AllvrIslandFieldGenerator.Island;
import com.iridium126.createmanaindustry.dimension.mesh.AllvrMeshLight;
import com.iridium126.createmanaindustry.dimension.mesh.AllvrMesher;

/**
 * Builds the 34³ mesher snapshot for one LOD node from the island density
 * field plus player-edit overlay (doc §13 4c) — the server-side VoxelSource.
 * <p>
 * Classification is q-domain and pow-free. The true field is
 * {@code d = gauge + fbm·JAG} with FBM confined to {@code |gauge| < 2·JAG},
 * so {@code gauge < −JAG ⇒ solid} and {@code gauge > +JAG ⇒ air} hold
 * regardless of FBM; the remaining gauge shell routes through the generator's
 * own {@code evaluate} (full FBM + union + material bands). Material in the
 * q-only path needs no pow either — the depth bands translate to q thresholds,
 * and grass is impossible there because its band lies inside the FBM shell. A
 * 2×2×2 snapshot-cell group whose center classifies uniformly (every island
 * deeply solid or deeply air after the group's gauge-extent margin) fills its
 * 8 cells without per-cell work — the expected ≥70% evaluation cut of
 * grilling Q5.
 * <p>
 * The overlay (captured on the server thread at enqueue time — see
 * {@link #capture}) overrides cells with real blocks from EDITED loaded
 * cubes: unedited cubes are bitwise the density field, so only they need
 * reading, which keeps the main-thread capture near zero for natural terrain.
 * Overlay semantics per grilling Q3: any full occluder in the cell ⇒ solid,
 * represented by that state (dug tunnels read as solid far away — documented
 * known limitation).
 * <p>
 * Usage per build job: {@link #create} → {@link #fill} → {@link #light()} →
 * {@code AllvrMesher.build(..., light, SERVER_CODEC)}.
 */
public final class AllvrLodSnapshot {

    /** Server mesh codec: vanilla global state id, gated on canOcclude + full
     *  block (the 4c gating decision). The client remaps ids on packet receive. */
    public static final com.iridium126.createmanaindustry.dimension.mesh.AllvrMeshCodec SERVER_CODEC =
        state -> AllvrMesher.occludesAt(state) != 0 ? net.minecraft.world.level.block.Block.getId(state) : 0;

    /** FBM application band in q: |gauge| < 2·JAG ⟺ q ∈ (BAND_LO, BAND_HI). */
    private static final double BAND_LO_Q = Math.pow(1.0 - 2.0 * AllvrIslandFieldGenerator.EDGE_JAG, 8.0);
    private static final double BAND_HI_Q = Math.pow(1.0 + 2.0 * AllvrIslandFieldGenerator.EDGE_JAG, 8.0);
    /** Depth 0.22 (dirt/stone boundary) as a q threshold: depth < 0.22 ⟺ q > 0.78⁸. */
    private static final double DIRT_Q = Math.pow(0.78, 8.0);
    /** Worst-case presence check for the sky column samples: gauge < +JAG ⇒
     *  possibly inside the island shell ⇒ block the sky conservatively. */
    private static final double PRESENCE_Q = Math.pow(1.0 + AllvrIslandFieldGenerator.EDGE_JAG, 8.0);

    private static final double GAUGE_GRADIENT = 2.0e-3;
    private static final BlockState STONE = Blocks.STONE.defaultBlockState();
    private static final BlockState DIRT = Blocks.DIRT.defaultBlockState();

    private final AllvrIslandFieldGenerator generator;
    private final AllvrLodPos pos;
    private final Island[] islands;
    private final double cellSolidQ;
    private final double cellAirQ;
    private final double groupSolidQ;
    private final double groupAirQ;
    private final Overlay overlay;
    /** The occluder array fill() was given — the light's column scan reads it. */
    private byte[] occludes;

    private AllvrLodSnapshot(AllvrIslandFieldGenerator generator, AllvrLodPos pos, Overlay overlay) {
        this.generator = generator;
        this.pos = pos;
        this.overlay = overlay;
        int stride = pos.stride();
        double cellExt = GAUGE_GRADIENT * stride * Math.sqrt(3.0);
        double groupExt = GAUGE_GRADIENT * 2.0 * stride * Math.sqrt(3.0);
        double jag = AllvrIslandFieldGenerator.EDGE_JAG;
        this.cellSolidQ = Math.pow(1.0 - jag - cellExt, 8.0);
        this.cellAirQ = Math.pow(1.0 + jag + cellExt, 8.0);
        this.groupSolidQ = Math.pow(1.0 - jag - groupExt, 8.0);
        this.groupAirQ = Math.pow(1.0 + jag + groupExt, 8.0);
        int minBx = pos.minBlockX();
        int minBy = pos.minBlockY();
        int minBz = pos.minBlockZ();
        int span = pos.sizeBlocks();
        this.islands = this.generator.islandsForBox(minBx - stride, minBy - stride, minBz - stride,
            minBx + span + stride, minBy + span + stride, minBz + span + stride);
    }

    public static AllvrLodSnapshot create(AllvrIslandFieldGenerator generator, AllvrLodPos pos, Overlay overlay) {
        return new AllvrLodSnapshot(generator, pos, overlay);
    }

    /**
     * Server-thread overlay capture for a node: edited loaded cubes
     * overlapping the node's padded region contribute their occluder cells
     * (first occluder per snapshot cell) and light emitters. Cells align with
     * cube borders on every level (stride divides 32), so each snapshot cell
     * is decided by exactly one cube. Unedited cubes are skipped wholesale —
     * their blocks ARE the density field.
     */
    public static Overlay capture(AllvrCubeMap cubeMap, AllvrLodPos pos) {
        int stride = pos.stride();
        int minBx = pos.minBlockX();
        int minBy = pos.minBlockY();
        int minBz = pos.minBlockZ();
        int span = pos.sizeBlocks();
        // cells need the pad ring (+stride); emitters reach manhattan 15 beyond it
        int pad = stride + 16;
        Int2ObjectOpenHashMap<BlockState> cells = new Int2ObjectOpenHashMap<>();
        LongArrayList emitters = new LongArrayList();

        int cx0 = (minBx - pad) >> 5;
        int cx1 = (minBx + span + pad - 1) >> 5;
        int cy0 = (minBy - pad) >> 5;
        int cy1 = (minBy + span + pad - 1) >> 5;
        int cz0 = (minBz - pad) >> 5;
        int cz1 = (minBz + span + pad - 1) >> 5;
        BlockPos.MutableBlockPos cursor = new BlockPos.MutableBlockPos();
        for (int cy = cy0; cy <= cy1; cy++) {
            for (int cz = cz0; cz <= cz1; cz++) {
                for (int cx = cx0; cx <= cx1; cx++) {
                    long key = AllvrCubePos.asLong(cx, cy, cz);
                    if (!cubeMap.isEdited(key)) {
                        continue;
                    }
                    AllvrCube cube = cubeMap.getLoadedCube(key);
                    if (cube == null) {
                        continue; // edited then unloaded — R14 semantics, density wins
                    }
                    collectCubeOverlay(pos, cube, cx, cy, cz, cursor, cells);
                    for (Int2IntMap.Entry e : cube.getEmitters().int2IntEntrySet()) {
                        int cell = e.getIntKey();
                        emitters.add((cx << 5) + (cell & 31));
                        emitters.add((cy << 5) + (cell >> 10));
                        emitters.add((cz << 5) + ((cell >> 5) & 31));
                        emitters.add(e.getIntValue());
                    }
                }
            }
        }
        return new Overlay(cells, emitters.toLongArray());
    }

    /** One edited cube's occluder contribution to the snapshot cells. */
    private static void collectCubeOverlay(AllvrLodPos pos, AllvrCube cube, int cx, int cy, int cz,
                                           BlockPos.MutableBlockPos cursor,
                                           Int2ObjectOpenHashMap<BlockState> cells) {
        int shift = 5 + pos.level();
        int stride = pos.stride();
        int minBx = pos.minBlockX();
        int minBy = pos.minBlockY();
        int minBz = pos.minBlockZ();
        int bx0 = cx << 5;
        int by0 = cy << 5;
        int bz0 = cz << 5;
        // this cube's local cell range, clamped to the padded snapshot space
        int lx0 = Math.max(-1, (bx0 - minBx) >> shift);
        int lx1 = Math.min(32, (bx0 + 31 - minBx) >> shift);
        int ly0 = Math.max(-1, (by0 - minBy) >> shift);
        int ly1 = Math.min(32, (by0 + 31 - minBy) >> shift);
        int lz0 = Math.max(-1, (bz0 - minBz) >> shift);
        int lz1 = Math.min(32, (bz0 + 31 - minBz) >> shift);
        for (int ly = ly0; ly <= ly1; ly++) {
            for (int lz = lz0; lz <= lz1; lz++) {
                for (int lx = lx0; lx <= lx1; lx++) {
                    if (cells.containsKey(AllvrMesher.paddedIndex(lx, ly, lz))) {
                        continue;
                    }
                    // scan the cell's stride³ blocks for the first full occluder
                    int wx0 = minBx + (lx << pos.level());
                    int wy0 = minBy + (ly << pos.level());
                    int wz0 = minBz + (lz << pos.level());
                    BlockState found = null;
                    for (int dy = 0; dy < stride && found == null; dy++) {
                        for (int dz = 0; dz < stride && found == null; dz++) {
                            for (int dx = 0; dx < stride && found == null; dx++) {
                                BlockState s = cube.getBlockState(cursor.set(wx0 + dx, wy0 + dy, wz0 + dz));
                                if (AllvrMesher.occludesAt(s) != 0) {
                                    found = s;
                                }
                            }
                        }
                    }
                    if (found != null) {
                        cells.put(AllvrMesher.paddedIndex(lx, ly, lz), found);
                    }
                }
            }
        }
    }

    /** Immutable overlay payload handed from the server thread to a build job. */
    public record Overlay(Int2ObjectOpenHashMap<BlockState> cells, long[] emitters) {}

    /**
     * Fills the padded snapshot arrays for one node. {@code states} /
     * {@code occludes} are caller-allocated {@code PADDED³} arrays (air /
     * zero pre-filled by the caller). Must run before {@link #light()}.
     */
    public void fill(BlockState[] states, byte[] occludes) {
        this.occludes = occludes;
        int stride = this.pos.stride();
        int minBx = this.pos.minBlockX();
        int minBy = this.pos.minBlockY();
        int minBz = this.pos.minBlockZ();
        // 17 groups of 2×2×2 snapshot cells cover local −1..32 (pad ring included)
        for (int gy = 0; gy < 17; gy++) {
            double wy = minBy + 2 * gy * (double) stride;
            for (int gz = 0; gz < 17; gz++) {
                double wz = minBz + 2 * gz * (double) stride;
                for (int gx = 0; gx < 17; gx++) {
                    double wx = minBx + 2 * gx * (double) stride;
                    int groupResult = this.classifyGroup(wx, wy, wz);
                    if (groupResult == 0) {
                        this.fillGroupPerCell(gx, gy, gz, states, occludes);
                    } else if (groupResult == 1) {
                        // uniformly solid — material from the full evaluate at
                        // the center (deep cells: pure gauge, cheap)
                        BlockState material = this.generator.evaluate((int) wx, (int) wy, (int) wz, this.islands);
                        if (material == null) {
                            material = STONE; // union edge — the q said solid
                        }
                        this.writeGroup(gx, gy, gz, material, states, occludes);
                    }
                    // groupResult == 2: uniformly air — arrays stay air
                }
            }
        }
        // overlay always wins over the density field
        for (it.unimi.dsi.fastutil.ints.Int2ObjectMap.Entry<BlockState> e : this.overlay.cells().int2ObjectEntrySet()) {
            int idx = e.getIntKey();
            states[idx] = e.getValue();
            occludes[idx] = AllvrMesher.occludesAt(e.getValue());
        }
    }

    /** 0 = mixed (per-cell path), 1 = uniformly solid, 2 = uniformly air. */
    private int classifyGroup(double wx, double wy, double wz) {
        int result = 2; // air until some island says otherwise
        for (Island island : this.islands) {
            double q = AllvrIslandFieldGenerator.gaugeQ(island, wx, wy, wz);
            if (q >= BAND_LO_Q && q <= BAND_HI_Q) {
                return 0; // FBM band — only the full evaluate can decide
            }
            if (q < this.groupSolidQ) {
                result = 1; // solid contribution — other islands may still
                            // force the per-cell path, keep scanning
            } else if (q <= this.groupAirQ) {
                return 0; // inside the uncertainty shell — per-cell path
            }
            // q > groupAirQ: air contribution, result unchanged
        }
        return result;
    }

    private void fillGroupPerCell(int gx, int gy, int gz, BlockState[] states, byte[] occludes) {
        int stride = this.pos.stride();
        int minBx = this.pos.minBlockX();
        int minBy = this.pos.minBlockY();
        int minBz = this.pos.minBlockZ();
        for (int dy = 0; dy < 2; dy++) {
            int ly = 2 * gy - 1 + dy;
            for (int dz = 0; dz < 2; dz++) {
                int lz = 2 * gz - 1 + dz;
                for (int dx = 0; dx < 2; dx++) {
                    int lx = 2 * gx - 1 + dx;
                    double wx = minBx + (lx + 0.5) * stride;
                    double wy = minBy + (ly + 0.5) * stride;
                    double wz = minBz + (lz + 0.5) * stride;
                    BlockState state = this.classifyCell(wx, wy, wz);
                    if (state != null) {
                        int idx = AllvrMesher.paddedIndex(lx, ly, lz);
                        states[idx] = state;
                        occludes[idx] = AllvrMesher.occludesAt(state);
                    }
                }
            }
        }
    }

    /** Solid material for one snapshot cell, or null for air (q-only fast
     *  path; FBM-band cells delegate to the generator's evaluate). */
    private BlockState classifyCell(double wx, double wy, double wz) {
        double minSolidQ = Double.MAX_VALUE;
        for (Island island : this.islands) {
            double q = AllvrIslandFieldGenerator.gaugeQ(island, wx, wy, wz);
            if (q >= BAND_LO_Q && q <= BAND_HI_Q) {
                return this.generator.evaluate((int) wx, (int) wy, (int) wz, this.islands);
            }
            if (q < this.cellSolidQ && q < minSolidQ) {
                minSolidQ = q;
            }
            // q > cellAirQ: air contribution from this island
        }
        if (minSolidQ == Double.MAX_VALUE) {
            return null;
        }
        return minSolidQ < DIRT_Q ? STONE : DIRT;
    }

    private void writeGroup(int gx, int gy, int gz, BlockState material, BlockState[] states, byte[] occludes) {
        byte occ = AllvrMesher.occludesAt(material);
        for (int dy = 0; dy < 2; dy++) {
            int ly = 2 * gy - 1 + dy;
            for (int dz = 0; dz < 2; dz++) {
                int lz = 2 * gz - 1 + dz;
                for (int dx = 0; dx < 2; dx++) {
                    int lx = 2 * gx - 1 + dx;
                    int idx = AllvrMesher.paddedIndex(lx, ly, lz);
                    states[idx] = material;
                    occludes[idx] = occ;
                }
            }
        }
    }

    /**
     * The node's {@link AllvrMeshLight}: sky = snapshot column scan plus six
     * coarse density samples over the 128-block window above the node (no
     * caves exist, and islands above sit ≥300 blocks out — a ~21-block sample
     * pitch may miss thin overhangs, a documented LOD approximation); block
     * light = manhattan max-decay over the captured edited-cube emitters.
     * <p>
     * NB the mesher passes {@code y = originY() + local cell index}; with
     * stride > 1 this impl reinterprets it as a cell index and scales.
     */
    public AllvrMeshLight light() {
        if (this.occludes == null) {
            throw new IllegalStateException("light() before fill()");
        }
        return new LodLight();
    }

    private final class LodLight implements AllvrMeshLight {

        private final Island[] aboveIslands;

        LodLight() {
            AllvrLodSnapshot self = AllvrLodSnapshot.this;
            int margin = self.pos.sizeBlocks();
            int top = self.pos.minBlockY() + self.pos.sizeBlocks();
            this.aboveIslands = self.generator.islandsForBox(
                self.pos.minBlockX() - margin, top - 32, self.pos.minBlockZ() - margin,
                self.pos.minBlockX() + margin, top + 128 + margin, self.pos.minBlockZ() + margin);
        }

        @Override
        public long originY() {
            return AllvrLodSnapshot.this.pos.minBlockY();
        }

        @Override
        public int sky(int lx, int lz, long y) {
            AllvrLodSnapshot self = AllvrLodSnapshot.this;
            int cellLy = (int) (y - originY());
            byte[] occludes = self.occludes;
            for (int ly = cellLy + 1; ly <= 32; ly++) {
                if (occludes[AllvrMesher.paddedIndex(lx, ly, lz)] != 0) {
                    return 0;
                }
            }
            // six coarse samples over the window above the node top
            int stride = self.pos.stride();
            double sampleAbs = originY() + cellLy * (double) stride;
            double topAbs = originY() + 32.0 * stride;
            double window = 128.0 - Math.max(0.0, topAbs - sampleAbs);
            if (window <= 0) {
                return 15;
            }
            double wx = self.pos.minBlockX() + (lx + 0.5) * stride;
            double wz = self.pos.minBlockZ() + (lz + 0.5) * stride;
            for (int k = 1; k <= 6; k++) {
                double py = topAbs + window * k / 7.0;
                for (Island island : this.aboveIslands) {
                    double ax = Math.abs(wx - island.cx());
                    if (ax > island.halfWidth() + 96) {
                        continue;
                    }
                    double ay = Math.abs(py - island.cy());
                    if (ay > island.halfHeight() + 96) {
                        continue;
                    }
                    double az = Math.abs(wz - island.cz());
                    if (az > island.halfWidth() + 96) {
                        continue;
                    }
                    if (AllvrIslandFieldGenerator.gaugeQ(island, wx, py, wz) < PRESENCE_Q) {
                        return 0; // possibly inside this island — block conservatively
                    }
                }
            }
            return 15;
        }

        @Override
        public int block(int lx, int lz, long y) {
            AllvrLodSnapshot self = AllvrLodSnapshot.this;
            long[] emitters = self.overlay.emitters();
            if (emitters.length == 0) {
                return 0;
            }
            int stride = self.pos.stride();
            long cellLy = y - originY(); // mesher passes originY + local cell index
            long x = self.pos.minBlockX() + lx * (long) stride + (stride >> 1);
            long yy = originY() + cellLy * stride + (stride >> 1);
            long z = self.pos.minBlockZ() + lz * (long) stride + (stride >> 1);
            int best = 0;
            for (int i = 0; i < emitters.length; i += 4) {
                long dx = Math.abs(emitters[i] - x);
                if (dx > 15) {
                    continue;
                }
                long dy = Math.abs(emitters[i + 1] - yy);
                if (dy > 15) {
                    continue;
                }
                long dz = Math.abs(emitters[i + 2] - z);
                if (dx + dy + dz > 15) {
                    continue;
                }
                int value = (int) (emitters[i + 3] - dx - dy - dz);
                if (value > best) {
                    best = value;
                }
            }
            return Math.min(15, best);
        }
    }
}
