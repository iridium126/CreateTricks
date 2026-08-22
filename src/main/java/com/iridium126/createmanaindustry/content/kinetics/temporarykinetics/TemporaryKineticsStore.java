package com.iridium126.createmanaindustry.content.kinetics.temporarykinetics;

import java.util.Iterator;

import org.jetbrains.annotations.Nullable;

import com.simibubi.create.content.kinetics.base.KineticBlockEntity;

import it.unimi.dsi.fastutil.longs.Long2ObjectMap;
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import it.unimi.dsi.fastutil.longs.LongIterator;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.neoforged.neoforge.attachment.IAttachmentHolder;
import net.neoforged.neoforge.attachment.IAttachmentSerializer;

/**
 * Per-level data store for active temporary kinetics states, attached to each
 * {@link Level} via {@code CMIAttachments#TEMPORARY_KINETICS} instead of static
 * per-dimension maps.
 * <p>
 * The data lives and dies with the level: a fresh world gets a fresh (empty)
 * store, so stale states from a previous single-player world can never bleed
 * into the next one. The server holds live countdowns on its {@link ServerLevel};
 * the client mirrors synced states on its own {@code ClientLevel}. Serialization
 * persists remaining durations with the level save so a reloaded network keeps
 * its temporary capacity instead of waking up over-stressed.
 * <p>
 * Positions are stored as packed {@code pos.asLong()} keys: queries come from
 * hot paths ({@code getGeneratedSpeed}), so no per-query key objects are
 * allocated. All mutation happens on the owning side's main thread; a plain
 * map is therefore sufficient.
 */
public final class TemporaryKineticsStore {

    final Long2ObjectOpenHashMap<StressState> states = new Long2ObjectOpenHashMap<>();

    /**
     * Packed positions whose countdown already reached zero while their chunk
     * was unloaded. They leave {@link #states} immediately — no further
     * per-tick decrement, map iteration or loaded-probing on the hot map — and
     * are finalized by the chunk-load event ({@link #drainPending}), with the
     * level tick as an allocation-free fallback sweep. Never persisted: a
     * save+reload drops them, and vanilla's kinetic validation heals the
     * reloaded block's stale speed instead.
     */
    final LongOpenHashSet pendingExpiry = new LongOpenHashSet();

    public StressState get(BlockPos pos) {
        return states.get(pos.asLong());
    }

    public void put(BlockPos pos, StressState state) {
        states.put(pos.asLong(), state);
    }

    public void remove(BlockPos pos) {
        states.remove(pos.asLong());
    }

    /**
     * Counts down every active state of this level and finalizes expired ones.
     * <p>
     * Expiry must never force a synchronous chunk load just to reset a block:
     * an expired state whose chunk is unloaded parks in {@link #pendingExpiry}
     * and is resolved by the chunk-load event. The per-tick fallback sweep
     * below only runs while positions are actually parked, and probes chunk
     * presence straight off the packed position — no countdown work, no
     * {@code BlockPos} allocation.
     */
    public void tick(ServerLevel level) {
        if (!pendingExpiry.isEmpty())
            drainPending(level, null);
        if (states.isEmpty())
            return;

        Iterator<Long2ObjectMap.Entry<StressState>> iterator = states.long2ObjectEntrySet().iterator();
        while (iterator.hasNext()) {
            Long2ObjectMap.Entry<StressState> entry = iterator.next();
            StressState state = entry.getValue();
            state.ticksRemaining--;
            if (state.ticksRemaining > 0)
                continue;

            long packed = entry.getLongKey();
            iterator.remove();
            if (isChunkLoaded(level, packed))
                finalizeExpiry(level, packed);
            else
                pendingExpiry.add(packed); // retry when the chunk loads
        }
    }

