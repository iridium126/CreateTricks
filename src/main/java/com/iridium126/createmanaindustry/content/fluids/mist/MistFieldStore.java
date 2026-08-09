package com.iridium126.createmanaindustry.content.fluids.mist;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import com.iridium126.createmanaindustry.CMIAttachments;
import com.iridium126.createmanaindustry.config.ServerConfig;

import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;
import net.neoforged.neoforge.fluids.FluidStack;

/**
 * Central data store for active mist fields, attached to each {@link Level} via
 * {@link CMIAttachments#MIST_FIELD} instead of static per-dimension maps.
 * <p>
 * The data lives and dies with the level: the server holds it on its
 * {@code ServerLevel}, and the client holds its own (always-empty) copy on its
 * {@code ClientLevel}. This removes dimension-unload leaks and the server/client
 * shared-static coupling that single-player relied on.
 * <p>
 * Each active atomizer is stored with its position and field parameters.
 * Concentration is computed on the fly at query time using Euclidean distance —
 * no cached concentration grids are maintained, so block changes are
 * automatically reflected.
 */
public final class MistFieldStore {

    /**
     * How many ticks a recipe reservation lives without being refreshed. The
     * matching gate refreshes it on every poll while a mist recipe is waiting for
     * capacity, and the basin operator refreshes it while processing — so a
     * genuinely waiting/processing recipe never expires. When the recipe stops
     * being pursued (items removed, machine stopped/destroyed), the reservation
     * expires after this many ticks and the condenser resumes draining.
     */
    public static final long RESERVATION_TTL = 60;

    /**
     * Per-level mist field data. Attached to each {@link Level} so it is created
     * lazily on first access and collected together with the level.
     */
    public static final class MistFieldData {
        /** Persistent (atomizer / burner) sources keyed by position. */
        final Map<BlockPos, AtomizerField> active = new ConcurrentHashMap<>();
        /** Timed (recipe byproduct) sources keyed by position. */
        final Map<BlockPos, TimedMistEntry> timed = new ConcurrentHashMap<>();
        /** Recipe capacity reservations keyed by basin position. */
        final Map<BlockPos, MistReservation> reservations = new ConcurrentHashMap<>();
        /** Per-source total reserved capacity, derived from {@link #reservations}. */
        final Map<BlockPos, Long> reservedBySource = new ConcurrentHashMap<>();
    }

    private MistFieldStore() {}

    private static MistFieldData data(Level level) {
        return level.getData(CMIAttachments.MIST_FIELD);
    }

    /**
     * Called by {@code KineticAtomizerBlockEntity} on state transitions (inactive
     * → active or active → inactive). Not called every tick.
     *
     * @param active {@code true} to register this atomizer; {@code false} to remove
     */
    public static void setActive(Level level, BlockPos pos, boolean active, int radius) {
        setActive(level, pos, active, radius, null);
    }

    /**
     * Like {@link #setActive(Level, BlockPos, boolean, int)} but also stores the
     * fluid type, enabling {@link #getFluidType(Level, BlockPos)} queries.
     */
    public static void setActive(Level level, BlockPos pos, boolean active, int radius, FluidStack fluid) {
        if (level == null || level.isClientSide)
            return;

        if (active)
            data(level).active.put(pos.immutable(), new AtomizerField(radius, fluid));
        else
            data(level).active.remove(pos);
    }

    /**
     * Queries the mist concentration at a given position.
     * <p>
     * If multiple atomizer fields overlap, the <b>maximum</b> concentration is
     * returned.
     *
     * @return concentration in {@code [0, mistBaseConcentration]}, or {@code 0}
     *         if the position is not in any mist field
     */
    public static float getConcentration(Level level, BlockPos pos) {
        return getDominant(level, pos).concentration;
    }

    /**
     * Returns whether any mist source of {@code fluidId} contributes at least
     * {@code minConcentration} at {@code pos}.
     * <p>
     * Unlike {@link #getFluidType(Level, BlockPos)}, this checks all overlapping
     * mist sources instead of only the dominant one.
     */
    public static boolean hasMatchingMist(Level level, BlockPos pos,
            net.minecraft.resources.ResourceLocation fluidId, double minConcentration) {
        if (level == null || pos == null || fluidId == null)
            return false;

        MistFieldData data = data(level);
        if (!data.active.isEmpty() && hasMatchingAtomizerMist(data.active, pos, fluidId, minConcentration))
            return true;

        return !data.timed.isEmpty() && hasMatchingTimedMist(data.timed, pos, fluidId, minConcentration);
    }

