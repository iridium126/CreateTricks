package com.iridium126.createmanaindustry.client.dimension;

import java.util.Arrays;

import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunkSection;
import net.minecraft.world.level.chunk.PalettedContainer;
import net.minecraft.world.level.material.FluidState;

import com.iridium126.createmanaindustry.CreateManaIndustry;
import com.iridium126.createmanaindustry.client.dimension.render.AllvrMesher;
import com.iridium126.createmanaindustry.dimension.AllvrDimensions;
import com.iridium126.createmanaindustry.dimension.cube.AllvrCube;
import com.iridium126.createmanaindustry.dimension.cube.AllvrCubePos;
import com.iridium126.createmanaindustry.dimension.net.ClientboundAllvrBlockUpdatePacket;
import com.iridium126.createmanaindustry.dimension.net.ClientboundAllvrCubePacket;

/**
 * Client-side registry of streamed cubes for the allay dimension — the cube
 * analogue of {@code ClientChunkCache}, filled by
 * {@code ClientboundAllvrCubePacket}/{@code ...ForgetCubePacket} and consumed
 * by {@code AllvrClientLevelMixin} (ClientLevel block reads inside the
 * dimension). No rendering yet (roadmap phase 3); the cache already powers
 * client-side collision, entity physics, block outlines and ray tracing, so a
 * player teleported onto an island stands on it.
 * <p>
 * Cache misses read as void air — the client never generates. All apply/forget
 * calls run on the main thread ({@code ctx.enqueueWork}); reads happen on the
 * client thread. Cleared on level unload / world switch (see
 * {@code CreateManaIndustryClient}).
 */
public final class AllvrClientCubeCache {

    /**
     * Guards the cube map and cube contents. Main-thread writers (apply /
     * forget / setBlock) and the mesher worker's snapshot reads both hold it —
     * {@code LevelChunkSection} objects are not thread-safe against
     * concurrent writes. Held briefly; no GL or world access inside.
     */
    public static final Object LOCK = new Object();

    private static ClientLevel level;
    private static final Long2ObjectOpenHashMap<AllvrCube> cubes = new Long2ObjectOpenHashMap<>();
    /** Cubes that hold block entities — the client ticking worklist (mirrors
     *  the server cube map's registry; most cubes are pure terrain). */
    private static final Long2ObjectOpenHashMap<AllvrCube> beCubes = new Long2ObjectOpenHashMap<>();

    /** Binds the current client level (called on LevelEvent.Load). */
    public static void onLevelChanged(ClientLevel clientLevel) {
        level = clientLevel;
    }

    /** Drops every streamed cube (level unload / dimension switch / logout). */
    public static void clear() {
        cubes.clear();
        beCubes.clear();
        level = null;
    }

    /** Main-thread apply of one streamed cube. */
    public static void applyCube(ClientboundAllvrCubePacket packet) {
        ClientLevel clientLevel = Minecraft.getInstance().level;
        if (clientLevel == null || clientLevel.dimension() != AllvrDimensions.ALLAY_LEVEL) {
            return;
        }
        level = clientLevel;
        AllvrCube cube = packet.decodeCube(clientLevel, clientLevel.registryAccess());
        synchronized (LOCK) {
            cubes.put(packet.cubePos(), cube);
            refreshBeCube(packet.cubePos(), cube);
        }
        com.iridium126.createmanaindustry.client.dimension.render.AllvrRenderer.INSTANCE.onCubeApplied(packet.cubePos());
        if (CreateManaIndustry.LOGGER.isDebugEnabled()) {
            CreateManaIndustry.LOGGER.debug("[Allvr] cube {} streamed ({} bytes, {} cubes cached)",
                cube.getPos(), packet.payload().length, cubes.size());
        }
    }

    public static void forgetCube(long cubePos) {
        synchronized (LOCK) {
            cubes.remove(cubePos);
            beCubes.remove(cubePos);
        }
        com.iridium126.createmanaindustry.client.dimension.render.AllvrRenderer.INSTANCE.onCubeForgotten(cubePos);
    }

