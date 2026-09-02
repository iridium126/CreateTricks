package com.iridium126.createmanaindustry.content.allaystorm;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import com.iridium126.createmanaindustry.CMIAttachments;
import com.iridium126.createmanaindustry.CreateManaIndustry;
import com.iridium126.createmanaindustry.config.ServerConfig;
import com.iridium126.createmanaindustry.network.ClientboundStormCenterPacket;
import com.iridium126.createmanaindustry.network.ClientboundStormDamagePacket;
import com.iridium126.createmanaindustry.network.ClientboundStormPositionsPacket;
import com.iridium126.createmanaindustry.network.ClientboundStormStatePacket;
import com.iridium126.createmanaindustry.network.ClientboundStormWavePacket;
import com.iridium126.createmanaindustry.network.ServerboundStormHitPacket;
import com.iridium126.createmanaindustry.network.ServerboundStormPositionsPacket;
import com.iridium126.createmanaindustry.network.ServerboundStormWaveContactPacket;

import it.unimi.dsi.fastutil.ints.IntOpenHashSet;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.stats.Stats;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.damagesource.DamageType;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.animal.allay.Allay;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.enchantment.EnchantmentHelper;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.level.LevelEvent;
import net.neoforged.neoforge.event.server.ServerStoppedEvent;
import net.neoforged.neoforge.event.tick.LevelTickEvent;
import net.neoforged.neoforge.network.PacketDistributor;
import net.neoforged.neoforge.network.handling.IPayloadContext;

/**
 * Server-side Allay Storm authority: activation gating, authority-client
 * election, hit processing, HP regen and position relaying. Per level (one
 * storm per dimension, matching the client engine's single-storm design).
 * <p>
 * The server never simulates member positions — it owns exactly what must be
 * durable and consistent (definition, death set, HP) and treats positions as
 * client-side GPU state:
 * <ul>
 *   <li><b>Activation</b> (per-player, hysteresis): only players within
 *       {@link #ACTIVATE_RANGE} of the anchor run the 131k-member simulation
 *       — the client engine has no simulation distance culling, so "who is
 *       active" IS the performance lever. Same-dimension distant players cost
 *       nothing. The ACTIVATE packet doubles as the join / dimension-change /
 *       re-enter-range resync (parameters + seed + dead bitmap).</li>
 *   <li><b>Authority</b>: the EARLIEST still-active client. It runs the
 *       position-snapshot readback at the configured Hz; the server relays
 *       its snapshots to the other active clients (coarse sanity check only
 *       — it cannot validate what it cannot compute; documented trust
 *       surface). Handoff on disconnect / range exit is seamless: the new
 *       authority's local state becomes the reference and the others ease
 *       toward it through the same soft correction layer.</li>
 *   <li><b>Hits</b>: reported by the attacking client (the server cannot
 *       detect hits against GPU state). Rate-limited per player, damage is
 *       capped by the wire format, dead members and remote players are
 *       ignored; death is decided HERE and only here, then broadcast with
 *       the death bit to every active player including the attacker.</li>
 *   <li><b>Regen</b>: vanilla 2 HP/s over the sparse damaged-alive table
 *       (iterating only damaged members; a full-HP boss costs nothing).</li>
 *   <li><b>Chase</b>: the anchor is not static — the center pursues the
 *       nearest player (horizontal distance) at {@link #CHASE_SPEED}, altitude
 *       pinned to {@code ServerConfig.stormChaseY} (a sky storm). The center
 *       integrates as DOUBLES in the per-level runtime (no block staircase);
 *       {@code data.anchor} stays in sync as its containing block for
 *       persistence, activation scanning and the hit reach check. Clients see
 *       the center through {@link ClientboundStormCenterPacket} snapshots
 *       every {@link #CENTER_INTERVAL_TICKS} (plus one on activation) and lerp
 *       the last two one interval behind — a stream-derivable, continuous
 *       center that is identical on every client, at half the packet rate the
 *       old per-step UPDATE broadcast produced. Being server-owned, the chased
 *       center is the shape-consistency anchor of the typhoon home points.</li>
 * </ul>
 */
@EventBusSubscriber(modid = CreateManaIndustry.MODID)
public final class AllayStormManager {

    /**
     * Activation radius in blocks: default client fade end (96 + 24 ramp =
     * 120) plus the maximum storm extent (radius 64 · 1.15 fringe ≈ 74).
     * Deliberately a server constant — the fade distance is a CLIENT config
     * the server cannot read.
     */
    public static final double ACTIVATE_RANGE = 196.0;
    /** Hysteresis margin past {@link #ACTIVATE_RANGE} before deactivating. */
    public static final double HYSTERESIS = 64.0;

    private static final int SCAN_INTERVAL_TICKS = 20;
    private static final int MAX_HITS_PER_SECOND = 12;
    /** Chase speed toward the nearest player, blocks/s (horizontal only). */
    public static final double CHASE_SPEED = 2.0;
    /** Center-snapshot broadcast cadence in ticks (1 Hz; clients lerp one interval behind). */
    public static final int CENTER_INTERVAL_TICKS = 20;
    /** Coarse sanity bound for relayed positions (blocks from the anchor). */
    private static final float RELAY_BOUND = 320.0f;
    /** Coarse sanity bound for relayed velocities (b/s — the wire's byte envelope). */
    private static final float RELAY_VEL_BOUND = 8.0f;

    // ---- dive waves (the storm's offense; see docs/allay-storm-ai.md) --------