    /**
     * Finalizes deferred expiries whose chunk is now loaded. Called from the
     * chunk-load event with the freshly loaded chunk — its block entities are
     * registered by then ({@code ChunkStatusTasks#full} posts the event right
     * after {@code registerAllBlockEntitiesAfterLevelLoad}) — and from
     * {@link #tick} as the fallback sweep with {@code loadedChunk == null},
     * which also guarantees a parked position always terminates: once its
     * chunk is loaded it either finalizes or is dropped.
     */
    void drainPending(ServerLevel level, @Nullable ChunkPos loadedChunk) {
        if (pendingExpiry.isEmpty())
            return;

        LongIterator iterator = pendingExpiry.iterator();
        while (iterator.hasNext()) {
            long packed = iterator.nextLong();
            if (loadedChunk != null) {
                if (BlockPos.getX(packed) >> 4 != loadedChunk.x || BlockPos.getZ(packed) >> 4 != loadedChunk.z)
                    continue;
            } else if (!isChunkLoaded(level, packed))
                continue;

            iterator.remove();
            finalizeExpiry(level, packed);
        }
    }

    /** Allocation-free chunk-loaded probe straight off the packed position. */
    private static boolean isChunkLoaded(ServerLevel level, long packed) {
        return level.getChunkSource()
            .hasChunk(BlockPos.getX(packed) >> 4, BlockPos.getZ(packed) >> 4);
    }

    private static void finalizeExpiry(ServerLevel level, long packed) {
        if (level.getBlockEntity(BlockPos.of(packed)) instanceof KineticBlockEntity kinetic) {
            TemporaryKinetics.updateGeneratedRotation(kinetic);
            TemporaryKinetics.syncBlock(kinetic);
        }
    }

    // ---- persistence ---------------------------------------------------------

    public static final class Serializer implements IAttachmentSerializer<CompoundTag, TemporaryKineticsStore> {

        @Override
        public TemporaryKineticsStore read(IAttachmentHolder holder, CompoundTag tag, HolderLookup.Provider provider) {
            TemporaryKineticsStore store = new TemporaryKineticsStore();
            ListTag entries = tag.getList("Entries", Tag.TAG_COMPOUND);
            for (int i = 0; i < entries.size(); i++) {
                CompoundTag entryTag = entries.getCompound(i);
                long pos = entryTag.getLong("Pos");
                float stress = entryTag.getFloat("Stress");
                float speed = entryTag.getFloat("Speed");
                int ticks = entryTag.getInt("Ticks");
                // Degenerate entries (no rotation, nothing left to count down)
                // have no effect; drop them instead of carrying dead weight.
                if (ticks > 0 && speed != 0f)
                    store.states.put(pos, new StressState(stress, speed, ticks));
            }
            return store;
        }

        @Override
        public CompoundTag write(TemporaryKineticsStore attachment, HolderLookup.Provider provider) {
            if (attachment.states.isEmpty())
                return null; // nothing active — serialize nothing at all

            CompoundTag tag = new CompoundTag();
            ListTag entries = new ListTag();
            for (Long2ObjectMap.Entry<StressState> entry : attachment.states.long2ObjectEntrySet()) {
                StressState state = entry.getValue();
                CompoundTag entryTag = new CompoundTag();
                entryTag.putLong("Pos", entry.getLongKey());
                entryTag.putFloat("Stress", state.stress);
                entryTag.putFloat("Speed", state.speed);
                entryTag.putInt("Ticks", state.ticksRemaining);
                entries.add(entryTag);
            }
            tag.put("Entries", entries);
            return tag;
        }
    }

    /**
     * One active application of temporary kinetics: the stress magnitude at the
     * applied speed, the imposed speed itself, and the remaining duration.
     * Package-visible mutable state — only the countdown and the source
     * re-activation flag ever change after construction.
     */
    static final class StressState {
        final float stress;
        final float speed;
        int ticksRemaining;
        boolean reActivateSource;

        StressState(float stress, float speed, int ticksRemaining) {
            this.stress = stress;
            this.speed = speed;
            this.ticksRemaining = ticksRemaining;
        }

        /** Capacity contributed to the network such that {@code capacity * |speed| == |stress|}. */
        float stressCapacity() {
            float absSpeed = Math.abs(speed);
            return absSpeed == 0 ? 0 : Math.abs(stress) / absSpeed;
        }
    }
}
