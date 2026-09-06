package com.iridium126.createmanaindustry.dimension;

import java.util.UUID;

import com.iridium126.createmanaindustry.dimension.cube.AllvrCubeMap;
import com.iridium126.createmanaindustry.dimension.cube.AllvrServerLevelDuck;

import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import net.neoforged.neoforge.event.tick.LevelTickEvent;

/**
 * Server event wiring for the allay dimension. Registered from the mod
 * constructor on {@code NeoForge.EVENT_BUS}.
 * <ul>
 *   <li>{@code LevelTickEvent.Post} — drives cube generation + per-player
 *       streaming (the cube layer replaces the vanilla ticket machinery
 *       entirely; no DistanceManager/ChunkMap mixins are involved).</li>
 *   <li>logout / dimension change — drops the player's cube subscription so
 *       the stream restarts cleanly on re-entry.</li>
 * </ul>
 */
public final class AllvrServerHandler {

    public static void onLevelTick(LevelTickEvent.Post event) {
        if (event.getLevel() instanceof ServerLevel serverLevel
            && serverLevel.dimension() == AllvrDimensions.ALLAY_LEVEL) {
            AllvrCubeMap map = ((AllvrServerLevelDuck) serverLevel).allvr$getCubeMap();
            if (map != null) {
                map.tick();
                ((AllvrServerLevelDuck) serverLevel).allvr$getLodMap().tick();
            }
        }
    }

    public static void onPlayerLoggedOut(PlayerEvent.PlayerLoggedOutEvent event) {
        resetSubscription(event.getEntity());
    }

    public static void onPlayerChangedDimension(PlayerEvent.PlayerChangedDimensionEvent event) {
        resetSubscription(event.getEntity());
    }

    private static void resetSubscription(Entity entity) {
        if (!(entity instanceof ServerPlayer player)) {
            return;
        }
        MinecraftServer server = player.getServer();
        if (server == null) {
            return;
        }
        ServerLevel allayLevel = server.getLevel(AllvrDimensions.ALLAY_LEVEL);
        if (allayLevel != null) {
            UUID uuid = player.getUUID();
            ((AllvrServerLevelDuck) allayLevel).allvr$getCubeMap().resetPlayer(uuid);
            ((AllvrServerLevelDuck) allayLevel).allvr$getLodMap().resetPlayer(uuid);
        }
    }

    private AllvrServerHandler() {}
}