    /** Concurrent wave ceiling (the GPU wave-uniform array is sized to this). */
    public static final int MAX_WAVES = 4;
    /** Shared-clock seconds between launch and the squad's first pursuit frame. */
    private static final float WAVE_ASSEMBLE_SECONDS = 2.0f;
    /** Shared-clock seconds the whole wave stays live (hard stop for every diver). */
    private static final float WAVE_WINDOW_SECONDS = 24.0f;
    /** Corridor end height above the target player's feet (inside the shaft band). */
    private static final double WAVE_END_ABOVE_FEET = 10.0;

    /**
     * Dive-wave sword tier from the configured per-contact damage
     * ({@code ServerConfig.stormWaveDamage}): the squad's carried sword reads
     * as a damage gauge. Hardcoded thresholds in the
     * {@code EmitterSpec.HeldItem} id order (the same byte the wave packet
     * ships and the client's UV table indexes); a mid-storm config change
     * applies at the NEXT launch — in-flight waves keep the tier frozen with
     * their schedule, so no retroactive visual switch ever exists.
     */
    public static int swordTier(float damage) {
        if (damage >= 10.0f)
            return 6; // netherite
        if (damage >= 7.0f)
            return 5; // diamond (the 7.0 default)
        if (damage >= 5.0f)
            return 4; // iron
        if (damage >= 4.0f)
            return 3; // golden
        if (damage >= 3.0f)
            return 2; // stone
        return 1;     // wooden
    }

    /**
     * One launched dive wave. Everything on it is wire state (identical on
     * every client); the only server-only field is {@code reported} — the
     * per-member contact dedupe (a member lands its shot exactly once per
     * wave). Positions of the DIVERS never live here: the corridor defines
     * where they steer, the clients' GPU pools hold where they are.
     */
    private static final class Wave {
        final int id;
        final int seed;
        final float fraction;
        final UUID target;
        /** Smoothed corridor waypoints, flat world xyz (>= 2 points). */
        final double[] path;
        final float assembleSec;
        final float diveUntilSec;
        final IntOpenHashSet reported = new IntOpenHashSet();

        Wave(int id, int seed, float fraction, UUID target, double[] path,
                float assembleSec, float diveUntilSec) {
            this.id = id;
            this.seed = seed;
            this.fraction = fraction;
            this.target = target;
            this.path = path;
            this.assembleSec = assembleSec;
            this.diveUntilSec = diveUntilSec;
        }
    }

    // ---- per-level runtime ---------------------------------------------------

    private static final Map<ResourceKey<Level>, Runtime> RUNTIMES = new HashMap<>();

    private static final class Runtime {
        final ServerLevel level;
        long orderCounter;
        int scanCounter = SCAN_INTERVAL_TICKS; // force a scan on the first tick
        // Continuous chased center (NaN = seed from data.anchor on first use);
        // chaseVX/VZ is the current velocity, broadcast for the client stream.
        double chaseX = Double.NaN;
        double chaseZ = Double.NaN;
        double chaseVX;
        double chaseVZ;
        int centerTimer;
        /** Fractional member accumulator of the growth clock (rate/20 per tick). */
        double growthAcc;
        final Map<UUID, Track> tracks = new HashMap<>();
        /** Live dive waves (<= MAX_WAVES, one per target). */
        final List<Wave> waves = new ArrayList<>();
        final Map<UUID, Wave> waveByTarget = new HashMap<>();
        int waveIdCounter;

        Runtime(ServerLevel level) {
            this.level = level;
        }
    }

    private static final class Track {
        boolean active;
        boolean authority;
        long order;
        long lastWaveLaunchGameTime = Long.MIN_VALUE;
        final ArrayDeque<Long> hitTimes = new ArrayDeque<>();
    }

    private AllayStormManager() {
    }

    // ---- events ------------------------------------------------------------

    @SubscribeEvent
    public static void onLevelTick(LevelTickEvent.Post event) {
        if (!(event.getLevel() instanceof ServerLevel level)
                || !level.hasData(CMIAttachments.STORM_DATA.get()))
            return;
        tick(level);
    }

    @SubscribeEvent
    public static void onLevelUnload(LevelEvent.Unload event) {
        if (event.getLevel() instanceof ServerLevel level)
            RUNTIMES.remove(level.dimension());
    }

    @SubscribeEvent
    public static void onServerStopped(ServerStoppedEvent event) {
        RUNTIMES.clear();
    }

    // ---- tick ----------------------------------------------------------------

    private static void tick(ServerLevel level) {
        AllayStormData data = level.getData(CMIAttachments.STORM_DATA.get());
        Runtime rt = runtime(level);
        if (!data.active) {
            if (rt.tracks.values().stream().anyMatch(t -> t.active))
                deactivateAll(level, rt);
            return;
        }
        regen(level, data);
        chase(level, data);
        grow(level, rt, data);
        tickWaves(level, rt, data);
        if (++rt.scanCounter >= SCAN_INTERVAL_TICKS) {
            rt.scanCounter = 0;
            scan(level, rt, data);
        }
    }

    /** Shared-clock seconds — the SAME domain every client derives: (gameTime mod 2^21)/20. */
    private static float clockSeconds(long gameTime) {
        return (gameTime & ((1 << 21) - 1)) / 20.0f;
    }