    /** Keeps the block-entity worklist in step with a cube's BE set. */
    private static void refreshBeCube(long key, AllvrCube cube) {
        if (cube.hasBlockEntities()) {
            beCubes.put(key, cube);
        } else {
            beCubes.remove(key);
        }
    }

    /**
     * Client block-entity ticking — the client half of the cube BE tick loop
     * (vanilla also ticks BEs client-side; Create's rotation/mixer animations
     * are driven from here). Called from {@code ClientTickEvent.Post}; gated to
     * the streamed radii around the local player (a cube further out exists
     * only inside the forget hysteresis and must not tick — the client-side
     * stand-in for vanilla's simulation-distance gating).
     */
    public static void tickBlockEntities() {
        ClientLevel clientLevel = level;
        if (clientLevel == null || beCubes.isEmpty()) {
            return;
        }
        LocalPlayer player = Minecraft.getInstance().player;
        if (player == null) {
            return;
        }
        AllvrCubePos pc = AllvrCubePos.of(player.blockPosition());
        // snapshot: a ticker can write blocks (adding/removing BEs → registry writes)
        for (AllvrCube cube : beCubes.values().toArray(new AllvrCube[0])) {
            if (!cube.hasBlockEntities()) {
                continue;
            }
            AllvrCubePos cpos = cube.getPos();
            if (Math.abs(cpos.getX() - pc.getX()) <= 8 && Math.abs(cpos.getZ() - pc.getZ()) <= 8
                && Math.abs(cpos.getY() - pc.getY()) <= 4) {
                cube.tickBlockEntities(clientLevel);
            }
        }
    }

    /**
     * Applies one authoritative server-side block change. Routes through the
     * VANILLA confirmation path ({@code ClientPacketListener#handleBlockUpdate}
     * → {@code ClientLevel#setServerVerifiedBlockState}) rather than writing
     * the cache directly: with a pending client prediction for that position,
     * the handler absorbs the write — writing the cache directly leaves the
     * prediction handler's entry holding the pre-break state, so the later
     * prediction ACK's {@code syncBlockState} restores the broken block
     * (remeshing it back in) and rubber-bands the player standing in the hole
     * via {@code absMoveTo}. With no pending prediction it falls through to
     * {@code Level#setBlock} (flags 19, recursion 512) and the mixin writes the
     * cache normally — the vanilla semantics for a far-away authoritative
     * change (another player, /setblock).
     */
    public static void applyBlockUpdate(ClientboundAllvrBlockUpdatePacket packet) {
        ClientLevel clientLevel = level;
        if (clientLevel == null || clientLevel.dimension() != AllvrDimensions.ALLAY_LEVEL) {
            return; // level switched — the streamed cube died with it
        }
        com.iridium126.createmanaindustry.dimension.cube.AllvrCubePos pos =
            com.iridium126.createmanaindustry.dimension.cube.AllvrCubePos.fromLong(packet.cubePos());
        int cell = packet.cellIndex();
        BlockPos blockPos = new BlockPos(pos.minBlockX() + (cell & 31),
            pos.minBlockY() + (cell >> 10), pos.minBlockZ() + ((cell >> 5) & 31));
        clientLevel.setServerVerifiedBlockState(blockPos,
            net.minecraft.world.level.block.Block.stateById(packet.stateId()), 19);
    }

    /** The cached cube at a position's cube, or null (never generates). */
    public static AllvrCube peekCube(BlockPos pos) {
        synchronized (LOCK) {
            return cubes.get(com.iridium126.createmanaindustry.dimension.cube.AllvrCubePos.asLong(pos));
        }
    }

    /** Long-key variant for code that already works in cube keys (mesher
     *  light bake); same lock discipline as {@link #peekCube(BlockPos)}. */
    public static AllvrCube peekCube(long cubePos) {
        synchronized (LOCK) {
            return cubes.get(cubePos);
        }
    }

