package com.iridium126.createmanaindustry.dimension.lod;

/**
 * The 4c level/band table (grilling 2026-09-06, fixed absolute distances —
 * the view distance only caps the outer edge): full-resolution cube streaming
 * covers 0–256, then L0 256–512, L1 512–1024, L2 1024–2048, L3 2048–R.
 * Vertical range equals the horizontal band (Chebyshev metric, cube-aligned,
 * so the full-res↔LOD boundary never double-covers or gaps).
 * <p>
 * Kept as mutable-in-principle constants: if the 4c-1 smoke fill-time
 * measurement disappoints, demoting the L2 band to L3 is a one-line change
 * here (grilling Q5 decision).
 */
public final class AllvrLodBands {

    public static final int MAX_LEVEL = AllvrLodPos.MAX_LEVEL;

    /** Chebyshev block distance where level L's band starts. */
    private static final int[] BAND_MIN = {256, 512, 1024, 2048};

    public static int bandMin(int level) {
        return BAND_MIN[level];
    }

    /** Band outer edge for a level: fixed table, capped by the view distance.
     *  The top level's band is inclusive of the edge (its upper bound IS R). */
    public static int bandMax(int level, int viewDistanceBlocks) {
        int fixed = level >= MAX_LEVEL ? Integer.MAX_VALUE : BAND_MIN[level + 1];
        return Math.min(fixed, Math.max(viewDistanceBlocks, BAND_MIN[MAX_LEVEL]));
    }

    public static boolean inBand(int level, int chebyshevBlocks, int viewDistanceBlocks) {
        return chebyshevBlocks >= BAND_MIN[level] && chebyshevBlocks <= bandMax(level, viewDistanceBlocks);
    }

    /** Cells per axis of a level's surface-node bitmap box (player-centered,
     *  spanning ±bandMax). L0–L2 → 32³, L3 at R=2048 → 16³ (R=4096 → 32³). */
    public static int bitmapBoxCells(int level, int viewDistanceBlocks) {
        return 2 * bandMax(level, viewDistanceBlocks) / cellBlocks(level);
    }

    /** Player movement (in the level's own cells) before the bitmap is
     *  recomputed and resent — 8 cells at L0–L2, box/4 for narrow L3 boxes. */
    public static int resendThresholdCells(int level, int viewDistanceBlocks) {
        return Math.max(1, bitmapBoxCells(level, viewDistanceBlocks) / 4);
    }

    /** Blocks per cell of a level's node grid (32, 64, 128, 256). */
    public static int cellBlocks(int level) {
        return 32 << level;
    }

    private AllvrLodBands() {}
}