    /**
     * Dive-wave brain: expires and aborts dead waves, then launches new ones
     * at per-player cooldowns. Eligibility: active track, no wave running for
     * the target, a wave slot free, within {@code stormWaveRange} of the
     * chased center (horizontal — the storm is pinned to chase altitude) and
     * UNDER OPEN SKY. Open sky is the structural guarantee that the corridor's
     * final vertical descent is unobstructed (and the counterplay: a roof is
     * shelter). The check runs at cooldown-fire time only — one heightmap
     * query per player per opportunity, never per tick.
     */
    private static void tickWaves(ServerLevel level, Runtime rt, AllayStormData data) {
        if (rt.waves.isEmpty() && !data.active)
            return;
        long now = level.getGameTime();
        float nowSec = clockSeconds(now);

        // expire (deterministic on clients too — no packet needed) / abort (target gone)
        Iterator<Wave> it = rt.waves.iterator();
        while (it.hasNext()) {
            Wave w = it.next();
            ServerPlayer tp = level.getServer().getPlayerList().getPlayer(w.target);
            Track tt = rt.tracks.get(w.target);
            boolean invalid = tp == null || tp.serverLevel() != level
                    || tt == null || !tt.active || !tp.isAlive();
            if (nowSec > w.diveUntilSec) {
                it.remove();
                rt.waveByTarget.remove(w.target);
            } else if (invalid || !data.active) {
                broadcastWave(level, rt, new ClientboundStormWavePacket(w.id, true, 0, 0, 0, null, 0, 0, 0));
                it.remove();
                rt.waveByTarget.remove(w.target);
            }
        }

        if (!data.active)
            return;
        // launch pass: cheap gates before the (rare) corridor build
        double rangeSq = ServerConfig.stormWaveRange * ServerConfig.stormWaveRange;
        for (var entry : rt.tracks.entrySet()) {
            if (rt.waves.size() >= MAX_WAVES)
                break;
            Track t = entry.getValue();
            if (!t.active || rt.waveByTarget.containsKey(entry.getKey()))
                continue;
            long cooldownTicks = (long) (ServerConfig.stormWaveInterval * 20.0);
            if (t.lastWaveLaunchGameTime != Long.MIN_VALUE
                    && now - t.lastWaveLaunchGameTime < cooldownTicks)
                continue;
            ServerPlayer p = level.getServer().getPlayerList().getPlayer(entry.getKey());
            if (p == null || !p.isAlive())
                continue;
            double dx = p.getX() - rt.chaseX;
            double dz = p.getZ() - rt.chaseZ;
            if (dx * dx + dz * dz > rangeSq)
                continue;
            if (!level.canSeeSky(p.blockPosition()))
                continue;
            int alive = data.aliveCount();
            if (alive <= 0)
                continue;
            launchWave(level, rt, data, p, now, alive);
            if (rt.waves.size() >= MAX_WAVES)
                break;
        }
    }

    /**
     * Launches one dive wave at {@code target}: freezes the squad fraction
     * (population-scaled, capped — population attrition visibly weakens the
     * offense, the boss's health bar made legible), computes the corridor
     * once, and broadcasts the event to every active client. The schedule is
     * stamped in shared-clock seconds so every client's GPU phases the wave
     * identically with zero extra sync.
     */
    private static void launchWave(ServerLevel level, Runtime rt, AllayStormData data,
            ServerPlayer target, long now, int alive) {
        Track t = rt.tracks.get(target.getUUID());
        if (t == null)
            return;
        t.lastWaveLaunchGameTime = now;
        int id = ++rt.waveIdCounter;
        int seed = level.random.nextInt(1 << 24);
        float fraction = Math.min((float) ServerConfig.stormWaveFraction,
                (float) ServerConfig.stormWaveMaxSize / Math.max(1, alive));
        Vec3 start = new Vec3(rt.chaseX, ServerConfig.stormChaseY, rt.chaseZ);
        Vec3 end = new Vec3(target.getX(),
                Math.min(target.getY() + WAVE_END_ABOVE_FEET, ServerConfig.stormChaseY - 2.0),
                target.getZ());
        double[] corridor = AllayStormWaves.buildCorridor(level, start, end);
        // waypoints ride rel-to-anchor (the hit/contact reconstruction frame)
        Vec3 anchor = new Vec3(data.anchor.getX(), data.anchor.getY(), data.anchor.getZ());
        float[] rel = new float[corridor.length];
        for (int i = 0; i < corridor.length; i += 3) {
            rel[i] = (float) (corridor[i] - anchor.x);
            rel[i + 1] = (float) (corridor[i + 1] - anchor.y);
            rel[i + 2] = (float) (corridor[i + 2] - anchor.z);
        }
        float assemble = clockSeconds(now) + WAVE_ASSEMBLE_SECONDS;
        Wave w = new Wave(id, seed, fraction, target.getUUID(), corridor, assemble,
                assemble + WAVE_WINDOW_SECONDS);
        rt.waves.add(w);
        rt.waveByTarget.put(target.getUUID(), w);
        broadcastWave(level, rt, new ClientboundStormWavePacket(id, false, seed, fraction,
                target.getId(), rel, assemble, w.diveUntilSec,
                swordTier((float) ServerConfig.stormWaveDamage)));
    }

    /** Broadcasts a wave event to every active-track player (all must render the same attack). */
    private static void broadcastWave(ServerLevel level, Runtime rt, ClientboundStormWavePacket packet) {
        for (var entry : rt.tracks.entrySet()) {
            if (!entry.getValue().active)
                continue;
            ServerPlayer p = rt.level.getServer().getPlayerList().getPlayer(entry.getKey());
            if (p != null)
                PacketDistributor.sendToPlayer(p, packet);
        }
    }

