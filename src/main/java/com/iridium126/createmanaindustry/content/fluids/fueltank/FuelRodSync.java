package com.iridium126.createmanaindustry.content.fluids.fueltank;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;

import org.jetbrains.annotations.Nullable;

import net.minecraft.core.BlockPos;

/**
 * Client-sync bridge for the fuel rod structure (mirrors the mist system's
 * {@code MistSync}).
 * <p>
 * Block entities notify it from their {@code read(clientPacket=true)} path
 * ({@link #notifyClientSync}) whenever their rod state changes, and the
 * client-side bloom render handler subscribes via
 * {@link #registerSyncCallback}. The callback list is empty on dedicated
 * servers, so server-side code never touches client render classes.
 */
public final class FuelRodSync {

    /**
     * Sync payload sent to registered client callbacks whenever a rod's state
     * changes. A {@code null} rod signals that the structure broke and the glow
     * should fade out.
     */
    public record RodSyncData(BlockPos center, @Nullable FuelRodStructure.RodData rod) {}

    /** Shared client-sync callbacks. Thread-safe for concurrent registration. */
    private static final List<Consumer<RodSyncData>> syncCallbacks = new CopyOnWriteArrayList<>();

    private FuelRodSync() {
    }

    /**
     * Register a callback that receives rod state updates. Called by the
     * client-side render handler during initialization.
     */
    public static void registerSyncCallback(Consumer<RodSyncData> callback) {
        if (callback != null && !syncCallbacks.contains(callback))
            syncCallbacks.add(callback);
    }

    /** Direct notification from block entity {@code read(clientPacket=true)}. */
    public static void notifyClientSync(BlockPos center, @Nullable FuelRodStructure.RodData rod) {
        if (syncCallbacks.isEmpty())
            return;
        RodSyncData data = new RodSyncData(center, rod);
        for (Consumer<RodSyncData> cb : syncCallbacks)
            cb.accept(data);
    }
}
