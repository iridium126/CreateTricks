package com.iridium126.createmanaindustry.content.allaystorm;

import java.util.ArrayDeque;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

import com.iridium126.createmanaindustry.CMIAttachments;
import com.iridium126.createmanaindustry.CreateManaIndustry;
import com.iridium126.createmanaindustry.config.ServerConfig;
import com.iridium126.createmanaindustry.network.ClientboundStormDamagePacket;
import com.iridium126.createmanaindustry.network.ClientboundStormPositionsPacket;
import com.iridium126.createmanaindustry.network.ClientboundStormStatePacket;
import com.iridium126.createmanaindustry.network.ServerboundStormHitPacket;
import com.iridium126.createmanaindustry.network.ServerboundStormPositionsPacket;

import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.stats.Stats;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.animal.allay.Allay;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
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
 * </ul>
 */
@EventBusSubscriber(modid = CreateManaIndustry.MODID)
public final class AllayStormManager {

    /**
     * Activation radius in blocks: default client fade end (96 + 24 ramp =
     * 120) plus the maximum storm extent (radius 64 + wander 12). Deliberately
     * a server constant — the fade distance is a CLIENT config the server
     * cannot read.
     */
    public static final double ACTIVATE_RANGE = 196.0;
    /** Hysteresis margin past {@link #ACTIVATE_RANGE} before deactivating. */
    public static final double HYSTERESIS = 64.0;

    private static final int SCAN_INTERVAL_TICKS = 20;
    private static final int MAX_HITS_PER_SECOND = 12;
    /** Coarse sanity bound for relayed positions (blocks from the anchor). */
    private static final float RELAY_BOUND = 320.0f;
    /** Coarse sanity bound for relayed velocities (b/s — the wire's byte envelope). */
    private static final float RELAY_VEL_BOUND = 8.0f;

    // ---- per-level runtime ---------------------------------------------------

    private static final Map<ResourceKey<Level>, Runtime> RUNTIMES = new HashMap<>();

    private static final class Runtime {
        final ServerLevel level;
        long orderCounter;
        int scanCounter = SCAN_INTERVAL_TICKS; // force a scan on the first tick
        final Map<UUID, Track> tracks = new HashMap<>();

        Runtime(ServerLevel level) {
            this.level = level;
        }
    }

    private static final class Track {
        boolean active;
        boolean authority;
        long order;
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
        if (++rt.scanCounter >= SCAN_INTERVAL_TICKS) {
            rt.scanCounter = 0;
            scan(level, rt, data);
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
        PacketDistributor.sendToPlayer(player, ClientboundStormStatePacket.ACTIVATE(
                data.anchor, data.count, data.radius, data.mode, data.omega, data.stormSeed,
                t.authority, ServerConfig.stormCorrectionHz, data.dead.toByteArray()));
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
                            data.anchor, data.count, data.radius, data.mode, data.omega, data.stormSeed,
                            should, ServerConfig.stormCorrectionHz));
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
        // proxy mirrors the client's proxyFor: vanilla implementations never
        // read the target, but modded items may.
        ItemStack weapon = player.getWeaponItem();
        ItemStack weaponCopy = weapon.copy();
        Allay proxyTarget = new Allay(EntityType.ALLAY, level);
        boolean hurtEnemyResult = weapon.hurtEnemy(proxyTarget, player);
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
                packet.kbX(), packet.kbZ(), packet.light(), died,
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

    // ---- command surface -------------------------------------------------------

    /**
     * Creates or updates the storm. A parameter-only change keeps member
     * identity, HP and deaths (clients retarget their springs); any count
     * change is a full restart with a fresh seed (test-phase semantics — the
     * identity space reshapes).
     */
    public static void setStorm(ServerLevel level, BlockPos anchor, int count, double radius, int mode, double omega) {
        AllayStormData data = level.getData(CMIAttachments.STORM_DATA.get());
        boolean samePopulation = data.active && data.count == Math.max(1, Math.min(AllayStormData.MAX_COUNT, count));
        if (samePopulation) {
            data.anchor = anchor.immutable();
            data.radius = AllayStormData.quantizeRadius(radius);
            data.mode = mode == 2 ? 2 : 1;
            float mag = AllayStormData.quantizeOmega(omega);
            data.omega = data.mode == 2 ? (((data.stormSeed & 1) == 0) ? mag : -mag) : 0f;
            level.setData(CMIAttachments.STORM_DATA.get(), data);
            broadcast(level, data, true);
        } else {
            AllayStormData fresh = AllayStormData.create(anchor, count, radius, mode, omega,
                    level.random.nextInt(1 << 24));
            copyInto(fresh, data);
            level.setData(CMIAttachments.STORM_DATA.get(), data);
            // already-active clients restart against the new seed immediately;
            // newly-in-range players activate through the scan below
            broadcast(level, data, false);
        }
        Runtime rt = runtime(level);
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
                        data.anchor, data.count, data.radius, data.mode, data.omega, data.stormSeed,
                        t.authority, ServerConfig.stormCorrectionHz));
            } else {
                PacketDistributor.sendToPlayer(player, ClientboundStormStatePacket.ACTIVATE(
                        data.anchor, data.count, data.radius, data.mode, data.omega, data.stormSeed,
                        t.authority, ServerConfig.stormCorrectionHz, data.dead.toByteArray()));
            }
        }
    }

    private static void copyInto(AllayStormData src, AllayStormData dst) {
        dst.active = src.active;
        dst.anchor = src.anchor;
        dst.count = src.count;
        dst.radius = src.radius;
        dst.mode = src.mode;
        dst.omega = src.omega;
        dst.stormSeed = src.stormSeed;
        dst.dead.clear();
        dst.hp.clear();
    }

    private static Runtime runtime(ServerLevel level) {
        return RUNTIMES.computeIfAbsent(level.dimension(), k -> new Runtime(level));
    }
}
