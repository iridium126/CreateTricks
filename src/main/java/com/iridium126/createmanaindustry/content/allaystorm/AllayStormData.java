package com.iridium126.createmanaindustry.content.allaystorm;

import java.util.BitSet;

import it.unimi.dsi.fastutil.ints.Int2FloatOpenHashMap;
import com.iridium126.createmanaindustry.config.ServerConfig;
import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.neoforged.neoforge.attachment.IAttachmentHolder;
import net.neoforged.neoforge.attachment.IAttachmentSerializer;

/**
 * Server-authoritative Allay Storm state, attached to each {@code ServerLevel}
 * (one storm per dimension — the client engine is single-storm by design).
 * <p>
 * The storm is a BOSS made of up to {@link #MAX_COUNT} GPU particles: the
 * server owns only what must be durable and consistent — the definition
 * (anchor/count/radius/mode/seed), the member DEATH set (alive-member
 * consistency across clients and restarts) and a sparse HP table for
 * damaged-but-alive members. Member POSITIONS never live here: they are
 * client-side GPU state, deterministically re-derived from
 * {@code seed = hash(stormSeed, memberIdx)} plus the shared game clock. The
 * vortex ANGULAR VELOCITY is not state at all: {@link #vortexOmega} derives
 * it client-side from the synced radius and the seed's low bit.
 * <p>
 * All values are created in their CANONICAL QUANTIZED form (radius 0.5-block
 * steps — the wire byte's round-trip grid) so the persisted state, the wire
 * format and every client agree without re-quantization drift. The sparse HP
 * map holds only damaged-alive members (everything else is the 20 HP vanilla
 * Allay maximum); dead members are the {@link #dead} bit set and never re-enter
 * the HP map. Vanilla regen (2 HP/s) runs in {@link AllayStormManager}.
 */
public final class AllayStormData {

    /** Stress-test ceiling (2^17); mirrors AllayStormSpec.MAX_COUNT. */
    public static final int MAX_COUNT = 131072;
    /** Vanilla Allay MAX_HEALTH. */
    public static final float MAX_HP = 20.0f;
    /** Canonical quantization step, applied once at creation. */
    public static final float RADIUS_STEP = 0.5f;
    /** stormSeed is quantized to 24 bits so it survives a float round-trip in GLSL. */
    public static final int SEED_MASK = (1 << 24) - 1;
    /**
     * Fixed storm altitude: the typhoon is a SKY storm — the chased anchor
     * lives at this Y forever (command input Y is overridden). At this height
     * the collision bake volumes are all air, members never touch terrain,
     * and melee combat is only contestable by flight.
     */
    public static final int CHASE_Y = 128;

    public boolean active;
    public BlockPos anchor = BlockPos.ZERO;
    /**
     * GENERATED member population (alive + dead): starts at the command's
     * initial count and GROWS on the server tick toward {@link #finalCount}
     * ({@link AllayStormManager} growth clock — kills never slow it, dead
     * indices never regenerate). New indices become members as this advances;
     * persisted, so growth survives restarts.
     */
    public int count;
    public float radius;
    /** 1 = ball (test), 2 = vortex. */
    public int mode;
    /** 24-bit instance seed; member i's seed = hash(stormSeed, i) on every client. */
    public int stormSeed;

    // ---- frozen growth law (vortex phase invariants; zero for ball) --------
    /**
     * The typhoon's phase functions are INTEGRALS of rates that depend on the
     * radius. A naive {@code rate(R) * timeSec} re-derives the whole pattern
     * phase whenever R moves — amplified by the accumulated world clock into
     * target teleports (the "storm clumps into a blob" bug). The fix: the
     * growth law {@code count(t) = count0 + g*t} makes those integrals CLOSED
     * FORM and LINEAR in {@code (R_now - R0)} — every client evaluates the
     * identical bounded expression from the synced constants below, frozen at
     * creation. Mid-flight config changes never retcon a living storm's
     * phase law (same generation discipline as the seed).
     */
    /** Radius at creation ({@code sqrt(initialCount)/8}); the phase integral's R₀. */
    public float creationRadius;
    /** Radius at full growth ({@code sqrt(finalCount)/8}); the growth window's end. */
    public float finalRadius;
    /** Generated-population ceiling this storm grows toward (frozen config). */
    public int finalCount;
    /** Frozen growth rate (members/s); 0 = this storm never grows. */
    public double growthPerSecond;
    /** Raw creation gameTime — the age clock of the post-growth phase terms. */
    public long createdAtGameTime;

