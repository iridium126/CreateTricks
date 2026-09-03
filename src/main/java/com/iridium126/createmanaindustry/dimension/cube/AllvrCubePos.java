package com.iridium126.createmanaindustry.dimension.cube;

import net.minecraft.core.BlockPos;

/**
 * Cube position with 21-bit packing per axis — ±33,554,431 blocks per axis,
 * which covers the ±30,000,000 Y requirement with ~11% headroom and the
 * ±29,999,984 XZ world border.
 * <p>
 * Long packing ported verbatim from CubicChunksCore {@code api/CubePos.java}
 * (MIT): the parity of the top two bits distinguishes cube longs (kept for
 * CubicChunks-ecosystem alignment even though this mod has no chunk longs to
 * collide with), and the top two bits of cube longs are inverted so that
 * {@link Long#MAX_VALUE} is not a valid position.
 *
 * <pre>
 * Positive Z CubePos long:  0b01ZZZZZZ ZZZZZZZZ ZZZZZZYY YYYYYYYY YYYYYYYY YYYXXXXX XXXXXXXX XXXXXXXX
 * Negative Z CubePos long:  0b10ZZZZZZ ZZZZZZZZ ZZZZZZYY YYYYYYYY YYYYYYYY YYYXXXXX XXXXXXXX XXXXXXXX
 * </pre>
 */
public final class AllvrCubePos {

    public static final long INVALID_CUBE_POS = Long.MAX_VALUE;
    public static final int MAX_COORDINATE_VALUE = 33554431 >> AllvrCoords.LOG2_DIAMETER;

    private static final long TOP_TWO_BITS_MASK = 0b11L << 62;

    private final int x;
    private final int y;
    private final int z;

    private AllvrCubePos(int x, int y, int z) {
        this.x = x;
        this.y = y;
        this.z = z;
    }

    public static AllvrCubePos of(int x, int y, int z) {
        return new AllvrCubePos(x, y, z);
    }

    public static AllvrCubePos of(BlockPos pos) {
        return new AllvrCubePos(AllvrCoords.blockToCube(pos.getX()),
            AllvrCoords.blockToCube(pos.getY()), AllvrCoords.blockToCube(pos.getZ()));
    }

    public static AllvrCubePos fromLong(long packed) {
        return new AllvrCubePos(extractX(packed), extractY(packed), extractZ(packed));
    }

    public int getX() {
        return x;
    }

    public int getY() {
        return y;
    }

    public int getZ() {
        return z;
    }

    /** Minimum block coordinate of this cube on X. */
    public int minBlockX() {
        return AllvrCoords.cubeToMinBlock(x);
    }

    /** Minimum block coordinate of this cube on Y. */
    public int minBlockY() {
        return AllvrCoords.cubeToMinBlock(y);
    }

    /** Minimum block coordinate of this cube on Z. */
    public int minBlockZ() {
        return AllvrCoords.cubeToMinBlock(z);
    }

    public long asLong() {
        return asLong(x, y, z);
    }

    public static long asLong(int x, int y, int z) {
        long i = 0L;
        i |= (long) x & (1 << 21) - 1;
        i |= ((long) y & (1 << 21) - 1) << 21;
        i |= ((long) z & (1 << 21) - 1) << 42;
        // cubes are marked by starting with 0b01 or 0b10
        if (i < (1L << 62)) i |= 1L << 63;
        // invert the top two bits so Long.MAX_VALUE is invalid
        i ^= TOP_TWO_BITS_MASK;
        return i;
    }

    public static long asLong(BlockPos pos) {
        return asLong(AllvrCoords.blockToCube(pos.getX()),
            AllvrCoords.blockToCube(pos.getY()), AllvrCoords.blockToCube(pos.getZ()));
    }

    public static int extractX(long packed) {
        return (int) (packed << 43 >> 43);
    }

    public static int extractY(long packed) {
        return (int) (packed << 22 >> 43);
    }

    public static int extractZ(long packed) {
        // re-invert the top two bits, they were inverted for storage
        packed ^= TOP_TWO_BITS_MASK;
        return (int) (packed << 1 >> 43);
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof AllvrCubePos other)) return false;
        return x == other.x && y == other.y && z == other.z;
    }

    @Override
    public int hashCode() {
        return (x * 92837111) ^ (y * 689287499) ^ (z * 283923481);
    }

    @Override
    public String toString() {
        return "AllvrCubePos{" + x + ", " + y + ", " + z + "}";
    }
}
