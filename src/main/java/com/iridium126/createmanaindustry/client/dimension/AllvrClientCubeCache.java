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
        cubes.put(packet.cubePos(), cube);
        if (CreateManaIndustry.LOGGER.isDebugEnabled()) {
            CreateManaIndustry.LOGGER.debug("[Allvr] cube {} streamed ({} bytes, {} cubes cached)",
                cube.getPos(), packet.payload().length, cubes.size());
        }
    }

    public static void forgetCube(long cubePos) {
        cubes.remove(cubePos);
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