    /**
     * Growth clock: generates {@code ServerConfig.stormGrowthPerSecond} new
     * member indices per second (fractional accumulator over the level tick)
     * while the population is below {@code ServerConfig.stormMaxCount}. Runs
     * regardless of player activity — the storm is a persistent world boss
     * that keeps assembling while unobserved. The clock counts GENERATED
     * members (kills never slow it; dead indices never regenerate), so a
     * storm's total lifetime budget is exactly the config ceiling. New
     * indices reach clients through the 1 Hz center packet; clients spawn
     * them on the spawn ring and fly them to their storm positions. A rate
     * of 0 disables growth. The radius is not server state — every client
     * derives it from the synced population ({@link AllayStormData#vortexRadius}).
     */
    private static void grow(ServerLevel level, Runtime rt, AllayStormData data) {
        double rate = data.growthPerSecond;
        int max = data.finalCount;
        if (rate <= 0.0 || data.count >= max)
            return;
        rt.growthAcc += rate / 20.0;
        int n = (int) rt.growthAcc;
        if (n <= 0)
            return;
        rt.growthAcc -= n;
        data.count = Math.min(max, data.count + n);
        level.setData(CMIAttachments.STORM_DATA.get(), data);
    }

    /**
     * Integrates the center continuously toward the nearest player (horizontal
     * distance) at {@link #CHASE_SPEED}; no players (or the player already
     * overhead) means hover. {@code data.anchor} follows as the containing
     * block so persistence, activation scanning and the hit reach check keep
     * working unchanged. Center snapshots fly every
     * {@link #CENTER_INTERVAL_TICKS}; clients lerp the last two one interval
     * behind, so a 1 Hz stream renders as continuous motion and every client
     * derives the same center from the same packets.
     */
    private static void chase(ServerLevel level, AllayStormData data) {
        Runtime rt = runtime(level);
        if (Double.isNaN(rt.chaseX)) {
            rt.chaseX = data.anchor.getX() + 0.5;
            rt.chaseZ = data.anchor.getZ() + 0.5;
        }
        ServerPlayer target = null;
        double best = Double.MAX_VALUE;
        for (ServerPlayer p : level.players()) {
            double dx = p.getX() - rt.chaseX;
            double dz = p.getZ() - rt.chaseZ;
            double d2 = dx * dx + dz * dz;
            if (d2 < best) {
                best = d2;
                target = p;
            }
        }
        double vx = 0.0;
        double vz = 0.0;
        if (target != null) {
            double dx = target.getX() - rt.chaseX;
            double dz = target.getZ() - rt.chaseZ;
            double dist = Math.sqrt(dx * dx + dz * dz);
            if (dist >= 1.0) {
                vx = dx / dist * CHASE_SPEED;
                vz = dz / dist * CHASE_SPEED;
            }
        }
        rt.chaseVX = vx;
        rt.chaseVZ = vz;
        // the anchor follows as the CONTAINING BLOCK of the chased center, at
        // the CONFIGURED altitude — synced every tick, not only while moving,
        // so a mid-flight config change to stormChaseY self-heals the anchor
        // Y (the hit/contact reach checks reconstruct against this anchor; a
        // stale Y would reject every report until the storm happened to move)
        BlockPos containing = BlockPos.containing(rt.chaseX, ServerConfig.stormChaseY, rt.chaseZ);
        if (!containing.equals(data.anchor)) {
            data.anchor = containing;
            level.setData(CMIAttachments.STORM_DATA.get(), data);
        }
        if (vx != 0.0 || vz != 0.0) {
            rt.chaseX += vx / 20.0;
            rt.chaseZ += vz / 20.0;
        }
        if (++rt.centerTimer >= CENTER_INTERVAL_TICKS) {
            rt.centerTimer = 0;
            sendCenter(level, rt);
        }
    }

    /** Broadcasts the continuous center + chase velocity + growth state to every active client. */
    private static void sendCenter(ServerLevel level, Runtime rt) {
        AllayStormData data = rt.level.getData(CMIAttachments.STORM_DATA.get());
        ClientboundStormCenterPacket packet = new ClientboundStormCenterPacket(
                (float) rt.chaseX, ServerConfig.stormChaseY, (float) rt.chaseZ,
                (float) rt.chaseVX, (float) rt.chaseVZ, data.count,
                rt.level.getGameTime());
        for (var entry : rt.tracks.entrySet()) {
            if (!entry.getValue().active)
                continue;
            ServerPlayer p = rt.level.getServer().getPlayerList().getPlayer(entry.getKey());
            if (p != null)
                PacketDistributor.sendToPlayer(p, packet);
        }
    }

    /** Vanilla Allay regen: heal(1.0) per 10 ticks = 2 HP/s, only damaged members. */
    private static void regen(ServerLevel level, AllayStormData data) {
        if (data.hp.isEmpty())
            return;
        boolean changed = false;
        var it = data.hp.int2FloatEntrySet().iterator();
        while (it.hasNext()) {
            var e = it.next();
            float hp = e.getFloatValue() + 0.1f;
            if (hp >= AllayStormData.MAX_HP) {
                it.remove();
                changed = true;
            } else if (e.getFloatValue() != hp) {
                e.setValue(hp);
                changed = true;
            }
        }
        if (changed)
            level.setData(CMIAttachments.STORM_DATA.get(), data);
    }

