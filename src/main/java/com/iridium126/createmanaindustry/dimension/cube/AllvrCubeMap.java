package com.iridium126.createmanaindustry.dimension.cube;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Registry;
import net.minecraft.core.registries.Registries;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;

import com.iridium126.createmanaindustry.CreateManaIndustry;
import com.iridium126.createmanaindustry.dimension.AllvrDimensionLimits;
import com.iridium126.createmanaindustry.dimension.gen.AllvrIslandFieldGenerator;

/**
 * Server-side registry of loaded cubes for one allay-dimension
 * {@link ServerLevel} — the cube-world analogue of {@code ChunkMap} +
 * {@code ClientChunkCache}, kept deliberately minimal for phase 1:
 * <ul>
 *   <li>block access: {@code Level}'s get/setBlockState mixins route here
 *       when the level is the allay dimension;</li>
 *   <li>loading: cubes generate on demand (synchronous) for direct queries
 *       and, around players, via a per-tick time budget in shell order
 *       (nearest first). No vanilla ticket machinery is involved — columns
 *       keep their vanilla (empty) lifecycle untouched;</li>
 *   <li>persistence: none yet — islands regenerate deterministically from the
 *       world seed (roadmap phase 6 adds region3d IO), so cubes are kept for
 *       the whole session to preserve player edits;</li>
 *   <li>light: nothing here touches the vanilla light engine; rendering light
 *       is client-side (roadmap phase 5), gameplay light queries stay vanilla
 *       defaults until the gameplay stage (phase 7).</li>
 * </ul>
 * All access happens on the server thread.
 */
public final class AllvrCubeMap {

    /** Per-player ring load radius, in cubes (mirrors CC3 verticalViewDistance=8). */
    private static final int VIEW_RADIUS = 8;
    /** Shell-load time budget per tick. */
    private static final long TICK_BUDGET_NANOS = 3_000_000L;
    /**
     * Session cube cap. Uniform island-interior cubes are a few hundred bytes
     * but shell cubes carry real section data; past the cap only cubes within
     * ring 2 of a player generate (protects against runaway memory on very
     * long sessions).
     */
    private static final int MAX_LOADED_CUBES = 250_000;

    private final ServerLevel level;
    private final Registry<net.minecraft.world.level.biome.Biome> biomeRegistry;
    private final AllvrIslandFieldGenerator generator;
    private final Long2ObjectOpenHashMap<AllvrCube> cubes = new Long2ObjectOpenHashMap<>();
    private final Map<UUID, AllvrCubePos> playerLastCube = new java.util.HashMap<>();
    private boolean loggedCapWarning;

    public AllvrCubeMap(ServerLevel level) {
        this.level = level;
        this.biomeRegistry = level.registryAccess().registryOrThrow(Registries.BIOME);
        this.generator = new AllvrIslandFieldGenerator(level.getSeed());
    }

    public ServerLevel getLevel() {
        return level;
    }

    // ------------------------------------------------------------------
    // block access (called from the Level mixins)
    // ------------------------------------------------------------------

    /**
     * Block state of a position within the dimension. Unloaded cubes read as
     * void air — mirroring vanilla's behavior for unloaded chunks and keeping
     * incidental vanilla scans (light engine, collisions over borders) from
     * triggering mass generation.
     */
    public BlockState getBlockState(BlockPos pos) {
        if (!AllvrDimensionLimits.isInBounds(pos)) {
            return net.minecraft.world.level.block.Blocks.VOID_AIR.defaultBlockState();
        }
        AllvrCube cube = cubes.get(AllvrCubePos.asLong(pos));
        return cube == null ? net.minecraft.world.level.block.Blocks.VOID_AIR.defaultBlockState()
            : cube.getBlockState(pos);
    }

    public boolean setBlock(BlockPos pos, BlockState newState, int flags, int recursionLeft) {
        if (!AllvrDimensionLimits.isInBounds(pos)) {
            return false;
        }
        // vanilla Level#setBlock loads (generates) the target chunk before
        // writing — mirror that: the cube is generated on demand here. Reads
        // stay passive (getBlockState does not generate), which no vanilla
        // tick path crosses at cube-only Y positions.
        AllvrCube cube = getOrGenerate(AllvrCubePos.of(pos));
        pos = pos.immutable();
        BlockState oldState = cube.setBlockState(pos, newState, false);
        if (oldState == null) {
            return false;
        }

        updateBlockEntity(cube, pos, oldState, newState);

        // mirror of Level#markAndNotifyBlock, minus client sync / light engine
        // / chunk-status concerns (cubes have no LevelChunk)
        if ((flags & 2) != 0) {
            // sendBlockUpdated: skipped until cubes sync to clients with
            // dedicated packets (roadmap phase 2); vanilla chunk broadcast
            // never sees cube positions
        }
        if ((flags & 1) != 0) {
            level.blockUpdated(pos, oldState.getBlock());
            if (newState.hasAnalogOutputSignal()) {
                level.updateNeighbourForOutputSignal(pos, newState.getBlock());
            }
        }
        if ((flags & 16) == 0 && recursionLeft > 0) {
            int i = flags & -34;
            oldState.updateIndirectNeighbourShapes(level, pos, i, recursionLeft - 1);
            newState.updateNeighbourShapes(level, pos, i, recursionLeft - 1);
            newState.updateIndirectNeighbourShapes(level, pos, i, recursionLeft - 1);
        }
        return true;
    }