    /** Dead members; alive population = count - cardinality. */
    public final BitSet dead = new BitSet();
    /** HP of damaged-but-alive members; absent = full HP. */
    public final Int2FloatOpenHashMap hp = new Int2FloatOpenHashMap();
    {
        // absent keys read as full HP (the map's own default is 0f, which
        // would kill every healthy member on its first hit report)
        hp.defaultReturnValue(MAX_HP);
    }

    /** Alive member count under the current definition. */
    public int aliveCount() {
        return Math.max(0, count - dead.cardinality());
    }

    /** HP lookup with the full-HP default. */
    public float hpOf(int memberIdx) {
        return hp.get(memberIdx);
    }

    /**
     * Quantizes raw command input into the canonical form and FREEZES the
     * growth law (ceiling/rate/creation time — the vortex phase invariants).
     * Vortex mode ignores the passed radius — its radius DERIVES from the
     * population ({@link #vortexRadius}), and the spin rate follows client-side
     * ({@link #vortexOmega}); handedness comes from the seed's low bit so it
     * survives restarts with zero extra state.
     */
    public static AllayStormData create(BlockPos anchor, int count, double radius, int mode,
            long createdAtGameTime, int seed) {
        AllayStormData d = new AllayStormData();
        d.active = true;
        d.anchor = anchor.atY(CHASE_Y);
        d.count = Math.max(1, Math.min(MAX_COUNT, count));
        d.mode = mode == 2 ? 2 : 1;
        d.stormSeed = seed & SEED_MASK;
        d.createdAtGameTime = createdAtGameTime;
        if (d.mode == 2) {
            d.growthPerSecond = Math.max(0.0, ServerConfig.stormGrowthPerSecond);
            d.finalCount = (int) Math.min(MAX_COUNT, Math.max(d.count, ServerConfig.stormMaxCount));
            d.creationRadius = vortexRadius(d.count);
            // a zero rate means the storm never grows: the growth window is
            // empty and the final radius IS the creation radius
            d.finalRadius = d.growthPerSecond > 0.0 ? vortexRadius(d.finalCount) : d.creationRadius;
            d.radius = d.creationRadius;
        } else {
            d.growthPerSecond = 0.0;
            d.finalCount = d.count;
            d.creationRadius = 0.0f;
            d.finalRadius = 0.0f;
            d.radius = quantizeRadius(radius);
        }
        return d;
    }

    public static float quantizeRadius(double radius) {
        return Math.max(2f, Math.min(64f, Math.round((float) radius / RADIUS_STEP) * RADIUS_STEP));
    }

    /**
     * Vortex-mode radius: derived from the generated population as
     * {@code sqrt(count)/8}, clamped to the canonical [2, 64] range. NOT
     * quantized to the 0.5 grid: the population GROWS continuously (the
     * manager's growth clock), the client re-derives this radius every frame
     * from its interpolated count, and a quantized value would make the
     * expanding shell jump in 0.5-block steps. The server keeps this exact
     * value in {@code #radius} (NBT + state packets — the state packet's
     * byte rounding only touches the wire copy, clients derive their own);
     * ball mode never calls this. The count is floored at 1 so a degenerate
     * input still lands on the radius floor.
     */
    public static float vortexRadius(double count) {
        return Math.max(2f, Math.min(64f, (float) (Math.sqrt(Math.max(1.0, count)) / 8.0)));
    }

    /**
     * Vortex spin coefficient. The typhoon geometry scales purely
     * proportionally with the radius (reach = 1.15·R at the outermost fringe),
     * so a spin rate {@code ω = SPIN_K / R} holds the fringe tangential speed
     * at {@code 0.85 × 6 b/s} — inside the members' hard speed cap with a 15%
     * margin for the conveyor's radial/vertical components — at EVERY storm
     * size. NOT a per-storm constant: the current radius feeds it each frame,
     * and the rotation PHASE is accumulated by the growth-law integral on the
     * runtime side (never as {@code ω * timeSec}, which would teleport the
     * pattern whenever R moves).
     */
    public static final float SPIN_K = 0.85f * 6f / 1.15f;

    /**
     * Vortex spin rate at the given CURRENT radius, signed by the seed's low
     * bit (handedness). The magnitude is {@link #SPIN_K} / R — the phase
     * integration itself lives on the runtime (growth-law integral); this is
     * the instantaneous rate for the servo's target-velocity term.
     */
    public static float vortexOmega(double radiusNow, int seed) {
        float mag = SPIN_K / (float) Math.max(radiusNow, 1.0);
        return (seed & 1) == 0 ? mag : -mag;
    }