    /**
     * Returns the fluid type (as {@link net.minecraft.resources.ResourceLocation})
     * of the mist source that contributes the highest concentration at the given
     * position, or {@code null} if no mist is present.
     */
    @org.jetbrains.annotations.Nullable
    public static net.minecraft.resources.ResourceLocation getFluidType(Level level, BlockPos pos) {
        return getDominant(level, pos).fluidId;
    }

    /** Combined query — avoids double iteration over both maps. */
    private static DominantResult getDominant(Level level, BlockPos pos) {
        if (level == null || pos == null)
            return DominantResult.NONE;

        float maxConc = 0f;
        net.minecraft.resources.ResourceLocation bestFluid = null;
        MistFieldData data = data(level);

        for (var entry : data.active.entrySet()) {
            float conc = calcConcentration(pos, entry.getKey(), entry.getValue().radius());
            if (conc > maxConc) {
                maxConc = conc;
                var fluid = entry.getValue().fluid();
                bestFluid = fluid != null && !fluid.isEmpty()
                        ? net.minecraft.core.registries.BuiltInRegistries.FLUID.getKey(fluid.getFluid())
                        : null;
            }
        }

        for (var entry : data.timed.entrySet()) {
            float conc = calcConcentration(pos, entry.getKey(), entry.getValue().radius());
            if (conc > maxConc) {
                maxConc = conc;
                var fluid = entry.getValue().fluid();
                bestFluid = fluid != null && !fluid.isEmpty()
                        ? net.minecraft.core.registries.BuiltInRegistries.FLUID.getKey(fluid.getFluid())
                        : null;
            }
        }

        return new DominantResult(maxConc, bestFluid);
    }

    private record DominantResult(float concentration,
            @org.jetbrains.annotations.Nullable net.minecraft.resources.ResourceLocation fluidId) {
        static final DominantResult NONE = new DominantResult(0f, null);
    }

    private static float calcConcentration(BlockPos queryPos, BlockPos sourcePos, int radius) {
        if (radius <= 0)
            return 0f; // radius 0 would make dist/radius a 0/0 NaN at the source block
        double dx = queryPos.getX() - sourcePos.getX();
        double dy = queryPos.getY() - sourcePos.getY();
        double dz = queryPos.getZ() - sourcePos.getZ();
        double dist = Math.sqrt(dx * dx + dy * dy + dz * dz);

        if (dist <= radius) {
            return (float) (ServerConfig.mistBaseConcentration * (1.0 - dist / radius));
        }
        return 0f;
    }

    private static boolean hasMatchingAtomizerMist(Map<BlockPos, AtomizerField> fields, BlockPos pos,
            net.minecraft.resources.ResourceLocation fluidId, double minConcentration) {
        for (var entry : fields.entrySet()) {
            var fluid = entry.getValue().fluid();
            if (fluid == null || fluid.isEmpty())
                continue;
            if (!fluidId.equals(net.minecraft.core.registries.BuiltInRegistries.FLUID.getKey(fluid.getFluid())))
                continue;
            if (calcConcentration(pos, entry.getKey(), entry.getValue().radius()) >= minConcentration)
                return true;
        }
        return false;
    }

    private static boolean hasMatchingTimedMist(Map<BlockPos, TimedMistEntry> timedEntries, BlockPos pos,
            net.minecraft.resources.ResourceLocation fluidId, double minConcentration) {
        for (var entry : timedEntries.entrySet()) {
            var fluid = entry.getValue().fluid();
            if (fluid == null || fluid.isEmpty())
                continue;
            if (!fluidId.equals(net.minecraft.core.registries.BuiltInRegistries.FLUID.getKey(fluid.getFluid())))
                continue;
            if (calcConcentration(pos, entry.getKey(), entry.getValue().radius()) >= minConcentration)
                return true;
        }
        return false;
    }

    // ---- timed mist entries (recipe byproducts) ------------------------------

    /**
     * Registers a timed mist emission at the given position. Used for one-shot
     * recipe byproducts that should persist for a fixed duration.
     *
     * @param fluid        the fluid whose color determines mist appearance
     * @param radius       field radius in blocks
     * @param expiryTick   absolute tick when the entry expires
     */
    public static void addTimed(Level level, BlockPos pos, FluidStack fluid, int radius, long expiryTick) {
        if (level == null || level.isClientSide || pos == null)
            return;

        data(level).timed.put(pos.immutable(), new TimedMistEntry(fluid, radius, expiryTick));
    }

