package com.iridium126.createmanaindustry.client.dimension;

import net.minecraft.core.BlockPos;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.LightTexture;
import net.minecraft.world.level.block.state.BlockState;

import com.iridium126.createmanaindustry.dimension.cube.AllvrCube;
import com.iridium126.createmanaindustry.dimension.cube.AllvrCubePos;

/**
 * Synthetic packed light for entities/block entities inside the allay
 * dimension (doc §10.4) — the vanilla light engine has no data there (no
 * column light is ever computed), so entity rendering would be pitch dark
 * without this.
 * <ul>
 *   <li><b>sky</b>: bounded upward window (K=128 blocks) through the client
 *       cube cache — unobstructed → 15. The column-top heuristic is
 *       impossible in a layered island world (every column has an island at
 *       +30M), hence the bounded ray; gaps between layers leak light
 *       physically correctly. No gradient — 15 or 0 (V0 simplification).</li>
 *   <li><b>block</b>: the streamed emitter tables of the 3×3×3 cube
 *       neighborhood, {@code max(emission - manhattanDistance)} — a
 *       conservative stand-in for vanilla BFS propagation.</li>
 * </ul>
 * The day cycle needs no handling here: vanilla multiplies sky light 15 by
 * the lightmap curve downstream.
 */
public final class AllvrLightSampler {

    /** Bounded upward sky-exposure window (blocks). */
    private static final int SKY_WINDOW = 128;
    private static final int NEIGHBORHOOD = 1; // cubes each way for block light

    public static int sample(ClientLevel level, BlockPos pos) {
        int sky = sampleSky(pos);
        int block = sampleBlock(pos);
        return LightTexture.pack(block, sky);
    }

    private static int sampleSky(BlockPos pos) {
        BlockPos.MutableBlockPos cursor = new BlockPos.MutableBlockPos();
        for (int i = 1; i <= SKY_WINDOW; i++) {
            cursor.set(pos.getX(), pos.getY() + i, pos.getZ());
            BlockState state = AllvrClientCubeCache.getBlockState(cursor);
            if (!state.isAir() && state.canOcclude()) {
                return 0;
            }
        }
        return 15;
    }

    private static int sampleBlock(BlockPos pos) {
        int best = 0;
        AllvrCubePos center = AllvrCubePos.of(pos);
        for (int dy = -NEIGHBORHOOD; dy <= NEIGHBORHOOD; dy++) {
            for (int dz = -NEIGHBORHOOD; dz <= NEIGHBORHOOD; dz++) {
                for (int dx = -NEIGHBORHOOD; dx <= NEIGHBORHOOD; dx++) {
                    AllvrCube cube = AllvrClientCubeCache.peekCube(new BlockPos(
                        center.minBlockX() + dx * 32, center.minBlockY() + dy * 32,
                        center.minBlockZ() + dz * 32));
                    if (cube == null || cube.getEmitters().isEmpty()) {
                        continue;
                    }
                    for (it.unimi.dsi.fastutil.ints.Int2IntMap.Entry e : cube.getEmitters().int2IntEntrySet()) {
                        int cell = e.getIntKey();
                        int ex = cube.getPos().minBlockX() + (cell & 31);
                        int ey = cube.getPos().minBlockY() + (cell >> 10);
                        int ez = cube.getPos().minBlockZ() + ((cell >> 5) & 31);
                        int dist = Math.abs(ex - pos.getX()) + Math.abs(ey - pos.getY())
                            + Math.abs(ez - pos.getZ());
                        best = Math.max(best, e.getIntValue() - dist);
                    }
                }
            }
        }
        return Math.max(0, Math.min(15, best));
    }

    private AllvrLightSampler() {}
}