    private void updateBlockEntity(AllvrCube cube, BlockPos pos, BlockState oldState, BlockState newState) {
        if (oldState.hasBlockEntity() && !newState.hasBlockEntity()) {
            BlockEntity be = cube.removeBlockEntity(pos);
            if (be != null) {
                be.setRemoved();
            }
        }
        if (newState.hasBlockEntity() && newState.getBlock() instanceof net.minecraft.world.level.block.EntityBlock entityBlock) {
            BlockEntity be = entityBlock.newBlockEntity(pos, newState);
            if (be != null) {
                be.setLevel(level);
                cube.putBlockEntity(pos, be);
            }
        }
    }

    public BlockEntity getBlockEntity(BlockPos pos) {
        AllvrCube cube = cubes.get(AllvrCubePos.asLong(pos));
        return cube == null ? null : cube.getBlockEntity(pos);
    }

    public int getLoadedCubeCount() {
        return cubes.size();
    }

    // ------------------------------------------------------------------
    // loading
    // ------------------------------------------------------------------

    /** Synchronous generation/lookup used by the tick driver and direct paths. */
    public AllvrCube getOrGenerate(AllvrCubePos cpos) {
        return getOrGenerate(cpos.getX(), cpos.getY(), cpos.getZ());
    }

    /** Synchronous generation/lookup used by the tick driver and direct paths. */
    public AllvrCube getOrGenerate(int cubeX, int cubeY, int cubeZ) {
        long key = AllvrCubePos.asLong(cubeX, cubeY, cubeZ);
        AllvrCube cube = cubes.get(key);
        if (cube != null) {
            return cube;
        }
        cube = new AllvrCube(AllvrCubePos.of(cubeX, cubeY, cubeZ), biomeRegistry);
        generator.generate(cube);
        cubes.put(key, cube);
        return cube;
    }

    /**
     * Per-tick driver: on join/teleport synchronously fills the player's
     * 3×3×3 cube neighborhood (so {@code /data get block} right after a
     * {@code tp} sees real terrain), then loads rings outward in shell order
     * within the per-tick time budget.
     */
    public void tick() {
        List<ServerPlayer> players = level.players();
        if (players.isEmpty()) {
            return;
        }

        for (ServerPlayer player : players) {
            AllvrCubePos pc = AllvrCubePos.of(player.blockPosition());
            AllvrCubePos last = playerLastCube.get(player.getUUID());
            if (last == null || chebyshev(pc, last) > 2) {
                for (int dy = -1; dy <= 1; dy++) {
                    for (int dx = -1; dx <= 1; dx++) {
                        for (int dz = -1; dz <= 1; dz++) {
                            getOrGenerate(pc.getX() + dx, pc.getY() + dy, pc.getZ() + dz);
                        }
                    }
                }
                playerLastCube.put(player.getUUID(), pc);
            }
        }

        long deadline = System.nanoTime() + TICK_BUDGET_NANOS;
        boolean capReached = cubes.size() >= MAX_LOADED_CUBES;
        if (capReached && !loggedCapWarning) {
            loggedCapWarning = true;
            CreateManaIndustry.LOGGER.warn("[Allvr] loaded cube cap {} reached, limiting load radius", MAX_LOADED_CUBES);
        }

        for (ServerPlayer player : players) {
            AllvrCubePos pc = AllvrCubePos.of(player.blockPosition());
            for (int r = 0; r <= VIEW_RADIUS; r++) {
                if (System.nanoTime() > deadline) {
                    return;
                }
                int capR = capReached ? 2 : r;
                if (r > capR) {
                    continue;
                }
                for (int dy = -r; dy <= r; dy++) {
                    for (int dx = -r; dx <= r; dx++) {
                        for (int dz = -r; dz <= r; dz++) {
                            if (Math.max(Math.max(Math.abs(dx), Math.abs(dy)), Math.abs(dz)) != r) {
                                continue;
                            }
                            long key = AllvrCubePos.asLong(pc.getX() + dx, pc.getY() + dy, pc.getZ() + dz);
                            if (!cubes.containsKey(key)) {
                                getOrGenerate(pc.getX() + dx, pc.getY() + dy, pc.getZ() + dz);
                                if (System.nanoTime() > deadline) {
                                    return;
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    private static int chebyshev(AllvrCubePos a, AllvrCubePos b) {
        return Math.max(Math.max(Math.abs(a.getX() - b.getX()), Math.abs(a.getY() - b.getY())),
            Math.abs(a.getZ() - b.getZ()));
    }

    // ------------------------------------------------------------------
    // debug helpers (used by /data-style server-side queries)
    // ------------------------------------------------------------------

    public AllvrCube getLoadedCube(long key) {
        return cubes.get(key);
    }

    /** Null when the cube is not loaded; never generates. */
    public AllvrCube peek(BlockPos pos) {
        return cubes.get(AllvrCubePos.asLong(pos));
    }
}