    /** Re-evaluates every player's activation state and the authority assignment. */
    private static void scan(ServerLevel level, Runtime rt, AllayStormData data) {
        // players that left the level (dimension change, logout) drop their
        // track. The player lookup is GLOBAL across dimensions, so "still
        // online" is NOT enough: a cross-dimension player must be detected
        // via their current ServerLevel, or their stale active track would
        // pin the authority forever and their client would never be told to
        // disperse (belt-and-braces: the client also resets itself on the
        // level-change events)
        for (var it = rt.tracks.entrySet().iterator(); it.hasNext();) {
            var entry = it.next();
            ServerPlayer player = level.getServer().getPlayerList().getPlayer(entry.getKey());
            if (player == null || player.serverLevel() != level) {
                Track t = entry.getValue();
                if (t.active) {
                    t.active = false;
                    t.authority = false;
                    if (player != null)
                        PacketDistributor.sendToPlayer(player, ClientboundStormStatePacket.DEACTIVATE());
                }
                it.remove();
            }
        }
        if (!data.active) {
            deactivateAll(level, rt);
            return;
        }
        double ax = data.anchor.getX() + 0.5;
        double ay = data.anchor.getY();
        double az = data.anchor.getZ() + 0.5;
        double activateSq = ACTIVATE_RANGE * ACTIVATE_RANGE;
        double deactivateSq = (ACTIVATE_RANGE + HYSTERESIS) * (ACTIVATE_RANGE + HYSTERESIS);
        for (ServerPlayer player : level.players()) {
            Track t = rt.tracks.get(player.getUUID());
            double distSq = player.distanceToSqr(ax, ay, az);
            if (t == null || !t.active) {
                if (distSq <= activateSq)
                    activate(level, rt, data, player);
            } else if (distSq > deactivateSq) {
                deactivate(level, rt, data, player);
            }
        }
        recomputeAuthority(rt, data);
    }

    private static void activate(ServerLevel level, Runtime rt, AllayStormData data, ServerPlayer player) {
        Track t = rt.tracks.computeIfAbsent(player.getUUID(), k -> new Track());
        t.active = true;
        t.order = rt.orderCounter++;
        // the wave cooldown counts from activation: a freshly joined player
        // gets one assemble window before the storm's first strike at them
        t.lastWaveLaunchGameTime = level.getGameTime();
        PacketDistributor.sendToPlayer(player, ClientboundStormStatePacket.ACTIVATE(
                data.anchor, data.count, data.stormSeed,
                t.authority, ServerConfig.stormCorrectionHz, data.dead.toByteArray(),
                data.creationRadius, data.finalRadius, (float) data.growthPerSecond,
                data.createdAtGameTime));
        // seed the joining client's center stream immediately — without this
        // its interpolation would sit at the (block-quantized) ACTIVATE anchor
        // for up to one interval while the typhoon drifts on
        sendCenter(level, rt);
    }

    private static void deactivate(ServerLevel level, Runtime rt, AllayStormData data, ServerPlayer player) {
        Track t = rt.tracks.get(player.getUUID());
        if (t == null || !t.active)
            return;
        t.active = false;
        t.authority = false;
        PacketDistributor.sendToPlayer(player, ClientboundStormStatePacket.DEACTIVATE());
    }

    private static void deactivateAll(ServerLevel level, Runtime rt) {
        for (var entry : rt.tracks.entrySet()) {
            Track t = entry.getValue();
            if (!t.active)
                continue;
            t.active = false;
            t.authority = false;
            ServerPlayer player = rt.level.getServer().getPlayerList().getPlayer(entry.getKey());
            if (player != null)
                PacketDistributor.sendToPlayer(player, ClientboundStormStatePacket.DEACTIVATE());
        }
    }

    /** Authority = earliest-activated still-active client; UPDATE packets flip the bit on change. */
    private static void recomputeAuthority(Runtime rt, AllayStormData data) {
        UUID earliest = null;
        long best = Long.MAX_VALUE;
        for (var entry : rt.tracks.entrySet()) {
            Track t = entry.getValue();
            if (t.active && t.order < best) {
                best = t.order;
                earliest = entry.getKey();
            }
        }
        for (var entry : rt.tracks.entrySet()) {
            Track t = entry.getValue();
            if (!t.active) {
                t.authority = false;
                continue;
            }
            boolean should = entry.getKey().equals(earliest);
            if (t.authority != should) {
                t.authority = should;
                ServerPlayer player = rt.level.getServer().getPlayerList().getPlayer(entry.getKey());
                if (player != null)
                    PacketDistributor.sendToPlayer(player, ClientboundStormStatePacket.UPDATE(
                            data.anchor, data.count, data.stormSeed,
                            should, ServerConfig.stormCorrectionHz,
                            data.creationRadius, data.finalRadius, (float) data.growthPerSecond,
                            data.createdAtGameTime));
            }
        }
    }

    // ---- packet handling ------------------------------------------------------