    /**
     * Client-side mirror of {@code AllvrCubeMap#setBlock} for the write paths
     * vanilla routes through {@code Level#setBlock} on the client (destroy /
     * place prediction, server confirmation packets). Keeps prediction writes
     * off the empty-shell column chunks, whose section arrays cannot address
     * cube-only Y positions ({@code LevelChunk#setBlockState} has no section
     * bounds check — an unchecked write crashes with AIOOBE).
     * <p>
     * Unloaded cubes reject the write ({@code false}), mirroring vanilla's
     * "write to unloaded chunk fails": a block the player can target is always
     * inside a streamed cube. Light-emitter bookkeeping mirrors the server so
     * the emitter table stays consistent for the phase-3 synthetic light.
     */
    public static boolean setBlock(BlockPos pos, BlockState newState, int flags, int recursionLeft) {
        ClientLevel clientLevel = level;
        if (clientLevel == null || !com.iridium126.createmanaindustry.dimension.AllvrDimensionLimits.isInBounds(pos)) {
            return false;
        }
        AllvrCube cube;
        BlockState oldState;
        synchronized (LOCK) {
            cube = cubes.get(com.iridium126.createmanaindustry.dimension.cube.AllvrCubePos.asLong(pos));
            if (cube == null) {
                return false;
            }
            pos = pos.immutable();
            oldState = cube.setBlockState(pos, newState, false);
            if (oldState == null) {
                return false;
            }
            updateBlockEntity(clientLevel, cube, pos, newState);

            int oldEmission = oldState.getLightEmission(clientLevel, pos);
            int newEmission = newState.getLightEmission(clientLevel, pos);
            if (oldEmission > 0) {
                cube.removeEmitter(pos);
            }
            if (newEmission > 0) {
                cube.putEmitter(pos, newEmission);
            }
        }
        // re-entrant LOCK acquisitions below (recursive setBlock via neighbour
        // shape updates) are safe — Java monitors are reentrant
        com.iridium126.createmanaindustry.client.dimension.render.AllvrRenderer.INSTANCE
            .onBlockChanged(pos, oldState, newState);

        // mirror of Level#markAndNotifyBlock, minus renderer notification —
        // cubes have no vanilla sections to re-render (ALLVR remesh, phase 3)
        if ((flags & 1) != 0) {
            clientLevel.blockUpdated(pos, oldState.getBlock());
            if (newState.hasAnalogOutputSignal()) {
                clientLevel.updateNeighbourForOutputSignal(pos, newState.getBlock());
            }
        }
        if ((flags & 16) == 0 && recursionLeft > 0) {
            int i = flags & -34;
            oldState.updateIndirectNeighbourShapes(clientLevel, pos, i, recursionLeft - 1);
            newState.updateNeighbourShapes(clientLevel, pos, i, recursionLeft - 1);
            newState.updateIndirectNeighbourShapes(clientLevel, pos, i, recursionLeft - 1);
        }
        return true;
    }

    private static void updateBlockEntity(ClientLevel clientLevel, AllvrCube cube, BlockPos pos, BlockState newState) {
        cube.updateBlockEntity(clientLevel, pos, newState);
        refreshBeCube(com.iridium126.createmanaindustry.dimension.cube.AllvrCubePos.asLong(pos), cube);
    }

    public static BlockState getBlockState(BlockPos pos) {
        AllvrCube cube = cubes.get(com.iridium126.createmanaindustry.dimension.cube.AllvrCubePos.asLong(pos));
        return cube == null ? Blocks.VOID_AIR.defaultBlockState() : cube.getBlockState(pos);
    }

    public static FluidState getFluidState(BlockPos pos) {
        return getBlockState(pos).getFluidState();
    }

    public static BlockEntity getBlockEntity(BlockPos pos) {
        AllvrCube cube = cubes.get(com.iridium126.createmanaindustry.dimension.cube.AllvrCubePos.asLong(pos));
        return cube == null ? null : cube.getBlockEntity(pos);
    }

