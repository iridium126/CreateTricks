package com.iridium126.createmanaindustry.dimension.gen;

import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunkSection;

import com.iridium126.createmanaindustry.dimension.cube.AllvrCube;
import com.iridium126.createmanaindustry.dimension.cube.AllvrCubePos;

/**
 * Deterministic island-archipelago terrain for the allay dimension.
 * <p>
 * The world is a 3D lattice of large floating islands: each lattice cell
 * (XZ spacing ~2816, Y spacing ~512, odd layers offset by half a cell on both
 * horizontal axes) hosts exactly one island of ~2000×2000 blocks footprint and
 * ~200 blocks thickness, positioned/sized by a seed-stable hash. There is no
 * structure or feature stage and no caves — generation is a single pass over
 * the cube's 32³ voxels evaluating a union-of-rounded-boxes density field with
 * FBM-jagged edges. Determinism (same seed → same world) is load-bearing while
 * the dimension is in-memory only (roadmap phases 1–5 regenerate on restart).
 * <p>
 * Island interiors evaluate with cheap AABB/p-norm math only; the edge FBM is
 * reserved for the narrow shell band, keeping a full 32³ cube in the
 * microsecond-to-low-millisecond range.
 */
public final class AllvrIslandFieldGenerator {

    /** Horizontal lattice spacing (88 cubes). Island footprint ≈ 2000 blocks. */
    private static final int SPACING_XZ = 2816;
    /** Vertical lattice spacing (16 cubes). Island thickness ≈ 200 blocks. */
    private static final int SPACING_Y = 512;

    /** Island half-width range (footprint 1360..2240 blocks). */
    private static final double HALF_WIDTH_MIN = 680.0;
    private static final double HALF_WIDTH_SPAN = 440.0;
    /** Island half-thickness range (thickness 120..280 blocks). */
    private static final double HALF_HEIGHT_MIN = 60.0;
    private static final double HALF_HEIGHT_SPAN = 80.0;
    /** Center jitter within the cell, keeps islands from touching. */
    private static final double JITTER_XZ = 200.0;
    private static final double JITTER_Y = 80.0;

    /**
     * Edge-noise amplitude in normalized box space — multiplied by half-width
     * it yields ±50..100 block jaggedness on the silhouette.
     */
    private static final double EDGE_NOISE = 0.06;
    /** Blocks beyond the max island extent that a candidate island can influence. */
    private static final double INFLUENCE_MARGIN = HALF_WIDTH_MIN + HALF_WIDTH_SPAN + JITTER_XZ
        + EDGE_NOISE * (HALF_WIDTH_MIN + HALF_WIDTH_SPAN) + 16;

    /** Superellipse exponent — 8 reads as a box with rounded corners. */
    private static final int SHAPE_EXPONENT = 8;

    /** Depth under the surface that counts as grass / dirt before stone. */
    private static final double GRASS_BAND = 0.05;
    private static final double DIRT_BAND = 0.22;

    private static final BlockState STONE = Blocks.STONE.defaultBlockState();
    private static final BlockState DIRT = Blocks.DIRT.defaultBlockState();
    private static final BlockState GRASS_BLOCK = Blocks.GRASS_BLOCK.defaultBlockState();

    private final long seed;

    public AllvrIslandFieldGenerator(long seed) {
        this.seed = seed;
    }

    /**
     * Fills the cube's 8 sections from the density field. Air voxels are
     * skipped (sections already default to air).
     */
    public void generate(AllvrCube cube) {
        AllvrCubePos cpos = cube.getPos();
        int x0 = cpos.minBlockX();
        int y0 = cpos.minBlockY();
        int z0 = cpos.minBlockZ();

        Island[] islands = collectIslands(x0, y0, z0);
        if (islands.length == 0) {
            return;
        }

        for (int sy = 0; sy < AllvrCube.SECTIONS_PER_CUBE; sy++) {
            LevelChunkSection section = cube.getSections()[sy];
            for (int ly = 0; ly < 16; ly++) {
                int wy = y0 + (sy << 4) + ly;
                for (int lz = 0; lz < 16; lz++) {
                    int wz = z0 + lz;
                    for (int lx = 0; lx < 16; lx++) {
                        int wx = x0 + lx;
                        BlockState state = evaluate(wx, wy, wz, islands);
                        if (state != null) {
                            section.setBlockState(lx, ly, lz, state, false);
                        }
                    }
                }
            }
        }
    }