    /** Consumes {@link ServerboundStormHitPacket} (attacker-reported melee hit). */
    public static void handleHit(ServerboundStormHitPacket packet, IPayloadContext ctx) {
        if (!(ctx.player() instanceof ServerPlayer player))
            return;
        ServerLevel level = player.serverLevel();
        if (!level.hasData(CMIAttachments.STORM_DATA.get()))
            return;
        AllayStormData data = level.getData(CMIAttachments.STORM_DATA.get());
        if (!data.active)
            return;
        Runtime rt = runtime(level);
        Track t = rt.tracks.get(player.getUUID());
        if (t == null || !t.active)
            return; // remote player: no local storm to hit
        int idx = packet.memberIdx();
        if (idx < 0 || idx >= data.count || data.dead.get(idx))
            return; // unknown or already dead member
        // cheap plausibility: the reported hit spot must sit within melee
        // reach of the attacker's SERVER-side position (the +8 covers the
        // server view lagging a fast-moving client by ~1-2 blocks plus
        // latency jitter). Kills the "hit any member from anywhere inside
        // the activation range" corner of the documented trust surface, and
        // is immune to anchor moves / radius shrinks (members fly there for
        // seconds, but an attacker can only hit what they stand next to).
        // Deliberately melee-shaped: a future ranged report path re-scopes
        // or drops this check (one conditional).
        double hitX = data.anchor.getX() + packet.relX();
        double hitY = data.anchor.getY() + packet.relY();
        double hitZ = data.anchor.getZ() + packet.relZ();
        double reach = player.entityInteractionRange() + 8.0;
        if (player.distanceToSqr(hitX, hitY, hitZ) > reach * reach)
            return;
        if (!rateLimit(t))
            return;
        float hpBefore = data.hpOf(idx);
        float hp = hpBefore - packet.damage();
        boolean died = hp <= 0f;
        if (died) {
            data.dead.set(idx);
            data.hp.remove(idx);
        } else {
            data.hp.put(idx, hp);
        }
        level.setData(CMIAttachments.STORM_DATA.get(), data);

        // vanilla Player.attack SERVER half on a landed hit (verbatim shape):
        // weapon durability via the item's own hurtEnemy/postHurtEnemy chain
        // — swords/tridents/maces 1 point, diggers 2, items without overrides
        // 0; Unbreaking, the creative bypass and the break shrink+sound all
        // live inside hurtAndBreak. A REJECTED report never reaches here (the
        // vanilla analog is hurt() returning false — no durability either);
        // a KILLING hit consumes like any other landed hit. The throwaway
        // proxy mirrors the client's proxyFor: vanilla durability
        // implementations never read the target, but modded items and the
        // POST_ATTACK effects below may.
        ItemStack weapon = player.getWeaponItem();
        ItemStack weaponCopy = weapon.copy();
        Allay proxyTarget = new Allay(EntityType.ALLAY, level);
        proxyTarget.setPos(hitX, hitY, hitZ);
        boolean hurtEnemyResult = weapon.hurtEnemy(proxyTarget, player);
        // vanilla Player.attack:1380 — POST_ATTACK enchantment effects fire
        // SERVER-side only, in exactly this slot (after hurtEnemy, before
        // postHurtEnemy). This is what makes Wind Burst work on a landed
        // mace smash: its effect is ATTACKER-affected, explodes at the
        // ATTACKER's own position behind the fall-distance ≥ 1.5 gate, and
        // the knockback-only blast launches the player like a real-entity
        // hit. Victim-side effects (Fire Aspect's ignite) reach only the
        // throwaway proxy, which is not in the world and cannot burn
        // anything.
        EnchantmentHelper.doPostAttackEffects(level, proxyTarget,
                player.damageSources().playerAttack(player));
        if (!weapon.isEmpty()) {
            if (hurtEnemyResult)
                weapon.postHurtEnemy(proxyTarget, player);
            if (weapon.isEmpty()) {
                net.neoforged.neoforge.event.EventHooks.onPlayerDestroyItem(player, weaponCopy,
                        weapon == player.getMainHandItem() ? InteractionHand.MAIN_HAND : InteractionHand.OFF_HAND);
                if (weapon == player.getMainHandItem())
                    player.setItemInHand(InteractionHand.MAIN_HAND, ItemStack.EMPTY);
                else
                    player.setItemInHand(InteractionHand.OFF_HAND, ItemStack.EMPTY);
            }
        }
        // f8 = healthBefore - healthAfter; the server-owned hp has no armor /
        // absorption term, so min(hpBefore, damage) IS the exact damage dealt
        player.awardStat(Stats.DAMAGE_DEALT, Math.round(Math.min(hpBefore, packet.damage()) * 10.0F));
        player.causeFoodExhaustion(0.1F);

        // server-side hurt/death audio for everyone EXCEPT the attacker —
        // the overload's nullable-player argument excludes exactly that
        // player, who already played its local prediction on the click
        // (vanilla parity: real entities play their hurt sounds server-side)
        level.playSound(player,
                data.anchor.getX() + packet.relX(),
                data.anchor.getY() + packet.relY(),
                data.anchor.getZ() + packet.relZ(),
                died ? SoundEvents.ALLAY_DEATH : SoundEvents.ALLAY_HURT,
                SoundSource.NEUTRAL, 1.0F, 1.0F);
        ClientboundStormDamagePacket out = new ClientboundStormDamagePacket(idx, packet.damage(),
                packet.kbX(), packet.kbZ(), packet.light(), died, player.getId(),
                packet.crit(), packet.magic(), packet.hearts(),
                packet.relX(), packet.relY(), packet.relZ());
        // pure server circuit: the attacker receives the broadcast like everyone
        for (var entry : rt.tracks.entrySet()) {
            Track tr = entry.getValue();
            if (!tr.active)
                continue;
            ServerPlayer p = rt.level.getServer().getPlayerList().getPlayer(entry.getKey());
            if (p != null)
                PacketDistributor.sendToPlayer(p, out);
        }
    }