    /**
     * Fills a padded 34³ mesher snapshot (states + occluder flags) for the
     * cube at {@code key}. Lock-held work is bounded to the 3×3×3 neighborhood
     * lookup, eight private {@link PalettedContainer#copy()} copies of the
     * center sections and the thin padding strips — the 32³ interior is filled
     * from the private copies after the lock is released, so main-thread cube
     * writes never queue behind a full 39k-voxel scan. Missing cubes and
     * neighbors fill as air. Arrays are caller-allocated ({@code PADDED³}
     * each), as before.
     */
    @SuppressWarnings("unchecked")
    public static void snapshotForMesher(long key, BlockState[] states, byte[] occludes) {
        BlockState air = Blocks.AIR.defaultBlockState();
        Arrays.fill(states, air);
        Arrays.fill(occludes, (byte) 0);
        AllvrCubePos cpos = AllvrCubePos.fromLong(key);
        PalettedContainer<BlockState>[] own;
        synchronized (LOCK) {
            AllvrCube center = cubes.get(key);
            if (center == null) {
                return; // forgotten mid-flight — the all-air snapshot stays
            }
            LevelChunkSection[] sections = center.getSections();
            own = new PalettedContainer[8];
            for (int i = 0; i < 8; i++) {
                own[i] = sections[i].getStates().copy();
            }
            Long2ObjectOpenHashMap<AllvrCube> hood = new Long2ObjectOpenHashMap<>(27);
            for (int oy = -1; oy <= 1; oy++) {
                for (int oz = -1; oz <= 1; oz++) {
                    for (int ox = -1; ox <= 1; ox++) {
                        long nkey = AllvrCubePos.asLong(cpos.getX() + ox, cpos.getY() + oy, cpos.getZ() + oz);
                        AllvrCube neighbor = cubes.get(nkey);
                        if (neighbor != null) {
                            hood.put(nkey, neighbor);
                        }
                    }
                }
            }
            BlockPos.MutableBlockPos cursor = new BlockPos.MutableBlockPos();
            // padding shell (any axis at -1 or 32) — live reads from neighbor sections
            for (int y = -1; y <= AllvrMesher.CUBE; y++) {
                boolean edgeY = y < 0 || y >= AllvrMesher.CUBE;
                for (int z = -1; z <= AllvrMesher.CUBE; z++) {
                    boolean edgeZ = z < 0 || z >= AllvrMesher.CUBE;
                    for (int x = -1; x <= AllvrMesher.CUBE; x++) {
                        boolean edgeX = x < 0 || x >= AllvrMesher.CUBE;
                        if (!edgeX && !edgeY && !edgeZ) {
                            continue; // interior filled below, outside the lock
                        }
                        // (x>>5, y>>5, z>>5) is the neighbor offset (−1|0|1) and
                        // (x&31, …) the in-neighbor local coord for −1 and 32 alike
                        AllvrCube neighbor = hood.get(AllvrCubePos.asLong(
                            cpos.getX() + (x >> 5), cpos.getY() + (y >> 5), cpos.getZ() + (z >> 5)));
                        BlockState state = air;
                        if (neighbor != null) {
                            state = neighbor.getBlockState(cursor.set(
                                neighbor.getPos().minBlockX() + (x & 31),
                                neighbor.getPos().minBlockY() + (y & 31),
                                neighbor.getPos().minBlockZ() + (z & 31)));
                        }
                        int i = AllvrMesher.paddedIndex(x, y, z);
                        states[i] = state;
                        occludes[i] = AllvrMesher.occludesAt(state);
                    }
                }
            }
        }
        // interior 32³ from the private copies — no main-thread writes can race these
        for (int y = 0; y < AllvrMesher.CUBE; y++) {
            for (int z = 0; z < AllvrMesher.CUBE; z++) {
                for (int x = 0; x < AllvrMesher.CUBE; x++) {
                    BlockState state =
                        own[AllvrCube.sliceIndex(x >> 4, y >> 4, z >> 4)].get(x & 15, y & 15, z & 15);
                    int i = AllvrMesher.paddedIndex(x, y, z);
                    states[i] = state;
                    occludes[i] = AllvrMesher.occludesAt(state);
                }
            }
        }
    }

    public static int size() {
        return cubes.size();
    }

    private AllvrClientCubeCache() {}
}
