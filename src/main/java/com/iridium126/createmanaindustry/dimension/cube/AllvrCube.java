package com.iridium126.createmanaindustry.dimension.cube;

import it.unimi.dsi.fastutil.ints.Int2IntOpenHashMap;
import it.unimi.dsi.fastutil.ints.Int2ObjectOpenHashMap;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.core.Registry;
import net.minecraft.core.registries.Registries;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.biome.Biomes;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunkSection;

/**
 * A 32×32×32 cube — the allay dimension's unit of block data, mirroring
 * {@code ChunkAccess} for the vertical axis. Holds 8 vanilla
 * {@link LevelChunkSection}s (the cube is exactly 2×2×2 sections per axis)
 * plus the cube's block entities.
 * <p>
 * Sections default to air with a plains biome (vanilla
 * {@link LevelChunkSection#LevelChunkSection(Registry)} defaults), matching
 * the dimension's fixed-biome design. Storage is in-memory only until the
 * region3d persistence stage (roadmap phase 6).
 */
public final class AllvrCube {

    public static final int SECTIONS_PER_CUBE = 8;

    private final AllvrCubePos pos;
    private final LevelChunkSection[] sections = new LevelChunkSection[SECTIONS_PER_CUBE];
    /**
     * Block entities keyed by the 15-bit in-cube cell index. Never key by
     * {@code BlockPos#asLong} here — its Y packing is only 12 bit, which
     * aliases positions beyond the vanilla build height.
     */
    private final Int2ObjectOpenHashMap<BlockEntity> blockEntities = new Int2ObjectOpenHashMap<>();
    /**
     * Light-emitting blocks (cell index → emission) — the wire-format "light
     * source events" of the cube. Maintained on setBlock on the server,
     * filled from the packet on the client; consumed by the phase-3
     * synthetic light sampler. The island generator only produces
     * stone/dirt/grass, so generated cubes start without emitters.
     */
    private final Int2IntOpenHashMap emitters = new Int2IntOpenHashMap();

    public AllvrCube(AllvrCubePos pos, Registry<Biome> biomeRegistry) {
        this.pos = pos;
        for (int i = 0; i < SECTIONS_PER_CUBE; i++) {
            sections[i] = new LevelChunkSection(biomeRegistry);
        }
    }

    public AllvrCubePos getPos() {
        return pos;
    }

    /**
     * Slice index within the 2×2×2 section grid, Y-major (each s* = 0..1) —
     * the single source shared by the block accessors and the island
     * generator's fill loop; the packet streams sections in plain array
     * order, so both sides agree without encoding the convention.
     */
    public static int sliceIndex(int ssx, int ssy, int ssz) {
        return (ssy << 2) | (ssz << 1) | ssx;
    }

    /** Section array index for block-local coords (0..31 per axis). */
    private static int sectionIndex(int lx, int ly, int lz) {
        return sliceIndex(lx >> 4, ly >> 4, lz >> 4);
    }

    public LevelChunkSection[] getSections() {
        return sections;
    }

    public BlockState getBlockState(BlockPos worldPos) {
        int lx = AllvrCoords.blockToLocal(worldPos.getX());
        int ly = AllvrCoords.blockToLocal(worldPos.getY());
        int lz = AllvrCoords.blockToLocal(worldPos.getZ());
        return sections[sectionIndex(lx, ly, lz)].getBlockState(lx & 15, ly & 15, lz & 15);
    }

    /**
     * Writes a block state. Returns the previous state, or {@code null} when
     * the new state equals the old one (mirrors
     * {@code LevelChunkSection#setBlockState} semantics used by
     * {@code Level#setBlock}).
     */
    public BlockState setBlockState(BlockPos worldPos, BlockState state, boolean useLocks) {
        int lx = AllvrCoords.blockToLocal(worldPos.getX());
        int ly = AllvrCoords.blockToLocal(worldPos.getY());
        int lz = AllvrCoords.blockToLocal(worldPos.getZ());
        return sections[sectionIndex(lx, ly, lz)].setBlockState(lx & 15, ly & 15, lz & 15, state, useLocks);
    }

    // ---- block entities -------------------------------------------------

    /** In-cube cell index (0..32767) for a world position inside this cube. */
    public static int localIndex(BlockPos worldPos) {
        return (AllvrCoords.blockToLocal(worldPos.getY()) << 10)
            | (AllvrCoords.blockToLocal(worldPos.getZ()) << 5)
            | AllvrCoords.blockToLocal(worldPos.getX());
    }

    public BlockEntity getBlockEntity(BlockPos worldPos) {
        return blockEntities.get(localIndex(worldPos));
    }

    public void putBlockEntity(BlockPos worldPos, BlockEntity be) {
        blockEntities.put(localIndex(worldPos), be);
    }

    public BlockEntity removeBlockEntity(BlockPos worldPos) {
        return blockEntities.remove(localIndex(worldPos));
    }

    public Int2ObjectOpenHashMap<BlockEntity> getBlockEntities() {
        return blockEntities;
    }

    // ---- light emitters ----------------------------------------------------

    public void putEmitter(BlockPos worldPos, int emission) {
        emitters.put(localIndex(worldPos), emission);
    }

    public void removeEmitter(BlockPos worldPos) {
        emitters.remove(localIndex(worldPos));
    }

    public Int2IntOpenHashMap getEmitters() {
        return emitters;
    }

    /** Holder of the cube's uniform biome (plains), for future consumers. */
    public static Holder<Biome> defaultBiome(Registry<Biome> biomeRegistry) {
        return biomeRegistry.getHolderOrThrow(Biomes.PLAINS);
    }
}