    /** Relays an authority client's position snapshot to the other active clients. */
    public static void relayPositions(ServerboundStormPositionsPacket packet, IPayloadContext ctx) {
        if (!(ctx.player() instanceof ServerPlayer player))
            return;
        ServerLevel level = player.serverLevel();
        if (!level.hasData(CMIAttachments.STORM_DATA.get()))
            return;
        AllayStormData data = level.getData(CMIAttachments.STORM_DATA.get());
        if (!data.active)
            return;
        Runtime rt = runtime(level);
        Track t = rt.tracks.get(player.getUUID());
        if (t == null || !t.active || !t.authority)
            return; // only the authority feeds the stream
        // coarse sanity per entry. Stride-8 layout: (memberIdx, pad,
        // relPos.xyz, vel.xyz) — the positions are offsets 2..4 (the old
        // check started at the always-zero pad slot and never looked at z),
        // and memberIdx must be a plausible member of the CURRENT population
        // (a snapshot from a just-replaced generation with a larger count
        // fails here and drops — fine, the soft layer self-heals). Any
        // violation discards the whole snapshot.
        float[] e = packet.entries();
        int stride = ServerboundStormPositionsPacket.STRIDE;
        // NaN-safe bound form ("!(x <= bound)" also rejects NaN and both
        // infinities). The wire quantization bounds every value today
        // (positions short → ±2048 blocks, velocities byte → ±8 b/s), but this
        // check IS the trust boundary: it stays self-sufficient even if the
        // codec ever widens, and the velocity components were previously
        // unchecked outright.
        for (int i = 0; i + stride - 1 < e.length; i += stride) {
            if (!(e[i] >= 0f && e[i] < data.count)
                    || !(Math.abs(e[i + 2]) <= RELAY_BOUND) || !(Math.abs(e[i + 3]) <= RELAY_BOUND)
                    || !(Math.abs(e[i + 4]) <= RELAY_BOUND)
                    || !(Math.abs(e[i + 5]) <= RELAY_VEL_BOUND) || !(Math.abs(e[i + 6]) <= RELAY_VEL_BOUND)
                    || !(Math.abs(e[i + 7]) <= RELAY_VEL_BOUND))
                return;
        }
        ClientboundStormPositionsPacket relay = new ClientboundStormPositionsPacket(packet.gameTime(), e);
        for (var entry : rt.tracks.entrySet()) {
            Track tr = entry.getValue();
            if (!tr.active || entry.getKey().equals(player.getUUID()))
                continue;
            ServerPlayer p = rt.level.getServer().getPlayerList().getPlayer(entry.getKey());
            if (p != null)
                PacketDistributor.sendToPlayer(p, relay);
        }
    }

    private static boolean rateLimit(Track t) {
        long now = System.currentTimeMillis();
        while (!t.hitTimes.isEmpty() && now - t.hitTimes.peekFirst() > 1000L)
            t.hitTimes.pollFirst();
        if (t.hitTimes.size() >= MAX_HITS_PER_SECOND)
            return false;
        t.hitTimes.addLast(now);
        return true;
    }

    // ---- dive-wave contact reports ---------------------------------------------

    /**
     * Consumes {@link ServerboundStormWaveContactPacket}: the target player's
     * client self-reports a diving member's contact; the server decides the
     * damage. Validation chain (docs/allay-storm-ai.md §7): storm and wave
     * live, the sender IS the wave's target, the report sits inside the wave
     * window (shared-clock seconds, same domain the client phased its GPU
     * with), the member index is a plausible live member, the SQUAD
     * MEMBERSHIP re-derives from the float-exact shared hash chains (the GPU
     * selection and this test cannot disagree — no state was ever synced for
     * it), the per-member-per-wave dedupe (one shot per diver), and a
     * plausibility reach check (the same +8 margin as the melee reports,
     * absorbing the chased-center-vs-anchor skew). Damage routes through
     * {@code player.hurt} with the data-driven {@code storm_peck} type, so
     * vanilla i-frames/armor/absorption/totems and the hurt sound all apply
     * with zero extra code — concurrent divers cannot burst the i-frame gate.
     * <p>
     * The source is POSITION-ONLY ({@code new DamageSource(holder, contact)}):
     * {@code isDamageSourceBlocked} runs its horizontal direction check off
     * {@code getSourcePosition()} and fails unconditionally when it is null,
     * so the old entity-less source made shields unable to block a diver.
     * With the contact point as the source position, blocking, shield
     * durability, the {@code DAMAGE_BLOCKED_BY_SHIELD} stat, the SHIELD_BLOCK
     * sound broadcast and the {@code LivingShieldBlockEvent} all ride the
     * vanilla {@code hurt} pipeline for free — and its internal 0.4 knockback
     * derives from the SAME source position (the vanilla melee shape), which
     * is why the old custom {@code knockback(0.5)} is gone (it would
     * double-push now). Near-vertical centered dives keep vanilla's overhead
     * edge case: the horizontal-only check yields NaN → unblocked, by design.
     */
    public static void handleWaveContact(ServerboundStormWaveContactPacket packet, IPayloadContext ctx) {
        if (!(ctx.player() instanceof ServerPlayer player))
            return;
        ServerLevel level = player.serverLevel();
        if (!level.hasData(CMIAttachments.STORM_DATA.get()))
            return;
        AllayStormData data = level.getData(CMIAttachments.STORM_DATA.get());
        if (!data.active)
            return;
        Runtime rt = runtime(level);
        Track t = rt.tracks.get(player.getUUID());
        if (t == null || !t.active)
            return;
        Wave w = null;
        for (Wave cand : rt.waves)
            if (cand.id == packet.waveId()) {
                w = cand;
                break;
            }
        if (w == null || !player.getUUID().equals(w.target))
            return;
        float nowSec = clockSeconds(level.getGameTime());
        if (nowSec < w.assembleSec || nowSec > w.diveUntilSec)
            return;
        int idx = packet.memberIdx();
        if (idx < 0 || idx >= data.count || data.dead.get(idx))
            return;
        if (!AllayStormWaves.isWaveMember(data.stormSeed, idx, w.seed, w.fraction))
            return;
        if (!w.reported.add(idx))
            return; // one shot per diver per wave
        Vec3 anchor = new Vec3(data.anchor.getX(), data.anchor.getY(), data.anchor.getZ());
        Vec3 contact = anchor.add(packet.relX(), packet.relY(), packet.relZ());
        double reach = player.entityInteractionRange() + 8.0;
        if (player.distanceToSqr(contact) > reach * reach)
            return;

        // position-only source: the contact point feeds isDamageSourceBlocked's
        // direction check (null source position = shield can never block) and
        // hurt's own 0.4 positional knockback — no entity, no custom push
        DamageSource source = new DamageSource(
                level.registryAccess().registryOrThrow(Registries.DAMAGE_TYPE)
                        .getHolderOrThrow(CMIDamageTypes.STORM_PECK),
                contact);
        player.hurt(source, (float) ServerConfig.stormWaveDamage);
    }