    /** Removes a timed mist entry early (e.g. when a recipe source is removed). */
    public static void removeTimed(Level level, BlockPos pos) {
        if (level == null || level.isClientSide)
            return;

        data(level).timed.remove(pos);
    }

    // ---- capacity tracking -------------------------------------------------

    /**
     * Adds drained fluid to the persistent (atomizer) source at {@code pos}.
     * No-op if no persistent source exists at the position.
     * Called every tick by {@code KineticAtomizerBlockEntity} while active.
     */
    public static void addCapacity(Level level, BlockPos pos, long amount) {
        if (level == null || level.isClientSide || amount <= 0)
            return;

        data(level).active.computeIfPresent(pos, (p, f) -> {
            f.fluidCapacity += amount;
            return f;
        });
    }

    /**
     * Updates the radius of a persistent (atomizer) source in-place.
     * Avoids deactivate/re-activate which would reset fluid capacity.
     * No-op if no persistent source exists at the position.
     */
    public static void updateRadius(Level level, BlockPos pos, int newRadius) {
        if (level == null || level.isClientSide)
            return;

        data(level).active.computeIfPresent(pos, (p, f) -> {
            f.radius = newRadius;
            return f;
        });
    }

    /**
     * Creates or refreshes a timed mist entry. If an entry already exists at
     * {@code pos}, its expiry is reset and capacity is added. Otherwise a new
     * entry is created.
     *
     * @param capacityAmount mB to add to the entry's fluid capacity
     */
    public static void emitOrExtendTimed(Level level, BlockPos pos, FluidStack fluid,
            int radius, long expiryTick, long capacityAmount) {
        if (level == null || level.isClientSide)
            return;

        data(level).timed.compute(pos.immutable(), (p, existing) -> {
            // Same fluid → extend: refresh expiry, adopt the new radius, add capacity.
            // Different fluid (basin switched to another mist recipe at this pos) →
            // replace entirely so the server store and the client packet agree.
            if (existing != null && existing.fluid != null && existing.fluid.is(fluid.getFluid())) {
                existing.expiryTick = expiryTick;
                existing.radius = radius;
                existing.fluidCapacity += capacityAmount;
                return existing;
            }
            return new TimedMistEntry(fluid, radius, expiryTick, capacityAmount);
        });
    }

    /**
     * Consumes up to {@code desired} mB from all mist sources matching
     * {@code fluidId} that contribute concentration at {@code queryPos}.
     * <p>
     * Sources are consumed in order of decreasing concentration at the query
     * position — the dominant (strongest) source is drained first, then
     * progressively weaker sources of the same fluid type.
     *
     * @param respectReservations when {@code true}, each source is capped at its
     *                            unreserved capacity
     *                            ({@code max(0, capacity - reserved)}), so reserved
     *                            capacity claimed by waiting recipes is never
     *                            drained. The condenser passes {@code true}; a
     *                            recipe consuming its own reserved mist at
     *                            completion passes {@code false}.
     * @return total mB actually consumed across all matching sources
     */
    public static long consumeCapacity(Level level, BlockPos queryPos,
            ResourceLocation fluidId, long desired, boolean respectReservations) {
        if (level == null || level.isClientSide || fluidId == null || desired <= 0)
            return 0L;

        MistFieldData data = data(level);

        // Collect all same-fluid sources with capacity > 0 and concentration at queryPos
        record SourcedEntry(BlockPos srcPos, boolean isTimed, long capacity, float conc) {}
        List<SourcedEntry> sources = new ArrayList<>();

        for (var e : data.active.entrySet()) {
            AtomizerField f = e.getValue();
            if (f.fluidCapacity <= 0 || f.fluid == null || f.fluid.isEmpty())
                continue;
            if (!fluidId.equals(net.minecraft.core.registries.BuiltInRegistries.FLUID.getKey(f.fluid.getFluid())))
                continue;
            float conc = calcConcentration(queryPos, e.getKey(), f.radius());
            if (conc > 0)
                sources.add(new SourcedEntry(e.getKey(), false, f.fluidCapacity, conc));
        }

        for (var e : data.timed.entrySet()) {
            TimedMistEntry t = e.getValue();
            if (t.fluidCapacity <= 0)
                continue;
            if (!fluidId.equals(net.minecraft.core.registries.BuiltInRegistries.FLUID.getKey(t.fluid.getFluid())))
                continue;
            float conc = calcConcentration(queryPos, e.getKey(), t.radius());
            if (conc > 0)
                sources.add(new SourcedEntry(e.getKey(), true, t.fluidCapacity, conc));
        }

        if (sources.isEmpty())
            return 0L;

        // Sort by concentration descending — strongest source first
        sources.sort(Comparator.comparingDouble(SourcedEntry::conc).reversed());

        // Consume from each until target met
        long remaining = desired;
        long totalConsumed = 0L;
        for (SourcedEntry se : sources) {
            if (remaining <= 0)
                break;
            long take = Math.min(remaining, se.capacity);
            if (respectReservations) {
                long reserved = data.reservedBySource.getOrDefault(se.srcPos, 0L);
                take = Math.min(take, Math.max(0L, se.capacity - reserved));
            }
            final long amountTaken = take;
            if (se.isTimed) {
                data.timed.computeIfPresent(se.srcPos, (p, e) -> {
                    e.fluidCapacity -= amountTaken;
                    return e;
                });
            } else {
                data.active.computeIfPresent(se.srcPos, (p, f) -> {
                    f.fluidCapacity -= amountTaken;
                    return f;
                });
            }
            totalConsumed += take;
            remaining -= take;
        }
        return totalConsumed;
    }

