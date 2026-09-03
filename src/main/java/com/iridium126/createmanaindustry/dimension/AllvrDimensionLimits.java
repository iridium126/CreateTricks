package com.iridium126.createmanaindustry.dimension;

import net.minecraft.core.BlockPos;

/**
 * Software play-area bounds of the allay dimension.
 * <p>
 * The cube coordinate encoding ({@code AllvrCubePos}, 21 bit per axis) can
 * address ±33,554,431 blocks; these limits are the tighter gameplay window
 * enforced at block-write and entity-movement time. XZ deliberately matches
 * the vanilla default world border (±29,999,984, derived from the 26 bit
 * BlockPos X/Z packing) so the dimension's horizontal play area is identical
 * to the overworld's.
 */
public final class AllvrDimensionLimits {

    /** Soft Y boundary, ±. Inside the CubePos encoding range with ~11% headroom. */
    public static final int Y_BOUND = 30_000_000;

    /** Horizontal boundary, ±. Equals the vanilla default world border. */
    public static final int XZ_BOUND = 29_999_984;

    public static boolean isInBounds(BlockPos pos) {
        return isInBounds(pos.getX(), pos.getY(), pos.getZ());
    }

    public static boolean isInBounds(int x, int y, int z) {
        return Math.abs(x) <= XZ_BOUND && Math.abs(y) <= Y_BOUND && Math.abs(z) <= XZ_BOUND;
    }

    public static int clampY(int y) {
        return Math.max(-Y_BOUND, Math.min(Y_BOUND, y));
    }

    private AllvrDimensionLimits() {}
}
