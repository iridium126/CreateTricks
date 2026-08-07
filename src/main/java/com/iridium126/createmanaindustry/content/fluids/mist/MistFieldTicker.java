package com.iridium126.createmanaindustry.content.fluids.mist;

import com.iridium126.createmanaindustry.CreateManaIndustry;
import com.iridium126.createmanaindustry.network.ClientboundMistSyncPacket;

import net.minecraft.server.level.ServerLevel;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.tick.LevelTickEvent;
import net.neoforged.neoforge.fluids.FluidStack;

/**
 * Global per-tick handler that cleans up stale atomizer entries and expired
 * timed entries from the mist field store, and broadcasts an explicit
 * deactivation packet so clients (single-player and dedicated-server alike)
 * fade their fog when recipe mist naturally expires.
 * <p>
 * The per-dimension data itself is attached to each {@code ServerLevel} and is
 * collected with the level — no dimension-unload hook is needed here.
 */
@EventBusSubscriber(modid = CreateManaIndustry.MODID)
public final class MistFieldTicker {
    private MistFieldTicker() {}

    @SubscribeEvent
    public static void onLevelTick(LevelTickEvent.Post event) {
        if (event.getLevel() instanceof ServerLevel serverLevel) {
            MistFieldStore.tick(serverLevel, expiredPos -> {
                // Only broadcast when the chunk is loaded: getChunkAt would force
                // a load, and an unloaded chunk has no tracking players anyway.
                if (serverLevel.isLoaded(expiredPos))
                    ClientboundMistSyncPacket.sendToTracking(serverLevel, expiredPos, FluidStack.EMPTY, 0);
            });
        }
    }
}