    /**
     * Non-destructive query: total fluid capacity of all mist sources matching
     * {@code fluidId} that contribute concentration at {@code pos}. Mirrors the
     * source collection of {@link #consumeCapacity} without consuming.
     * <p>
     * Used by consumers to cap their demand so they never claim more than the
     * field can actually supply.
     *
     * @param excludeReserved when {@code true}, each source is counted as
     *                        {@code max(0, capacity - reserved)}, i.e. capacity
     *                        claimed by waiting/processing recipes is hidden from
     *                        the caller. The condenser passes {@code true} to
     *                        yield to recipes; the recipe's full-or-nothing gate
     *                        passes {@code false} to read the physical field.
     */
    public static long availableCapacity(Level level, BlockPos pos,
            ResourceLocation fluidId, boolean excludeReserved) {
        if (level == null || pos == null || fluidId == null)
            return 0L;

        long total = 0L;
        MistFieldData data = data(level);

        for (var e : data.active.entrySet()) {
            AtomizerField f = e.getValue();
            if (f.fluidCapacity <= 0 || f.fluid == null || f.fluid.isEmpty())
                continue;
            if (!fluidId.equals(net.minecraft.core.registries.BuiltInRegistries.FLUID.getKey(f.fluid.getFluid())))
                continue;
            if (calcConcentration(pos, e.getKey(), f.radius()) > 0)
                total += reservedAdjusted(f.fluidCapacity, excludeReserved ? reservedOn(data, e.getKey()) : 0L);
        }

        for (var e : data.timed.entrySet()) {
            TimedMistEntry t = e.getValue();
            if (t.fluidCapacity <= 0)
                continue;
            if (!fluidId.equals(net.minecraft.core.registries.BuiltInRegistries.FLUID.getKey(t.fluid.getFluid())))
                continue;
            if (calcConcentration(pos, e.getKey(), t.radius()) > 0)
                total += reservedAdjusted(t.fluidCapacity, excludeReserved ? reservedOn(data, e.getKey()) : 0L);
        }

        return total;
    }

    private static long reservedOn(MistFieldData data, BlockPos sourcePos) {
        return data.reservedBySource.getOrDefault(sourcePos, 0L);
    }

    private static long reservedAdjusted(long capacity, long reserved) {
        return Math.max(0L, capacity - reserved);
    }

    // ---- recipe reservations (full-or-nothing capacity claims) ------------------