    /** Islands whose influence overlaps the cube AABB (at most ~27 candidates). */
    private Island[] collectIslands(int x0, int y0, int z0) {
        int minX = Math.floorDiv(x0 - (int) INFLUENCE_MARGIN, SPACING_XZ);
        int maxX = Math.floorDiv(x0 + 31 + (int) INFLUENCE_MARGIN, SPACING_XZ);
        int minY = Math.floorDiv(y0 - (int) INFLUENCE_MARGIN, SPACING_Y);
        int maxY = Math.floorDiv(y0 + 31 + (int) INFLUENCE_MARGIN, SPACING_Y);
        int minZ = Math.floorDiv(z0 - (int) INFLUENCE_MARGIN, SPACING_XZ);
        int maxZ = Math.floorDiv(z0 + 31 + (int) INFLUENCE_MARGIN, SPACING_XZ);

        Island[] out = new Island[(maxX - minX + 1) * (maxY - minY + 1) * (maxZ - minZ + 1)];
        int n = 0;
        for (int cx = minX; cx <= maxX; cx++) {
            for (int cy = minY; cy <= maxY; cy++) {
                for (int cz = minZ; cz <= maxZ; cz++) {
                    out[n++] = islandAt(cx, cy, cz);
                }
            }
        }
        return out;
    }

    /** Seed-stable island parameters for a lattice cell. */
    private Island islandAt(int cellX, int cellY, int cellZ) {
        long h = mix(seed
            ^ cellX * 0x9E3779B97F4A7C15L
            ^ cellY * 0xBF58476D1CE4E5B9L
            ^ cellZ * 0x94D049BB133111EBL);

        double halfWidth = HALF_WIDTH_MIN + HALF_WIDTH_SPAN * frac(h);
        double halfHeight = HALF_HEIGHT_MIN + HALF_HEIGHT_SPAN * frac(h >>> 21);
        double jitterX = (frac(h >>> 42) * 2.0 - 1.0) * JITTER_XZ;
        double jitterY = (frac(mix(h ^ 0x165667B19E3779F9L)) * 2.0 - 1.0) * JITTER_Y;
        double jitterZ = (frac(mix(h ^ 0x27D4EB2F165667C5L)) * 2.0 - 1.0) * JITTER_XZ;

        // odd vertical layers shift half a cell on both horizontal axes, so
        // consecutive layers never stack column-aligned and gaps leak skylight
        double layerOffsetX = ((cellY & 1) != 0) ? SPACING_XZ * 0.5 : 0.0;
        double layerOffsetZ = ((cellY & 1) != 0) ? SPACING_XZ * 0.5 : 0.0;

        double cx = cellX * (double) SPACING_XZ + SPACING_XZ * 0.5 + jitterX + layerOffsetX;
        double cy = cellY * (double) SPACING_Y + jitterY;
        double cz = cellZ * (double) SPACING_XZ + SPACING_XZ * 0.5 + jitterZ + layerOffsetZ;

        return new Island(cx, cy, cz, halfWidth, halfHeight, h);
    }

