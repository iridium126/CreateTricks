package com.iridium126.createmanaindustry.dimension.cube;

/**
 * Block/cube/section coordinate conversions for the allay dimension.
 * <p>
 * Ported from CubicChunksCore {@code utils/Coords.java} (MIT), pinned to the
 * fixed cube diameter of 32 blocks (CC3's configurable 16/32/64/128 is cut —
 * one cube always spans exactly 2×2×2 vanilla 16³ sections).
 */
public final class AllvrCoords {

    public static final int DIAMETER_IN_BLOCKS = 32;
    public static final int DIAMETER_IN_SECTIONS = 2;
    public static final int LOG2_DIAMETER = 5;

    /** block coordinate → cube coordinate (arithmetic shift handles negatives). */
    public static int blockToCube(int blockPos) {
        return blockPos >> LOG2_DIAMETER;
    }

    /** cube coordinate → minimum block coordinate of the cube. */
    public static int cubeToMinBlock(int cubePos) {
        return cubePos << LOG2_DIAMETER;
    }

    /** block coordinate → local coordinate inside its cube (0..31). */
    public static int blockToLocal(int blockPos) {
        return blockPos & (DIAMETER_IN_BLOCKS - 1);
    }

    /** cube coordinate + local section index (0..1) → vanilla section coordinate. */
    public static int cubeToSection(int cubePos, int localSection) {
        return cubePos << 1 | localSection;
    }

    /** vanilla section coordinate → cube coordinate. */
    public static int sectionToCube(int sectionPos) {
        return sectionPos >> 1;
    }

    private AllvrCoords() {}
}