    /**
     * Registers or refreshes a recipe's capacity reservation at {@code basinPos}.
     * <p>
     * The reservation is a <b>virtual claim</b> of {@code amount} mB spread over
     * the same-fluid sources contributing concentration at the basin, strongest
     * first (matching {@link #consumeCapacity} drain order). It may exceed a
     * source's current physical capacity — that is intentional: with
     * {@code availableCapacity(excludeReserved=true)} the condenser then reports
     * the field as claimed and yields, so it can accumulate to {@code amount}.
     * <p>
     * Called by the matching gate on every poll while a recipe waits for capacity
     * and by the basin operator while it processes such a recipe; each call
     * refreshes {@link #RESERVATION_TTL}.
     *
     * @return {@code true} if at least one source was reserved
     */
    public static boolean reserve(Level level, BlockPos basinPos, ResourceLocation fluidId, int amount) {
        if (level == null || level.isClientSide || basinPos == null || fluidId == null || amount <= 0)
            return false;

        MistFieldData data = data(level);
        BlockPos key = basinPos.immutable();

        // Drop any prior split for this basin before recomputing.
        MistReservation old = data.reservations.remove(key);
        if (old != null)
            applySplitToReservedBySource(data, old.split, -1);

        Map<BlockPos, Long> split = computeGreedySplit(data, basinPos, fluidId, amount);
        if (split.isEmpty())
            return false;

        data.reservations.put(key, new MistReservation(fluidId, amount,
                level.getGameTime() + RESERVATION_TTL, split));
        applySplitToReservedBySource(data, split, 1);
        return true;
    }

    /** Removes the reservation registered for {@code basinPos}, if any. */
    public static void releaseReservation(Level level, BlockPos basinPos) {
        if (level == null || level.isClientSide || basinPos == null)
            return;
        MistFieldData data = data(level);
        MistReservation old = data.reservations.remove(basinPos);
        if (old != null)
            applySplitToReservedBySource(data, old.split, -1);
    }

    /** Whether a live (unexpired) reservation is held for {@code basinPos}. */
    public static boolean hasWaitingReservation(Level level, BlockPos basinPos) {
        if (level == null || level.isClientSide || basinPos == null)
            return false;
        MistFieldData data = data(level);
        MistReservation r = data.reservations.get(basinPos);
        return r != null && r.expiryTick > level.getGameTime();
    }

    /**
     * Greedy strongest-first split of {@code amount} across the same-fluid
     * sources contributing concentration at {@code basinPos}. Each source is
     * capped at its current physical capacity; any remainder is claimed
     * virtually on the strongest source, so even a freshly-activated (capacity 0)
     * field is protected from the condenser while it accumulates.
     */
    private static Map<BlockPos, Long> computeGreedySplit(MistFieldData data, BlockPos basinPos,
            ResourceLocation fluidId, long amount) {
        record Src(BlockPos pos, long capacity, float conc) {}
        List<Src> sources = new ArrayList<>();

        for (var e : data.active.entrySet()) {
            AtomizerField f = e.getValue();
            if (f.fluid == null || f.fluid.isEmpty())
                continue;
            if (!fluidId.equals(net.minecraft.core.registries.BuiltInRegistries.FLUID.getKey(f.fluid.getFluid())))
                continue;
            float conc = calcConcentration(basinPos, e.getKey(), f.radius());
            if (conc > 0)
                sources.add(new Src(e.getKey(), f.fluidCapacity, conc));
        }

        for (var e : data.timed.entrySet()) {
            TimedMistEntry t = e.getValue();
            if (!fluidId.equals(net.minecraft.core.registries.BuiltInRegistries.FLUID.getKey(t.fluid.getFluid())))
                continue;
            float conc = calcConcentration(basinPos, e.getKey(), t.radius());
            if (conc > 0)
                sources.add(new Src(e.getKey(), t.fluidCapacity, conc));
        }

        if (sources.isEmpty())
            return Map.of();

        sources.sort(Comparator.comparingDouble(Src::conc).reversed());

        Map<BlockPos, Long> split = new HashMap<>();
        long remaining = amount;
        for (Src s : sources) {
            if (remaining <= 0)
                break;
            long take = Math.min(remaining, Math.max(s.capacity, 0L));
            if (take > 0) {
                split.put(s.pos, take);
                remaining -= take;
            }
        }
        // Leftover (virtual) claim lands on the strongest source so it is fully
        // yielded to the recipe even when it has not accumulated enough yet.
        if (remaining > 0)
            split.merge(sources.get(0).pos, remaining, Long::sum);
        return split;
    }

    /** Adds ({@code sign} 1) or subtracts ({@code sign} -1) a split from the per-source reserved map. */
    private static void applySplitToReservedBySource(MistFieldData data, Map<BlockPos, Long> split, int sign) {
        if (sign > 0) {
            for (var e : split.entrySet())
                data.reservedBySource.merge(e.getKey(), e.getValue(), Long::sum);
        } else {
            for (var e : split.entrySet()) {
                data.reservedBySource.computeIfPresent(e.getKey(),
                        (p, v) -> v - e.getValue() <= 0 ? null : v - e.getValue());
            }
        }
    }