    // ---- command surface -------------------------------------------------------

    /**
     * Creates or updates the storm. The count argument is the INITIAL
     * population of a NEW storm (clamped by {@code ServerConfig.stormMaxCount});
     * the storm then grows on the tick clock toward that ceiling. Re-running
     * the command on an ACTIVE storm is an anchor-only retarget (clients ease
     * over): generated progress, seed, HP and deaths are preserved, because
     * the population is a growing quantity and can no longer define a
     * "population change" restart. {@code stop} + re-create resets everything.
     * The radius derives from the population ({@link AllayStormData#vortexRadius});
     * the angular velocity is never server state — clients derive it from the
     * radius and the seed's low bit.
     */
    public static void setStorm(ServerLevel level, BlockPos anchor, int count) {
        AllayStormData data = level.getData(CMIAttachments.STORM_DATA.get());
        if (data.active) {
            data.anchor = anchor.atY(ServerConfig.stormChaseY);
            level.setData(CMIAttachments.STORM_DATA.get(), data);
            broadcast(level, data, true);
        } else {
            int initial = Math.max(1, Math.min(ServerConfig.stormMaxCount, count));
            AllayStormData fresh = AllayStormData.create(anchor, initial,
                    level.getGameTime(), level.random.nextInt(1 << 24));
            copyInto(fresh, data);
            level.setData(CMIAttachments.STORM_DATA.get(), data);
            // already-active clients restart against the new seed immediately;
            // newly-in-range players activate through the scan below
            broadcast(level, data, false);
        }
        Runtime rt = runtime(level);
        // re-seed the continuous center from the (just re-pinned) anchor —
        // both the create and update paths land here
        rt.chaseX = data.anchor.getX() + 0.5;
        rt.chaseZ = data.anchor.getZ() + 0.5;
        rt.chaseVX = 0.0;
        rt.chaseVZ = 0.0;
        rt.centerTimer = CENTER_INTERVAL_TICKS; // broadcast on the first tick
        rt.scanCounter = SCAN_INTERVAL_TICKS; // force an immediate scan
        scan(level, rt, data);
    }

    /** Ends the storm: clients disperse and forget; persisted state clears. */
    public static void stopStorm(ServerLevel level) {
        AllayStormData data = level.getData(CMIAttachments.STORM_DATA.get());
        if (data.active || !data.dead.isEmpty() || !data.hp.isEmpty()) {
            data.active = false;
            data.dead.clear();
            data.hp.clear();
            level.setData(CMIAttachments.STORM_DATA.get(), data);
        }
        Runtime rt = runtime(level);
        rt.waves.clear();
        rt.waveByTarget.clear();
        for (var entry : rt.tracks.entrySet()) {
            Track t = entry.getValue();
            if (!t.active)
                continue;
            t.active = false;
            t.authority = false;
            ServerPlayer player = rt.level.getServer().getPlayerList().getPlayer(entry.getKey());
            if (player != null)
                PacketDistributor.sendToPlayer(player, ClientboundStormStatePacket.STOP());
        }
    }

    private static void broadcast(ServerLevel level, AllayStormData data, boolean updateOnly) {
        Runtime rt = runtime(level);
        for (var entry : rt.tracks.entrySet()) {
            Track t = entry.getValue();
            if (!t.active)
                continue;
            ServerPlayer player = rt.level.getServer().getPlayerList().getPlayer(entry.getKey());
            if (player == null)
                continue;
            if (updateOnly) {
                PacketDistributor.sendToPlayer(player, ClientboundStormStatePacket.UPDATE(
                        data.anchor, data.count, data.stormSeed,
                        t.authority, ServerConfig.stormCorrectionHz,
                        data.creationRadius, data.finalRadius, (float) data.growthPerSecond,
                        data.createdAtGameTime));
            } else {
                PacketDistributor.sendToPlayer(player, ClientboundStormStatePacket.ACTIVATE(
                        data.anchor, data.count, data.stormSeed,
                        t.authority, ServerConfig.stormCorrectionHz, data.dead.toByteArray(),
                        data.creationRadius, data.finalRadius, (float) data.growthPerSecond,
                        data.createdAtGameTime));
            }
        }
    }

    private static void copyInto(AllayStormData src, AllayStormData dst) {
        dst.active = src.active;
        dst.anchor = src.anchor;
        dst.count = src.count;
        dst.stormSeed = src.stormSeed;
        dst.creationRadius = src.creationRadius;
        dst.finalRadius = src.finalRadius;
        dst.finalCount = src.finalCount;
        dst.growthPerSecond = src.growthPerSecond;
        dst.createdAtGameTime = src.createdAtGameTime;
        dst.dead.clear();
        dst.hp.clear();
    }

    private static Runtime runtime(ServerLevel level) {
        return RUNTIMES.computeIfAbsent(level.dimension(), k -> new Runtime(level));
    }
}
