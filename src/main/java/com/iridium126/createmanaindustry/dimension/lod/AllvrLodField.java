package com.iridium126.createmanaindustry.dimension.lod;

import com.iridium126.createmanaindustry.dimension.gen.AllvrIslandFieldGenerator;
import com.iridium126.createmanaindustry.dimension.gen.AllvrIslandFieldGenerator.Island;

/**
 * Surface-node bitmap computation over a level's player-centered box (doc §13
 * 4c). A node is a SURFACE node when its block region can contain true island
 * surface (the FBM band {@code gauge ∈ [−JAG, +JAG]}); fully-interior and
 * deep-air nodes are excluded — without this, an island's interior slab alone
 * contributes ~17k empty-mesh nodes per island at L0 and burns the build
 * budget ~5×.
 * <p>
 * Classification is analytic (zero voxel evaluation, zero pow, zero FBM): per
 * node cell, the pure superellipse gauge q of every candidate island at the
 * cell center is compared against precomputed per-level q thresholds. The
 * true field is {@code d = gauge + fbm·JAG} with FBM confined to
 * {@code |gauge| < 2·JAG}, so {@code gauge < −JAG ⇒ solid} and
 * {@code gauge > +JAG ⇒ air} regardless of FBM — the true surface lies
 * entirely inside the gauge shell {@code [−JAG, +JAG]}. A cell center whose
 * gauge shell distance exceeds the cell's gauge extent (gradient bound ×
 * diagonal, ~2e-3/block) therefore cannot contain surface. The per-island
 * contributions merge into a per-cell byte (all-solid / all-air / surface)
 * because the field is a union: a cell skipped as interior must be deep
 * inside EVERY island, and deep inside ANY island is still uniform solid.
 * <p>
 * Iteration visits only cells inside island inflated boxes — the bulk of a
 * bitmap box is empty space that never gets touched (state 0 = air, skipped).
 * Cost is ~15ns per visited cell; a full L0 box with 4 islands lands in the
 * low-millisecond range, event-driven (movement ≥ threshold cells), never
 * per-tick.
 */
public final class AllvrLodField {

    /**
     * Upper bound of |∇gauge| per block: radial derivative at the surface is
     * 1/hw ≤ 1/680, the diagonal worst case 1/(hw·3^(7/8)) ≈ 1.1e-3 — 2e-3
     * carries ~2× slack.
     */
    private static final double GAUGE_GRADIENT = 2.0e-3;

    private final AllvrIslandFieldGenerator generator;

    public AllvrLodField(AllvrIslandFieldGenerator generator) {
        this.generator = generator;
    }

    /**
     * Computes the surface-node bitset for the box of {@code dimCells}³ cells
     * whose minimum cell is {@code (originCellX/Y/Z)} at {@code level}. Bit
     * index = {@code (cy·dim + cz)·dim + cx} (Y-major, the convention the
     * bitmap packet documents).
     */
    public long[] compute(int level, int originCellX, int originCellY, int originCellZ, int dimCells) {
        int cellBlocks = AllvrLodBands.cellBlocks(level);
        int minBx = originCellX << (5 + level);
        int minBy = originCellY << (5 + level);
        int minBz = originCellZ << (5 + level);
        int span = dimCells * cellBlocks;

        // per-level q thresholds: the cell-center gauge may drift from the
        // cell-corner gauge by GAUGE_GRADIENT × cell diagonal
        double extent = GAUGE_GRADIENT * (cellBlocks * Math.sqrt(3.0));
        double shell = AllvrIslandFieldGenerator.EDGE_JAG + extent;
        double solidQ = Math.pow(1.0 - shell, 8.0);
        double airQ = Math.pow(1.0 + shell, 8.0);

        Island[] islands = this.generator.islandsForBox(minBx - cellBlocks, minBy - cellBlocks,
            minBz - cellBlocks, minBx + span + cellBlocks, minBy + span + cellBlocks, minBz + span + cellBlocks);

        byte[] state = new byte[dimCells * dimCells * dimCells];

        for (Island island : islands) {
            // inflated island box in blocks: FBM pushes the surface outward by
            // up to JAG×half-extent; the cell diagonal adds the rest
            double marginX = AllvrIslandFieldGenerator.EDGE_JAG * island.halfWidth() + cellBlocks + 16;
            double marginY = AllvrIslandFieldGenerator.EDGE_JAG * island.halfHeight() + cellBlocks + 16;
            int ix0 = clampCell((int) Math.floor((island.cx() - island.halfWidth() - marginX - minBx) / cellBlocks), dimCells);
            int ix1 = clampCell((int) Math.floor((island.cx() + island.halfWidth() + marginX - minBx) / cellBlocks), dimCells);
            int iy0 = clampCell((int) Math.floor((island.cy() - island.halfHeight() - marginY - minBy) / cellBlocks), dimCells);
            int iy1 = clampCell((int) Math.floor((island.cy() + island.halfHeight() + marginY - minBy) / cellBlocks), dimCells);
            int iz0 = clampCell((int) Math.floor((island.cz() - island.halfWidth() - marginX - minBz) / cellBlocks), dimCells);
            int iz1 = clampCell((int) Math.floor((island.cz() + island.halfWidth() + marginX - minBz) / cellBlocks), dimCells);

            for (int cy = iy0; cy <= iy1; cy++) {
                double wy = minBy + (cy + 0.5) * (double) cellBlocks;
                for (int cz = iz0; cz <= iz1; cz++) {
                    double wz = minBz + (cz + 0.5) * (double) cellBlocks;
                    int rowBase = (cy * dimCells + cz) * dimCells;
                    for (int cx = ix0; cx <= ix1; cx++) {
                        int idx = rowBase + cx;
                        byte s = state[idx];
                        if (s == 3) {
                            continue; // already surface — nothing to refine
                        }
                        double wx = minBx + (cx + 0.5) * (double) cellBlocks;
                        double q = AllvrIslandFieldGenerator.gaugeQ(island, wx, wy, wz);
                        byte contribution = q < solidQ ? (byte) 1 : q > airQ ? (byte) 2 : (byte) 3;
                        if (s == 0) {
                            state[idx] = contribution;
                        } else if (s != contribution) {
                            state[idx] = 3; // mixed across islands — conservative surface
                        }
                    }
                }
            }
        }

        long[] words = new long[(dimCells * dimCells * dimCells + 63) >> 6];
        for (int i = 0; i < state.length; i++) {
            if (state[i] == 3) {
                words[i >> 6] |= 1L << (i & 63);
            }
        }
        return words;
    }

    private static int clampCell(int v, int dim) {
        return Math.max(0, Math.min(dim - 1, v));
    }
}
