package com.iridium126.createmanaindustry.content.fluids.mist;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import com.iridium126.createmanaindustry.config.Config;

import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;
import net.neoforged.neoforge.fluids.FluidStack;

/**
 * Central data store for active Kinetic Atomizer mist fields. Follows the
 * {@code TemporaryStress} pattern: a static utility class backed by a
 * per-dimension {@link ConcurrentHashMap}.
 * <p>
 * Each active atomizer is stored with its position and field parameters.
 * Concentration is computed on the fly at query time using Manhattan distance
 * — no cached concentration grids are maintained, so block changes are
 * automatically reflected.
 */
public final class MistFieldStore {

    /** Per-dimension map of atomizer positions to their field parameters. */
    private static final Map<ResourceKey<Level>, Map<BlockPos, AtomizerField>> ACTIVE = new ConcurrentHashMap<>();

    /** Per-dimension map of timed mist entries (recipe byproducts) with expiry. */
    private static final Map<ResourceKey<Level>, Map<BlockPos, TimedMistEntry>> TIMED = new ConcurrentHashMap<>();

    private MistFieldStore() {}

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

        ResourceKey<Level> dim = level.dimension();
        if (active) {
            ACTIVE.computeIfAbsent(dim, k -> new ConcurrentHashMap<>())
                    .put(pos.immutable(), new AtomizerField(radius, fluid));
        } else {
            Map<BlockPos, AtomizerField> dimFields = ACTIVE.get(dim);
            if (dimFields != null) {
                dimFields.remove(pos);
                if (dimFields.isEmpty())
                    ACTIVE.remove(dim, dimFields);
            }
        }
    }

    /**
     * Queries the mist concentration at a given position.
     * <ul>
     * <li>Only air blocks can contain mist — checked at query time so block
     * changes are automatically reflected.</li>
     * <li>If multiple atomizer fields overlap, the <b>maximum</b> concentration
     * is returned.</li>
     * </ul>
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

        ResourceKey<Level> dim = level.dimension();

        Map<BlockPos, AtomizerField> dimFields = ACTIVE.get(dim);
        if (dimFields != null && hasMatchingAtomizerMist(dimFields, pos, fluidId, minConcentration))
            return true;

        Map<BlockPos, TimedMistEntry> dimTimed = TIMED.get(dim);
        return dimTimed != null && hasMatchingTimedMist(dimTimed, pos, fluidId, minConcentration);
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

        ResourceKey<Level> dim = level.dimension();
        float maxConc = 0f;
        net.minecraft.resources.ResourceLocation bestFluid = null;

        Map<BlockPos, AtomizerField> dimFields = ACTIVE.get(dim);
        if (dimFields != null && !dimFields.isEmpty()) {
            for (var entry : dimFields.entrySet()) {
                float conc = calcConcentration(pos, entry.getKey(), entry.getValue().radius());
                if (conc > maxConc) {
                    maxConc = conc;
                    var fluid = entry.getValue().fluid();
                    bestFluid = fluid != null && !fluid.isEmpty()
                            ? net.minecraft.core.registries.BuiltInRegistries.FLUID.getKey(fluid.getFluid())
                            : null;
                }
            }
        }

        Map<BlockPos, TimedMistEntry> dimTimed = TIMED.get(dim);
        if (dimTimed != null && !dimTimed.isEmpty()) {
            for (var entry : dimTimed.entrySet()) {
                float conc = calcConcentration(pos, entry.getKey(), entry.getValue().radius());
                if (conc > maxConc) {
                    maxConc = conc;
                    var fluid = entry.getValue().fluid();
                    bestFluid = fluid != null && !fluid.isEmpty()
                            ? net.minecraft.core.registries.BuiltInRegistries.FLUID.getKey(fluid.getFluid())
                            : null;
                }
            }
        }

        return new DominantResult(maxConc, bestFluid);
    }

    private record DominantResult(float concentration,
            @org.jetbrains.annotations.Nullable net.minecraft.resources.ResourceLocation fluidId) {
        static final DominantResult NONE = new DominantResult(0f, null);
    }

    private static float calcConcentration(BlockPos queryPos, BlockPos sourcePos, int radius) {
        double dx = queryPos.getX() - sourcePos.getX();
        double dy = queryPos.getY() - sourcePos.getY();
        double dz = queryPos.getZ() - sourcePos.getZ();
        double dist = Math.sqrt(dx * dx + dy * dy + dz * dz);

        if (dist <= radius) {
            return (float) (Config.mistBaseConcentration * (1.0 - dist / radius));
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

        ResourceKey<Level> dim = level.dimension();
        TIMED.computeIfAbsent(dim, k -> new ConcurrentHashMap<>())
                .put(pos.immutable(), new TimedMistEntry(fluid, radius, expiryTick));
    }

    /** Removes a timed mist entry early (e.g. when a recipe source is removed). */
    public static void removeTimed(Level level, BlockPos pos) {
        if (level == null || level.isClientSide)
            return;

        ResourceKey<Level> dim = level.dimension();
        Map<BlockPos, TimedMistEntry> dimTimed = TIMED.get(dim);
        if (dimTimed != null) {
            dimTimed.remove(pos);
            if (dimTimed.isEmpty())
                TIMED.remove(dim, dimTimed);
        }
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

        ResourceKey<Level> dim = level.dimension();
        Map<BlockPos, AtomizerField> dimFields = ACTIVE.get(dim);
        if (dimFields != null)
            dimFields.computeIfPresent(pos, (p, f) -> { f.fluidCapacity += amount; return f; });
    }

    /**
     * Updates the radius of a persistent (atomizer) source in-place.
     * Avoids deactivate/re-activate which would reset fluid capacity.
     * No-op if no persistent source exists at the position.
     */
    public static void updateRadius(Level level, BlockPos pos, int newRadius) {
        if (level == null || level.isClientSide)
            return;

        ResourceKey<Level> dim = level.dimension();
        Map<BlockPos, AtomizerField> dimFields = ACTIVE.get(dim);
        if (dimFields != null)
            dimFields.computeIfPresent(pos, (p, f) -> { f.radius = newRadius; return f; });
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

        ResourceKey<Level> dim = level.dimension();
        Map<BlockPos, TimedMistEntry> dimTimed =
                TIMED.computeIfAbsent(dim, k -> new ConcurrentHashMap<>());
        dimTimed.compute(pos.immutable(), (p, existing) -> {
            if (existing != null) {
                existing.expiryTick = expiryTick;
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
     * @return total mB actually consumed across all matching sources
     */
    public static long consumeCapacity(Level level, BlockPos queryPos,
            net.minecraft.resources.ResourceLocation fluidId, long desired) {
        if (level == null || level.isClientSide || fluidId == null || desired <= 0)
            return 0L;

        ResourceKey<Level> dim = level.dimension();

        // Collect all same-fluid sources with capacity > 0 and concentration at queryPos
        record SourcedEntry(BlockPos srcPos, boolean isTimed, long capacity, float conc) {}
        java.util.List<SourcedEntry> sources = new java.util.ArrayList<>();

        Map<BlockPos, AtomizerField> dimFields = ACTIVE.get(dim);
        if (dimFields != null) {
            for (var e : dimFields.entrySet()) {
                AtomizerField f = e.getValue();
                if (f.fluidCapacity <= 0 || f.fluid == null || f.fluid.isEmpty())
                    continue;
                if (!fluidId.equals(net.minecraft.core.registries.BuiltInRegistries.FLUID.getKey(f.fluid.getFluid())))
                    continue;
                float conc = calcConcentration(queryPos, e.getKey(), f.radius());
                if (conc > 0)
                    sources.add(new SourcedEntry(e.getKey(), false, f.fluidCapacity, conc));
            }
        }

        Map<BlockPos, TimedMistEntry> dimTimed = TIMED.get(dim);
        if (dimTimed != null) {
            for (var e : dimTimed.entrySet()) {
                TimedMistEntry t = e.getValue();
                if (t.fluidCapacity <= 0)
                    continue;
                if (!fluidId.equals(net.minecraft.core.registries.BuiltInRegistries.FLUID.getKey(t.fluid.getFluid())))
                    continue;
                float conc = calcConcentration(queryPos, e.getKey(), t.radius());
                if (conc > 0)
                    sources.add(new SourcedEntry(e.getKey(), true, t.fluidCapacity, conc));
            }
        }

        if (sources.isEmpty())
            return 0L;

        // Sort by concentration descending — strongest source first
        sources.sort(java.util.Comparator.comparingDouble(SourcedEntry::conc).reversed());

        // Consume from each until target met
        long remaining = desired;
        long totalConsumed = 0L;
        for (SourcedEntry se : sources) {
            if (remaining <= 0)
                break;
            long take = Math.min(remaining, se.capacity);
            if (se.isTimed) {
                Map<BlockPos, TimedMistEntry> map = TIMED.get(dim);
                if (map != null)
                    map.computeIfPresent(se.srcPos, (p, e) -> { e.fluidCapacity -= take; return e; });
            } else {
                Map<BlockPos, AtomizerField> map = ACTIVE.get(dim);
                if (map != null)
                    map.computeIfPresent(se.srcPos, (p, f) -> { f.fluidCapacity -= take; return f; });
            }
            totalConsumed += take;
            remaining -= take;
        }
        return totalConsumed;
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
     * </ul>
     */
    public static void tick(ServerLevel level, java.util.function.Consumer<BlockPos> onExpired) {
        ResourceKey<Level> dim = level.dimension();

        // Clean up persistent entries for unloaded chunks
        Map<BlockPos, AtomizerField> dimFields = ACTIVE.get(dim);
        if (dimFields != null && !dimFields.isEmpty()) {
            dimFields.entrySet().removeIf(entry -> !level.isLoaded(entry.getKey()));
            if (dimFields.isEmpty())
                ACTIVE.remove(dim, dimFields);
        }

        // Expire timed entries — notify callback for each removed position
        Map<BlockPos, TimedMistEntry> dimTimed = TIMED.get(dim);
        if (dimTimed != null && !dimTimed.isEmpty()) {
            long currentTick = level.getGameTime();
            dimTimed.entrySet().removeIf(entry -> {
                if (entry.getValue().expiryTick <= currentTick) {
                    onExpired.accept(entry.getKey());
                    return true;
                }
                return false;
            });
            if (dimTimed.isEmpty())
                TIMED.remove(dim, dimTimed);
        }
    }

    /**
     * Mutable per-atomizer field parameters.
     * <p>
     * radius and fluidCapacity are mutable — radius changes with atomizer speed,
     * and fluidCapacity accumulates drained fluid.  The {@code ConcurrentHashMap}
     * is updated in-place via {@code computeIfPresent} for thread safety.
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

        //AtomizerField(int radius) { this(radius, null); }

        int radius() { return radius; }
        @org.jetbrains.annotations.Nullable FluidStack fluid() { return fluid; }
        //long fluidCapacity() { return fluidCapacity; }
    }

    /**
     * Mutable timed mist entry with expiry and fluid capacity.
     * <p>
     * expiryTick and fluidCapacity are mutable — expiry is reset on each
     * recipe completion, and capacity accumulates across recipe cycles.
     */
    private static final class TimedMistEntry {
        final FluidStack fluid;
        final int radius;
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
        //long expiryTick() { return expiryTick; }
        //long fluidCapacity() { return fluidCapacity; }
    }
}
