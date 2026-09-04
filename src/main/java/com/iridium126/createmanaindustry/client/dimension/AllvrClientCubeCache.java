package com.iridium126.createmanaindustry.client.dimension;

import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.material.FluidState;

import com.iridium126.createmanaindustry.CreateManaIndustry;
import com.iridium126.createmanaindustry.dimension.AllvrDimensions;
import com.iridium126.createmanaindustry.dimension.cube.AllvrCube;
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

    /** Binds the current client level (called on LevelEvent.Load). */
    public static void onLevelChanged(ClientLevel clientLevel) {
        level = clientLevel;
    }

    /** Drops every streamed cube (level unload / dimension switch / logout). */
    public static void clear() {
        cubes.clear();
        level = null;
    }

    /** Main-thread apply of one streamed cube. */
    public static void applyCube(ClientboundAllvrCubePacket packet) {
        ClientLevel clientLevel = Minecraft.getInstance().level;
        if (clientLevel == null || clientLevel.dimension() != AllvrDimensions.ALLAY_LEVEL) {
            return;
        }
        level = clientLevel;
        AllvrCube cube = packet.decodeCube(clientLevel.registryAccess());
        synchronized (LOCK) {
            cubes.put(packet.cubePos(), cube);
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
        }
        com.iridium126.createmanaindustry.client.dimension.render.AllvrRenderer.INSTANCE.onCubeForgotten(cubePos);
    }

    /**
     * Applies one authoritative server-side block change (the cube analogue
     * of the vanilla confirmation packet path: flags 19, recursion 512).
     * Unloaded cubes reject the write inside {@link #setBlock}, mirroring
     * vanilla "write to unloaded chunk fails" (e.g. a race with a forget
     * packet); emitter bookkeeping and border remeshing run inside the normal
     * setBlock path.
     */
    public static void applyBlockUpdate(ClientboundAllvrBlockUpdatePacket packet) {
        com.iridium126.createmanaindustry.dimension.cube.AllvrCubePos pos =
            com.iridium126.createmanaindustry.dimension.cube.AllvrCubePos.fromLong(packet.cubePos());
        int cell = packet.cellIndex();
        BlockPos blockPos = new BlockPos(pos.minBlockX() + (cell & 31),
            pos.minBlockY() + (cell >> 10), pos.minBlockZ() + ((cell >> 5) & 31));
        setBlock(blockPos, net.minecraft.world.level.block.Block.stateById(packet.stateId()), 19, 512);
    }

    /** The cached cube at a position's cube, or null (never generates). */
    public static AllvrCube peekCube(BlockPos pos) {
        synchronized (LOCK) {
            return cubes.get(com.iridium126.createmanaindustry.dimension.cube.AllvrCubePos.asLong(pos));
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
            updateBlockEntity(clientLevel, cube, pos, oldState, newState);

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
        com.iridium126.createmanaindustry.client.dimension.render.AllvrRenderer.INSTANCE.onBlockChanged(pos);

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

    private static void updateBlockEntity(ClientLevel clientLevel, AllvrCube cube, BlockPos pos, BlockState oldState, BlockState newState) {
        if (oldState.hasBlockEntity() && !newState.hasBlockEntity()) {
            BlockEntity be = cube.removeBlockEntity(pos);
            if (be != null) {
                be.setRemoved();
            }
        }
        if (newState.hasBlockEntity() && newState.getBlock() instanceof net.minecraft.world.level.block.EntityBlock entityBlock) {
            BlockEntity be = entityBlock.newBlockEntity(pos, newState);
            if (be != null) {
                be.setLevel(clientLevel);
                cube.putBlockEntity(pos, be);
            }
        }
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

    public static int size() {
        return cubes.size();
    }

    private AllvrClientCubeCache() {}
}