    /**
     * NBT serializer: dead set as a varint index list (sparse — a boss fight
     * kills thousands of 131k, never the whole bitmap), HP as parallel
     * index/float-bits int arrays.
     */
    public static final class Serializer implements IAttachmentSerializer<CompoundTag, AllayStormData> {
        @Override
        public AllayStormData read(IAttachmentHolder holder, CompoundTag tag, HolderLookup.Provider provider) {
            AllayStormData d = new AllayStormData();
            d.active = tag.getBoolean("Active");
            d.anchor = BlockPos.of(tag.getLong("Anchor"));
            d.count = Math.max(0, Math.min(MAX_COUNT, tag.getInt("Count")));
            d.mode = tag.getInt("Mode") == 2 ? 2 : 1;
            // re-quantize defensively: the write side always persists the
            // canonical form, but hand-edited or future-format saves must
            // never leak out-of-range values to clients (the radius wire
            // byte's unsigned round-trip relies on the 0.5-step clamp too).
            // Vortex radius is DERIVED state (tracks the generated population,
            // see vortexRadius) — re-derive from the loaded count instead of
            // trusting the stored value.
            d.radius = d.mode == 2 ? vortexRadius(d.count) : quantizeRadius(tag.getFloat("Radius"));
            d.stormSeed = tag.getInt("Seed") & SEED_MASK;
            // frozen growth law. Legacy saves predate these tags: reconstruct
            // a NO-GROWTH law anchored at the loaded size (g = 0 freezes the
            // phase integrals at the current radius — finite and consistent),
            // never zeros (a zero finalRadius would divide the post-growth
            // phase terms into NaN).
            d.growthPerSecond = tag.getDouble("GrowthRate");
            d.finalCount = Math.max(d.count, Math.min(MAX_COUNT, tag.getInt("FinalCount")));
            d.createdAtGameTime = tag.getLong("CreatedAt");
            if (d.mode == 2 && d.growthPerSecond > 0.0 && d.finalCount > d.count) {
                d.creationRadius = vortexRadius(Math.max(1, (float) tag.getFloat("CreationRadius")));
                d.finalRadius = vortexRadius(d.finalCount);
            } else {
                d.growthPerSecond = 0.0;
                d.finalCount = d.count;
                d.creationRadius = d.radius;
                d.finalRadius = d.radius;
            }
            int[] deadIdx = tag.getIntArray("Dead");
            for (int i : deadIdx)
                if (i >= 0 && i < d.count)
                    d.dead.set(i);
            int[] hpIdx = tag.getIntArray("HpIdx");
            int[] hpBits = tag.getIntArray("HpBits");
            int n = Math.min(hpIdx.length, hpBits.length);
            for (int i = 0; i < n; i++)
                if (hpIdx[i] >= 0 && hpIdx[i] < d.count && !d.dead.get(hpIdx[i]))
                    d.hp.put(hpIdx[i], Float.intBitsToFloat(hpBits[i]));
            return d;
        }

        @Override
        public CompoundTag write(AllayStormData d, HolderLookup.Provider provider) {
            if (!d.active && d.dead.isEmpty() && d.hp.isEmpty())
                return null; // nothing worth persisting
            CompoundTag tag = new CompoundTag();
            tag.putBoolean("Active", d.active);
            tag.putLong("Anchor", d.anchor.asLong());
            tag.putInt("Count", d.count);
            tag.putFloat("Radius", d.radius);
            tag.putInt("Mode", d.mode);
            tag.putInt("Seed", d.stormSeed);
            tag.putFloat("CreationRadius", d.creationRadius);
            tag.putFloat("FinalRadius", d.finalRadius);
            tag.putInt("FinalCount", d.finalCount);
            tag.putDouble("GrowthRate", d.growthPerSecond);
            tag.putLong("CreatedAt", d.createdAtGameTime);
            if (!d.dead.isEmpty()) {
                int[] idx = new int[d.dead.cardinality()];
                int o = 0;
                for (int i = d.dead.nextSetBit(0); i >= 0 && o < idx.length; i = d.dead.nextSetBit(i + 1))
                    idx[o++] = i;
                tag.putIntArray("Dead", idx);
            }
            if (!d.hp.isEmpty()) {
                int[] hpIdx = new int[d.hp.size()];
                int[] hpBits = new int[d.hp.size()];
                int o = 0;
                for (var e : d.hp.int2FloatEntrySet()) {
                    hpIdx[o] = e.getIntKey();
                    hpBits[o] = Float.floatToIntBits(e.getFloatValue());
                    o++;
                }
                tag.putIntArray("HpIdx", hpIdx);
                tag.putIntArray("HpBits", hpBits);
            }
            return tag;
        }
    }
}
