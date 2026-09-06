package com.iridium126.createmanaindustry.dimension.lod;

import net.minecraft.core.BlockPos;

import com.iridium126.createmanaindustry.dimension.cube.AllvrCubePos;

/**
 * One LOD node's identity: octree level + cell coordinate (= cube coordinate
 * shifted right by the level). A level-L node spans {@code 32 << L} blocks
 * per axis and is meshed as a 32³ snapshot sampled at stride {@code 1 << L}
 * (doc §13 4c).
 * <p>
 * Keys stay per-level: the cell long reuses the {@link AllvrCubePos} 21-bit
 * packing, which ALIASES full-res cube longs — every map holding LOD nodes
 * must therefore be keyed by {@code (level, cellLong)} with level as the map
 * identity (NodeStore keeps one map per level), never by a combined long.
 */
public record AllvrLodPos(int level, int cellX, int cellY, int cellZ) {

    public static final int MAX_LEVEL = 3;

    public static AllvrLodPos of(int level, int cellX, int cellY, int cellZ) {
        return new AllvrLodPos(level, cellX, cellY, cellZ);
    }

    /** The level-L node containing a block position. */
    public static AllvrLodPos containing(int level, BlockPos pos) {
        int shift = 5 + level;
        return new AllvrLodPos(level, pos.getX() >> shift, pos.getY() >> shift, pos.getZ() >> shift);
    }

    public static AllvrLodPos fromCellLong(int level, long cellLong) {
        AllvrCubePos cell = AllvrCubePos.fromLong(cellLong);
        return new AllvrLodPos(level, cell.getX(), cell.getY(), cell.getZ());
    }

    /** Node edge length in blocks (32, 64, 128, 256). */
    public int sizeBlocks() {
        return 32 << this.level;
    }

    /** Snapshot sample stride in blocks (1, 2, 4, 8). */
    public int stride() {
        return 1 << this.level;
    }

    public int minBlockX() {
        return this.cellX << (5 + this.level);
    }

    public int minBlockY() {
        return this.cellY << (5 + this.level);
    }

    public int minBlockZ() {
        return this.cellZ << (5 + this.level);
    }

    /** Long key of the cell — valid ONLY inside a per-level map (aliases cube
     *  longs; see class doc). */
    public long cellLong() {
        return AllvrCubePos.asLong(this.cellX, this.cellY, this.cellZ);
    }

    @Override
    public String toString() {
        return "L" + this.level + " cell(" + this.cellX + "," + this.cellY + "," + this.cellZ + ")";
    }
}
