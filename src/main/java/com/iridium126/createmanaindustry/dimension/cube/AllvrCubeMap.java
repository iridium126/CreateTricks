package com.iridium126.createmanaindustry.dimension.cube;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import it.unimi.dsi.fastutil.longs.LongArrayList;
import it.unimi.dsi.fastutil.longs.LongIterator;
import it.unimi.dsi.fastutil.longs.LongList;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
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
import com.iridium126.createmanaindustry.dimension.net.ClientboundAllvrBlockUpdatePacket;
import com.iridium126.createmanaindustry.dimension.net.ClientboundAllvrCubePacket;
import com.iridium126.createmanaindustry.dimension.net.ClientboundAllvrForgetCubePacket;

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

    /** Per-player server-memory load radius, in cubes (mirrors CC3 verticalViewDistance=8). */
    private static final int GEN_RADIUS = 8;
    /** Per-player client subscription radii (xz, y) — smaller vertically, islands span ~7 cubes. */
    private static final int SEND_XZ_RADIUS = 8;
    private static final int SEND_Y_RADIUS = 4;
    /** Forget margin beyond the send radii (hysteresis against jitter at the edge). */
    private static final int FORGET_XZ_RADIUS = SEND_XZ_RADIUS + 2;
    private static final int FORGET_Y_RADIUS = SEND_Y_RADIUS + 2;
    /** Max cubes streamed per player per tick. */
    private static final int SEND_BUDGET_PER_TICK = 24;
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
    private final Map<UUID, Subscription> subscriptions = new java.util.HashMap<>();
    private boolean loggedCapWarning;

    /** Per-player client subscription state: which cube keys have been streamed. */
    private static final class Subscription {
        final LongOpenHashSet sent = new LongOpenHashSet();
        AllvrCubePos lastCube;
    }

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

        // light emitter tracking (wire "light source events"; consumed by the
        // phase-3 synthetic light sampler)
        int oldEmission = oldState.getLightEmission(level, pos);
        int newEmission = newState.getLightEmission(level, pos);
        if (oldEmission > 0) {
            cube.removeEmitter(pos);
        }
        if (newEmission > 0) {
            cube.putEmitter(pos, newEmission);
        }

        // mirror of Level#markAndNotifyBlock, minus light engine / chunk-status
        // concerns (cubes have no LevelChunk)
        if ((flags & 2) != 0) {
            // vanilla sendBlockUpdated equivalent: authoritative per-block push
            // to subscribed clients (the initiating player's own prediction
            // re-applies the same state idempotently)
            sendBlockUpdate(pos, newState);
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

    /** Authoritative per-block push to every client subscribed to this cube
     *  (one packet build, N sends; level.players() is the allay dimension). */
    private void sendBlockUpdate(BlockPos pos, BlockState state) {
        long cubeKey = AllvrCubePos.asLong(pos);
        ClientboundAllvrBlockUpdatePacket packet = new ClientboundAllvrBlockUpdatePacket(
            cubeKey, AllvrCube.localIndex(pos), net.minecraft.world.level.block.Block.getId(state));
        for (ServerPlayer player : level.players()) {
            Subscription sub = subscriptions.get(player.getUUID());
            if (sub != null && sub.sent.contains(cubeKey)) {
                player.connection.send(packet);
            }
        }
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
     * Per-tick driver: on join/teleport synchronously generates + streams the
     * player's 3×3×3 cube neighborhood (so {@code /data get block} right
     * after a {@code tp} sees real terrain and the client can stand on it),
     * then generates/shells outward within the per-tick time budget while
     * streaming cube data to each player's client within the per-tick send
     * budget. Cubes leaving the subscription range (with hysteresis) are
     * forgotten client-side.
     */
    public void tick() {
        List<ServerPlayer> players = level.players();
        if (players.isEmpty()) {
            return;
        }

        long deadline = System.nanoTime() + TICK_BUDGET_NANOS;
        boolean capReached = cubes.size() >= MAX_LOADED_CUBES;
        if (capReached && !loggedCapWarning) {
            loggedCapWarning = true;
            CreateManaIndustry.LOGGER.warn("[Allvr] loaded cube cap {} reached, limiting load radius", MAX_LOADED_CUBES);
        }

        for (ServerPlayer player : players) {
            AllvrCubePos pc = AllvrCubePos.of(player.blockPosition());
            Subscription sub = subscriptions.computeIfAbsent(player.getUUID(), k -> new Subscription());
            if (sub.lastCube == null || chebyshev(pc, sub.lastCube) > 2) {
                for (int dy = -1; dy <= 1; dy++) {
                    for (int dx = -1; dx <= 1; dx++) {
                        for (int dz = -1; dz <= 1; dz++) {
                            AllvrCube cube = getOrGenerate(pc.getX() + dx, pc.getY() + dy, pc.getZ() + dz);
                            long key = cube.getPos().asLong();
                            if (sub.sent.add(key)) {
                                player.connection.send(ClientboundAllvrCubePacket.of(cube, level.registryAccess()));
                            }
                        }
                    }
                }
                sub.lastCube = pc;
            }
        }

        for (ServerPlayer player : players) {
            AllvrCubePos pc = AllvrCubePos.of(player.blockPosition());
            Subscription sub = subscriptions.computeIfAbsent(player.getUUID(), k -> new Subscription());
            if (capReached && chebyshev(pc, playerCubeCenter(sub)) > 2) {
                continue;
            }
            int sentCount = 0;
            genLoop:
            for (int r = 0; r <= GEN_RADIUS; r++) {
                for (int dy = -r; dy <= r; dy++) {
                    for (int dx = -r; dx <= r; dx++) {
                        for (int dz = -r; dz <= r; dz++) {
                            if (Math.max(Math.max(Math.abs(dx), Math.abs(dy)), Math.abs(dz)) != r) {
                                continue;
                            }
                            int cx = pc.getX() + dx;
                            int cy = pc.getY() + dy;
                            int cz = pc.getZ() + dz;
                            long key = AllvrCubePos.asLong(cx, cy, cz);
                            boolean inSendRange = Math.abs(dy) <= SEND_Y_RADIUS; // xz always <= GEN_RADIUS == SEND_XZ_RADIUS
                            if (sub.sent.contains(key) || (capReached && r > 2 && !inSendRange)) {
                                continue;
                            }
                            if (System.nanoTime() > deadline) {
                                break genLoop;
                            }
                            AllvrCube cube = getOrGenerate(cx, cy, cz);
                            if (inSendRange && sentCount < SEND_BUDGET_PER_TICK && sub.sent.add(key)) {
                                player.connection.send(ClientboundAllvrCubePacket.of(cube, level.registryAccess()));
                                sentCount++;
                            }
                        }
                    }
                }
            }

            forgetOutOfRange(player, pc, sub);
        }
    }

    private static AllvrCubePos playerCubeCenter(Subscription sub) {
        return sub.lastCube != null ? sub.lastCube : AllvrCubePos.of(0, 0, 0);
    }

    private void forgetOutOfRange(ServerPlayer player, AllvrCubePos pc, Subscription sub) {
        if (sub.sent.isEmpty()) {
            return;
        }
        LongIterator it = sub.sent.iterator();
        LongList forget = null;
        while (it.hasNext()) {
            long key = it.nextLong();
            AllvrCubePos cpos = AllvrCubePos.fromLong(key);
            int dxCube = cpos.getX() - pc.getX();
            int dyCube = cpos.getY() - pc.getY();
            int dzCube = cpos.getZ() - pc.getZ();
            if (Math.max(Math.abs(dxCube), Math.abs(dzCube)) > FORGET_XZ_RADIUS
                || Math.abs(dyCube) > FORGET_Y_RADIUS) {
                if (forget == null) {
                    forget = new LongArrayList();
                }
                forget.add(key);
            }
        }
        if (forget != null) {
            for (long key : forget) {
                sub.sent.remove(key);
                player.connection.send(new ClientboundAllvrForgetCubePacket(key));
            }
        }
    }

    /** Drops one player's subscription so cubes are re-streamed from scratch. */
    public void resetPlayer(UUID uuid) {
        subscriptions.remove(uuid);
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