    /**
     * Solid material for a world voxel, or {@code null} for air. Interior
     * voxels skip the edge FBM entirely.
     */
    private BlockState evaluate(int wx, int wy, int wz, Island[] islands) {
        double bestDepth = 0.0;
        boolean solid = false;
        for (Island island : islands) {
            double ax = Math.abs(wx - island.cx);
            if (ax > island.halfWidth + 96) {
                continue;
            }
            double ay = Math.abs(wy - island.cy);
            if (ay > island.halfHeight + 96) {
                continue;
            }
            double az = Math.abs(wz - island.cz);
            if (az > island.halfWidth + 96) {
                continue;
            }

            double nx = ax / island.halfWidth;
            double ny = ay / island.halfHeight;
            double nz = az / island.halfWidth;

            // superellipse (p=8) rounded box, d < 0 inside
            double a = nx * nx;
            double b = ny * ny;
            double c = nz * nz;
            double a4 = a * a;
            double b4 = b * b;
            double c4 = c * c;
            double q = a4 * a4 + b4 * b4 + c4 * c4;
            double d = Math.pow(q, 1.0 / SHAPE_EXPONENT) - 1.0;

            // jagged edge: only the narrow shell band pays for the FBM
            if (d > -EDGE_NOISE * 2.0 && d < EDGE_NOISE * 2.0) {
                d += fbm2(wx, wy, wz, island.hash) * EDGE_NOISE;
            }

            if (d < 0.0) {
                solid = true;
                double depth = -d;
                if (depth > bestDepth) {
                    bestDepth = depth;
                }
            }
        }

        if (!solid) {
            return null;
        }
        if (bestDepth < GRASS_BAND) {
            return GRASS_BLOCK;
        }
        if (bestDepth < DIRT_BAND) {
            return DIRT;
        }
        return STONE;
    }

    /** Two octaves of value noise in [-1, 1]-ish, seed-stable per island. */
    private static double fbm2(int x, int y, int z, long islandHash) {
        double n = valueNoise(x * 0.020, y * 0.030, z * 0.020, islandHash)
            + 0.5 * valueNoise(x * 0.043, y * 0.061, z * 0.043, islandHash ^ 0x2545F4914F6CDD1DL);
        return n / 1.5;
    }

    /** Trilinear value noise; lattice corners hash to [-1, 1]. */
    private static double valueNoise(double x, double y, double z, long hashSeed) {
        int x0 = (int) Math.floor(x);
        int y0 = (int) Math.floor(y);
        int z0 = (int) Math.floor(z);
        double xf = x - x0;
        double yf = y - y0;
        double zf = z - z0;
        double u = xf * xf * (3.0 - 2.0 * xf);
        double v = yf * yf * (3.0 - 2.0 * yf);
        double w = zf * zf * (3.0 - 2.0 * zf);

        double c000 = corner(x0, y0, z0, hashSeed);
        double c100 = corner(x0 + 1, y0, z0, hashSeed);
        double c010 = corner(x0, y0 + 1, z0, hashSeed);
        double c110 = corner(x0 + 1, y0 + 1, z0, hashSeed);
        double c001 = corner(x0, y0, z0 + 1, hashSeed);
        double c101 = corner(x0 + 1, y0, z0 + 1, hashSeed);
        double c011 = corner(x0, y0 + 1, z0 + 1, hashSeed);
        double c111 = corner(x0 + 1, y0 + 1, z0 + 1, hashSeed);

        double x00 = c000 + (c100 - c000) * u;
        double x10 = c010 + (c110 - c010) * u;
        double x01 = c001 + (c101 - c001) * u;
        double x11 = c011 + (c111 - c011) * u;
        double y0v = x00 + (x10 - x00) * v;
        double y1v = x01 + (x11 - x01) * v;
        return y0v + (y1v - y0v) * w;
    }

    private static double corner(int x, int y, int z, long hashSeed) {
        long h = mix(hashSeed
            ^ x * 0x9E3779B97F4A7C15L
            ^ y * 0xC2B2AE3D27D4EB4FL
            ^ z * 0x165667B19E3779F9L);
        return frac(h) * 2.0 - 1.0;
    }

    private static long mix(long h) {
        h ^= h >>> 33;
        h *= 0xFF51AFD7ED558CCDL;
        h ^= h >>> 33;
        h *= 0xC4CEB9FE1A85EC53L;
        h ^= h >>> 33;
        return h;
    }

    private static double frac(long h) {
        return (h >>> 11) * 0x1.0p-53;
    }

    private record Island(double cx, double cy, double cz, double halfWidth, double halfHeight, long hash) {
    }
}
