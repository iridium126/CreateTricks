package com.iridium126.createmanaindustry.content.fluids.mist;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;

import com.iridium126.createmanaindustry.config.Config;

import net.minecraft.core.BlockPos;
import net.neoforged.neoforge.fluids.FluidStack;

/**
 * Client-sync bridge for the mist field system.
 * <p>
 * All mist data operations (queries and mutations) live on
 * {@link MistFieldStore} and are called directly by producers/consumers. This
 * class exists only to bridge the client-side render handler: block entities
 * notify it from their {@code read(clientPacket=true)} path
 * ({@link #notifyClientSync}) and the client renderer subscribes via
 * {@link #registerSyncCallback}. The callback list is empty on dedicated
 * servers, so server-side code never touches client render classes.
 */
public final class MistSync {

    /**
     * Sync payload sent to registered client callbacks whenever mist state
     * changes. An empty fluid signals deactivation; radius is 0 when deactivated.
     */
    public record MistSyncData(BlockPos pos, FluidStack fluid, int radius) {}

    /** Shared client-sync callbacks. Thread-safe for concurrent registration. */
    private static final List<Consumer<MistSyncData>> syncCallbacks = new CopyOnWriteArrayList<>();

    private MistSync() {}

    /**
     * Register a callback that receives position + fluid + radius updates
     * whenever mist state changes. Called by the client-side render handler
     * during initialization.
     */
    public static void registerSyncCallback(Consumer<MistSyncData> callback) {
        if (callback != null && !syncCallbacks.contains(callback))
            syncCallbacks.add(callback);
    }

    /**
     * Direct notification for client-side sync (e.g. from block entity
     * {@code read(clientPacket=true)} handlers).
     */
    public static void notifyClientSync(BlockPos pos, FluidStack fluid, int radius) {
        notifyCallbacks(pos, fluid, radius);
    }

    /** Backward-compatible overload — defaults radius to {@code Config.mistMaxRadius}. */
    public static void notifyClientSync(BlockPos pos, FluidStack fluid) {
        notifyCallbacks(pos, fluid, Config.mistMaxRadius);
    }

    private static void notifyCallbacks(BlockPos pos, FluidStack fluid, int radius) {
        if (syncCallbacks.isEmpty())
            return;
        MistSyncData data = new MistSyncData(pos, fluid, radius);
        for (Consumer<MistSyncData> cb : syncCallbacks)
            cb.accept(data);
    }
}
