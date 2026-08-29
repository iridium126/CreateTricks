package com.iridium126.createmanaindustry.storm;

import java.util.BitSet;

import it.unimi.dsi.fastutil.ints.Int2FloatOpenHashMap;
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
 * (anchor/count/radius/mode/omega/seed), the member DEATH set (alive-member
 * consistency across clients and restarts) and a sparse HP table for
 * damaged-but-alive members. Member POSITIONS never live here: they are
 * client-side GPU state, deterministically re-derived from
 * {@code seed = hash(stormSeed, memberIdx)} plus the shared game clock.
 * <p>
 * All values are created in their CANONICAL QUANTIZED form (radius 0.5-block
 * steps, omega 1/16 rad/s steps) so the persisted state, the wire format and
 * every client agree without re-quantization drift. The sparse HP map holds
 * only damaged-alive members (everything else is the 20 HP vanilla Allay
 * maximum); dead members are the {@link #dead} bit set and never re-enter the
 * HP map. Vanilla regen (2 HP/s) runs in {@link StormManager}.
 */
public final class StormData {

    /** Stress-test ceiling (2^17); mirrors AllayStormSpec.MAX_COUNT. */
    public static final int MAX_COUNT = 131072;
    /** Vanilla Allay MAX_HEALTH. */
    public static final float MAX_HP = 20.0f;
    /** Canonical quantization steps, applied once at creation. */
    public static final float RADIUS_STEP = 0.5f;
    public static final float OMEGA_STEP = 1.0f / 16.0f;
    /** stormSeed is quantized to 24 bits so it survives a float round-trip in GLSL. */
    public static final int SEED_MASK = (1 << 24) - 1;

    public boolean active;
    public BlockPos anchor = BlockPos.ZERO;
    /** Total member population (alive + dead). */
    public int count;
    public float radius;
    /** 1 = ball (test), 2 = vortex. */
    public int mode;
    /** SIGNED angular velocity in rad/s (sign = rotation handedness, server-owned). */
    public float omega;
    /** 24-bit instance seed; member i's seed = hash(stormSeed, i) on every client. */
    public int stormSeed;
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
     * Quantizes raw command input into the canonical form. Handedness comes
     * from the seed's low bit so it survives restarts with zero extra state.
     */
    public static StormData create(BlockPos anchor, int count, double radius, int mode, double omegaMagnitude, int seed) {
        StormData d = new StormData();
        d.active = true;
        d.anchor = anchor.immutable();
        d.count = Math.max(1, Math.min(MAX_COUNT, count));
        d.radius = quantizeRadius(radius);
        d.mode = mode == 2 ? 2 : 1;
        float mag = quantizeOmega(omegaMagnitude);
        d.omega = d.mode == 2 ? ((seed & 1) == 0 ? mag : -mag) : 0f;
        d.stormSeed = seed & SEED_MASK;
        return d;
    }

    public static float quantizeRadius(double radius) {
        return Math.max(2f, Math.min(64f, Math.round((float) radius / RADIUS_STEP) * RADIUS_STEP));
    }

    public static float quantizeOmega(double omega) {
        return Math.max(0.05f, Math.min(3f, Math.round((float) omega / OMEGA_STEP) * OMEGA_STEP));
    }

    /**
     * NBT serializer: dead set as a varint index list (sparse — a boss fight
     * kills thousands of 131k, never the whole bitmap), HP as parallel
     * index/float-bits int arrays.
     */
    public static final class Serializer implements IAttachmentSerializer<CompoundTag, StormData> {
        @Override
        public StormData read(IAttachmentHolder holder, CompoundTag tag, HolderLookup.Provider provider) {
            StormData d = new StormData();
            d.active = tag.getBoolean("Active");
            d.anchor = BlockPos.of(tag.getLong("Anchor"));
            d.count = Math.max(0, Math.min(MAX_COUNT, tag.getInt("Count")));
            // re-quantize defensively: the write side always persists the
            // canonical form, but hand-edited or future-format saves must
            // never leak out-of-range values to clients (the radius wire
            // byte's unsigned round-trip relies on the 0.5-step clamp too)
            d.radius = quantizeRadius(tag.getFloat("Radius"));
            d.mode = tag.getInt("Mode") == 2 ? 2 : 1;
            // quantizeOmega operates on MAGNITUDE (its 0.05 floor clamp would
            // destroy a negative sign), so re-apply the sign afterwards
            float w = tag.getFloat("Omega");
            d.omega = w == 0f ? 0f : Math.signum(w) * quantizeOmega(Math.abs(w));
            d.stormSeed = tag.getInt("Seed") & SEED_MASK;
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
        public CompoundTag write(StormData d, HolderLookup.Provider provider) {
            if (!d.active && d.dead.isEmpty() && d.hp.isEmpty())
                return null; // nothing worth persisting
            CompoundTag tag = new CompoundTag();
            tag.putBoolean("Active", d.active);
            tag.putLong("Anchor", d.anchor.asLong());
            tag.putInt("Count", d.count);
            tag.putFloat("Radius", d.radius);
            tag.putInt("Mode", d.mode);
            tag.putFloat("Omega", d.omega);
            tag.putInt("Seed", d.stormSeed);
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