    /**
     * Convenience check for whether a position has any mist concentration.
     *
     * @return {@code true} if {@link #getConcentration(Level, BlockPos)} &gt; 0
     */
    public static boolean isInMist(Level level, BlockPos pos) {
        return getConcentration(level, pos) > 0f;
    }

    /**
     * Called every tick via {@link MistFieldTicker} to:
     * <ul>
     *   <li>Remove persistent atomizers whose chunks are no longer loaded.</li>
     *   <li>Expire timed entries whose expiry tick has passed.</li>
     *   <li>Release reservations that expired or whose basin chunk unloaded.</li>
     * </ul>
     */
    public static void tick(ServerLevel level, java.util.function.Consumer<BlockPos> onExpired) {
        MistFieldData data = data(level);

        // Clean up persistent entries for unloaded chunks
        if (!data.active.isEmpty())
            data.active.entrySet().removeIf(entry -> !level.isLoaded(entry.getKey()));

        // Expire timed entries — notify callback for each removed position
        if (!data.timed.isEmpty()) {
            long currentTick = level.getGameTime();
            data.timed.entrySet().removeIf(entry -> {
                if (entry.getValue().expiryTick <= currentTick) {
                    onExpired.accept(entry.getKey());
                    return true;
                }
                return false;
            });
        }

        // Release expired or unloaded reservations (leak backstop: a machine
        // destroyed while holding a reservation, or a basin whose items were
        // removed, stops refreshing and is released here after the TTL).
        if (!data.reservations.isEmpty()) {
            long currentTick = level.getGameTime();
            data.reservations.entrySet().removeIf(entry -> {
                if (!level.isLoaded(entry.getKey()) || entry.getValue().expiryTick <= currentTick) {
                    applySplitToReservedBySource(data, entry.getValue().split, -1);
                    return true;
                }
                return false;
            });
        }
    }

    /**
     * Mutable per-atomizer field parameters.
     * <p>
     * radius and fluidCapacity are mutable — radius changes with atomizer speed,
     * and fluidCapacity accumulates drained fluid. Mutations go through the
     * per-level map's compute family; per-level access is single-threaded (server
     * thread only).
     */
    private static final class AtomizerField {
        int radius;
        @org.jetbrains.annotations.Nullable final FluidStack fluid;
        long fluidCapacity;

        AtomizerField(int radius, @org.jetbrains.annotations.Nullable FluidStack fluid) {
            this.radius = radius;
            this.fluid = fluid;
            this.fluidCapacity = 0L;
        }

        int radius() { return radius; }
        @org.jetbrains.annotations.Nullable FluidStack fluid() { return fluid; }
    }

    /**
     * Recipe capacity reservation — a virtual claim of {@code amount} mB on the
     * sources contributing at the reserving basin. {@code split} records how much
     * is claimed on each source (used to derive {@code reservedBySource});
     * {@code expiryTick} bounds the claim's lifetime once it stops being
     * refreshed (see {@link #RESERVATION_TTL}).
     */
    private static final class MistReservation {
        final ResourceLocation fluidId;
        final int amount;
        long expiryTick;
        final Map<BlockPos, Long> split;

        MistReservation(ResourceLocation fluidId, int amount, long expiryTick, Map<BlockPos, Long> split) {
            this.fluidId = fluidId;
            this.amount = amount;
            this.expiryTick = expiryTick;
            this.split = split;
        }
    }

    /**
     * Mutable timed mist entry with expiry and fluid capacity.
     * <p>
     * expiryTick, radius and fluidCapacity are mutable — expiry is reset on each
     * recipe completion, radius follows the recipe, and capacity accumulates
     * across recipe cycles.
     */
    private static final class TimedMistEntry {
        final FluidStack fluid;
        int radius;
        long expiryTick;
        long fluidCapacity;

        TimedMistEntry(FluidStack fluid, int radius, long expiryTick, long fluidCapacity) {
            this.fluid = fluid;
            this.radius = radius;
            this.expiryTick = expiryTick;
            this.fluidCapacity = fluidCapacity;
        }

        TimedMistEntry(FluidStack fluid, int radius, long expiryTick) {
            this(fluid, radius, expiryTick, 0L);
        }

        FluidStack fluid() { return fluid; }
        int radius() { return radius; }
    }
}
