package com.iridium126.createmanaindustry.client.particles.allaystorm;

import java.util.BitSet;

import com.iridium126.createmanaindustry.client.particles.engine.CMIParticleEngine;
import com.iridium126.createmanaindustry.client.particles.engine.ParticleBuffers;
import com.iridium126.createmanaindustry.content.allaystorm.AllayStormManager;
import com.iridium126.createmanaindustry.network.ClientboundStormStatePacket;
import com.iridium126.createmanaindustry.network.ServerboundStormPositionsPacket;

import net.minecraft.client.Minecraft;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.network.PacketDistributor;

/**
 * Allay Storm RUNTIME: every mutable storm state and behavior that used to
 * live on {@link CMIParticleEngine}, extracted so the engine keeps only the
 * frame skeleton (dispatch ordering, pool bookkeeping, abort discipline) while
 * the storm owns its own state machine — the completion of the
 * {@link AllayStormSpec} extraction ("the engine file keeps only the storm's
 * RUNTIME state"). Sits alongside the other two storm pillars:
 * {@link AllayStormSpec} (what a member IS — static spec + header packing) and
 * {@link AllayStormClientHandler} (how packets ENTER — thin unwrapping).
 * <p>
 * Contract:
 * <ul>
 *   <li><b>Render thread only</b>, exactly like the engine fields it
 *       replaced: payload handlers enqueueWork to the main thread, GPU reads
 *       and writes happen inside the engine's compute phase. NO
 *       synchronization is added here — the storm is single-threaded by
 *       design, and a lock would be a real regression.</li>
 *   <li><b>Back-reference discipline</b>: the constructor stores the engine
 *       reference WITHOUT dereferencing it (engine fields initialize after
 *       this one); every engine touch goes through the engine's
 *       internal accessors at call time.</li>
 *   <li><b>Loop discipline</b> (the zero-regression contract from the
 *       extraction review): per-element loops iterate over THIS class's own
 *       fields or references captured before the loop ({@link EmitSchedule},
 *       {@link #playerScratch}); no engine accessor may be called inside a
 *       per-element loop.</li>
 * </ul>
 * The engine's public storm API ({@code applyStormState / applyCorrections /
 * applyStormDamage / timeSec}) forwards here one-line, so
 * {@link AllayStormClientHandler} and the shader-pack merge hook are untouched.
 * The shared simulation clock and the crosshair hit snapshot live here too —
 * the MODEL material exists to serve the storm (non-storm allay MODEL
 * particles are the early test harness), so the allay pose clock and the
 * melee hit-query state are storm assets by classification.
 */
public final class AllayStormRuntime {

    private final CMIParticleEngine engine;

    /** Stores the back-reference ONLY — see the contract javadoc above. */
    public AllayStormRuntime(CMIParticleEngine engine) {
        this.engine = engine;
    }

    /**
     * Per-frame emit scheduling context handed to
     * {@link #scheduleSpawnRuns}: the engine's command arrays by reference
     * plus the running entry/spawn counters (which are runCompute LOCALS —
     * carrying them here lets the storm's scheduling loop write through the
     * same shape the engine's burst/stream loops use). Constructed fresh each
     * frame before the storm scheduling call; the engine copies the counters
     * back and every later writer (bursts/streams/free-pool rescale) keeps
     * touching its own arrays and locals unchanged.
     */
    public static final class EmitSchedule {
        public final int[] emitIds;
        public final int[] emitCounts;
        public final Vec3[] emitOrigins;
        public final boolean[] emitTranslucent;
        public final float[] emitOriginRef;
        public final float[] emitLight;
        public final int[] emitMemberBase;
        public final int[] emitMemberKey;
        public int entryCount;
        public int totalSpawn;

        public EmitSchedule(int[] emitIds, int[] emitCounts, Vec3[] emitOrigins, boolean[] emitTranslucent,
                float[] emitOriginRef, float[] emitLight, int[] emitMemberBase, int[] emitMemberKey) {
            this.emitIds = emitIds;
            this.emitCounts = emitCounts;
            this.emitOrigins = emitOrigins;
            this.emitTranslucent = emitTranslucent;
            this.emitOriginRef = emitOriginRef;
            this.emitLight = emitLight;
            this.emitMemberBase = emitMemberBase;
            this.emitMemberKey = emitMemberKey;
            this.entryCount = 0;
            this.totalSpawn = 0;
        }
    }

    // ---- shared simulation clock --------------------------------------------
    /**
     * Shared simulation clock: {@code (gameTime mod 2^21) / 20} seconds. All
     * clients derive the SAME value (integer modulo, no accumulation), so the
     * wander centres, vortex phases and correction-slot timestamps agree
     * without any clock sync. Float precision at the 2^21-tick wrap horizon
     * (~29 h of continuous uptime) stays at ~8 ms; the wrap itself perturbs
     * the orbit once per period — accepted (boss fights never span it).
     * Consumed beyond the storm proper by the allay pose paths (model render
     * uniform, shader-pack merge hook) — hence "shared".
     */
    private float timeSec = 0f;

    /** Advances the shared clock from the level's game time (render thread). */
    public void tickClock(long gameTime) {
        this.timeSec = (gameTime & ((1 << 21) - 1)) / 20.0f;
        refreshInterpolatedCenter();
    }

    /**
     * Re-evaluates the interpolated center for this frame: render time sits
     * one interval behind the newest snapshot; before the first center packet
     * (and after deactivation) it falls back to the raw packet anchor.
     */
    private void refreshInterpolatedCenter() {
        if (!this.centerValid) {
            this.center = this.stormAnchor;
            return;
        }
        float renderT = this.timeSec - CENTER_LAG_SECONDS;
        float span = this.centerCurrT - this.centerPrevT;
        float alpha = span <= 0.0f ? 1.0f : (renderT - this.centerPrevT) / span;
        alpha = Math.max(0.0f, Math.min(1.0f, alpha));
        this.center = new Vec3(
                this.centerPrevX + (this.centerCurrX - this.centerPrevX) * alpha,
                this.centerPrevY + (this.centerCurrY - this.centerPrevY) * alpha,
                this.centerPrevZ + (this.centerCurrZ - this.centerPrevZ) * alpha);
    }

    /**
     * Applies one {@code ClientboundStormCenterPacket}: the previous current
     * snapshot rolls into prev, the packet becomes current. The FIRST packet
     * initializes both snapshots (no lerp from the stale block anchor).
     * Timestamps share the simulation clock's seconds domain, exactly like
     * the correction slots. {@code velX}/{@code velZ} ride the wire for
     * diagnostics / a future extrapolation path — the clamped lerp ignores
     * them (a late packet freezes the center for under a second, invisible
     * at 2 b/s).
     */
    public void applyStormCenter(float x, float y, float z, float velX, float velZ, long gameTime) {
        if (!this.stormActive)
            return;
        float t = clockSeconds(gameTime);
        if (!this.centerValid) {
            this.centerPrevX = x;
            this.centerPrevY = y;
            this.centerPrevZ = z;
            this.centerPrevT = t;
        } else if (this.centerCurrT != t) {
            this.centerPrevX = this.centerCurrX;
            this.centerPrevY = this.centerCurrY;
            this.centerPrevZ = this.centerCurrZ;
            this.centerPrevT = this.centerCurrT;
        }
        this.centerCurrX = x;
        this.centerCurrY = y;
        this.centerCurrZ = z;
        this.centerCurrT = t;
        this.centerValid = true;
    }

    /** Shared simulation clock in seconds (storm wander/burst + pose paths). */
    public float timeSec() {
        return this.timeSec;
    }

    /** Shared simulation clock from a game-time tick value (see {@link #timeSec}). */
    private static float clockSeconds(long gameTime) {
        return (gameTime & ((1 << 21) - 1)) / 20.0f;
    }

    // ---- storm sync state ----------------------------------------------------

    private boolean stormActive = false;
    private int stormEmitId = -1;
    private Vec3 stormAnchor = Vec3.ZERO;

    // ---- continuous chased center (double-snapshot interpolation) -----------
    /**
     * The chased center arrives as {@code ClientboundStormCenterPacket}
     * snapshots every {@code AllayStormManager.CENTER_INTERVAL_TICKS} (20
     * ticks = 1 s). Every frame renders ONE INTERVAL BEHIND the newest
     * snapshot, lerping the last two — the standard entity-sync scheme, and a
     * deterministic function of the packet stream: every client derives the
     * SAME center, so the servo targets stay mutually consistent while the
     * motion is continuous at frame rate. Each client sits ~2 blocks (one
     * interval at the 2 b/s chase speed) behind the server's truth; the hit
     * reach check's +8 margin absorbs that, and every anchor-relative wire
     * coordinate (hit reports, corrections, snapshots) is produced AND
     * consumed against this same interpolated center.
     * <p>
     * A packet that never arrives clamps alpha at 1 — the center freezes for
     * under a second instead of teleporting; the wire's velocity fields are
     * carried for diagnostics and any future extrapolation path.
     */
    private static final float CENTER_LAG_SECONDS = AllayStormManager.CENTER_INTERVAL_TICKS / 20.0f;
    private float centerPrevX;
    private float centerPrevY;
    private float centerPrevZ;
    private float centerCurrX;
    private float centerCurrY;
    private float centerCurrZ;
    private float centerPrevT;
    private float centerCurrT;
    private boolean centerValid = false;
    /** Interpolated center, refreshed every {@link #tickClock}; all consumers read this. */
    private Vec3 center = Vec3.ZERO;
    private double stormRadius = 8.0;
    /** SIGNED vortex angular velocity rad/s (0 in ball mode / when idle). */
    private float stormOmega = 0f;
    private int pendingStormSpawns = 0;
    private boolean stormHeaderWritten = false;
    /** Emitter id force-expired on the NEXT update dispatch (-1 = none). */
    private int stormKillEmitId = -1;
    /** Server-owned 24-bit storm instance seed (member identity derivation). */
    private int stormSeed = 0;
    /** Total member population of the current storm (alive + dead). */
    private int stormCount = 0;
    /** Server-decided dead members (this client's mirror of the truth). */
    private final BitSet stormDead = new BitSet();
    /** True while THIS client is the authority (runs the position readback). */
    private boolean stormAuthority = false;
    private double stormCorrectionHz = 5.0;
    /** Next member index to schedule for spawning (skips dead members). */
    private int stormSpawnCursor = 0;
    /** gameTime (ticks) of the last dispatched position snapshot. */
    private long lastSnapshotGameTime = Long.MIN_VALUE;
    /** Set when the compute phase dispatched a readback; consumed at fence poll. */
    private boolean stormPosPending = false;
    private long stormPosGameTime = 0;

    // ---- crosshair hit-query snapshot (storm asset; see the class javadoc) ---
    /**
     * Newest fence-signalled hit-query snapshot: the packed sort key of the
     * allay under the crosshair ({@link ParticleBuffers#HIT_MISS} = none) and
     * its HP as float bits. At most one frame old -- a click consumes it with
     * zero stalls; a stale camera for one frame is accepted (documented).
     */
    private int hitKeySnapshot = ParticleBuffers.HIT_MISS;
    private int hitHpBits = 0;
    /**
     * Non-consuming mirror of {@link #hitKeySnapshot} for the crosshair pick
     * ({code CMIParticleEngine.injectCrosshairPick}): the click path
     * consume-once resets {@code hitKeySnapshot}, and the crosshair must not
     * flicker for the frame between a click and the next fence poll. Refreshed
     * at the same fence poll, cleared wherever the click snapshot is
     * invalidated.
     */
    private int crosshairHitKey = ParticleBuffers.HIT_MISS;
    /** Storm member identity of the newest hit-query snapshot (-1 = non-storm). */
    private int hitMemberIdx = -1;

    // ---- synced player collection (storm repulsion + readback near-test) -----

    /** Scratch for per-frame synced player positions (xyz per player). */
    private final float[] playerScratch = new float[ParticleBuffers.MAX_STORM_PLAYERS * 3];
    private final double[] playerDistScratch = new double[ParticleBuffers.MAX_STORM_PLAYERS];
    /** Player count uploaded with the last collectSyncedPlayers (pass handoff). */
    private int lastPlayerCount = 0;

    // ---- public protocol API (engine delegates; handler is the caller) -------

    /**
     * Applies a server storm-state packet (ACTIVATE / UPDATE / DEACTIVATE /
     * STOP). Called on the render thread (payload handlers enqueueWork there),
     * which is the thread all storm state lives on — direct mutation is safe.
     * <p>
     * ACTIVATE (also join / dimension change / re-enter range): full restart
     * against the server definition — existing members are expired via the
     * kill path, the dead bitmap replaces the local mirror, and exactly the
     * alive members trickle in with seed-derived identity, so member counts
     * agree across clients and restarts by construction. UPDATE: parameter
     * change only — identity, HP and deaths are preserved (springs retarget);
     * also carries the authority flag and correction rate. DEACTIVATE
     * (out of range) and STOP (storm over): local dispersal; the members
     * compact away on the next update dispatch.
     */
    public void applyStormState(int action, boolean authority, double correctionHz,
            Vec3 anchor, int count, float radius, int mode, float omega, int seed, byte[] deadBitmap) {
        switch (action) {
            case ClientboundStormStatePacket.ACTION_ACTIVATE -> {
                // full restart: expire whatever storm generation is live
                if (this.stormActive && this.stormEmitId >= 0)
                    this.stormKillEmitId = this.stormEmitId;
                // identities may be re-derived against a new seed: stale
                // identity -> slot entries must never serve member-keyed
                // combat origins
                if (engine.initialized())
                    engine.gpu().clearMemberMap();
                this.stormDead.clear();
                if (deadBitmap != null) {
                    byte[] bm = deadBitmap;
                    for (int i = 0; i < bm.length; i++) {
                        int b = bm[i] & 0xFF;
                        for (int bit = 0; bit < 8; bit++)
                            if ((b & (1 << bit)) != 0)
                                this.stormDead.set(i * 8 + bit);
                    }
                }
                this.stormActive = true;
                this.stormAnchor = anchor;
                // the immediate center packet on the server's activation path
                // seeds the interpolation; until then consumers read the raw
                // (block-quantized) anchor
                this.centerValid = false;
                this.center = anchor;
                this.stormRadius = radius;
                this.stormOmega = clampVortexOmega(omega, radius); // signed; 0 = ball mode
                this.stormSeed = seed & AllayStormSpec.SEED_MASK_CLIENT;
                this.stormCount = count;
                this.stormAuthority = authority;
                this.stormCorrectionHz = correctionHz;
                this.stormSpawnCursor = 0;
                this.pendingStormSpawns = Math.max(0, count - this.stormDead.cardinality());
                this.stormHeaderWritten = false;
                dropHitKeys(); // identities reshuffle
            }
            case ClientboundStormStatePacket.ACTION_UPDATE -> {
                if (!this.stormActive)
                    return;
                this.stormAnchor = anchor;
                this.stormRadius = radius;
                this.stormOmega = clampVortexOmega(omega, radius);
                this.stormAuthority = authority;
                this.stormCorrectionHz = correctionHz;
                if (this.stormHeaderWritten && this.stormEmitId >= 0) {
                    // re-pack header so G(t)/springs retarget on the next dispatch
                    float[] h = AllayStormSpec.packedHeader(this.stormOmega, this.stormRadius, this.center);
                    h[16 * 4 + 0] = engine.ensurePoofEmitter();
                    engine.gpu().setEmitterHeader(this.stormEmitId, h);
                }
            }
            case ClientboundStormStatePacket.ACTION_DEACTIVATE, ClientboundStormStatePacket.ACTION_STOP -> {
                // local dispersal; server state is not this side's business
                if (this.stormActive && this.stormEmitId >= 0)
                    this.stormKillEmitId = this.stormEmitId;
                if (engine.initialized())
                    engine.gpu().clearMemberMap();
                this.stormActive = false;
                this.stormAuthority = false;
                this.pendingStormSpawns = 0;
                this.stormHeaderWritten = false;
                this.stormDead.clear();
                this.centerValid = false;
                dropHitKeys();
            }
            default -> {
            }
        }
    }

    /**
     * Applies one relayed position snapshot ({@code entries} stride
     * {@code 8: memberIdx, pad, posRelToAnchor.xyz, vel.xyz}) by writing soft
     * correction slots — positions are never written directly (no teleports);
     * the update pass eases members toward the extrapolated targets.
     */
    public void applyCorrections(float[] entries, long gameTime) {
        if (!this.stormActive || entries == null || entries.length < 8)
            return;
        float arrival = clockSeconds(gameTime);
        var gpu = engine.gpu(); // captured before the loop (loop discipline)
        int cap = gpu.capacity();
        for (int o = 0; o + 7 < entries.length; o += 8) {
            int memberIdx = (int) entries[o];
            if (memberIdx < 0 || memberIdx >= cap)
                continue; // no correction slot exists for this identity
            float mx = (float) this.center.x + entries[o + 2];
            float my = (float) this.center.y + entries[o + 3];
            float mz = (float) this.center.z + entries[o + 4];
            gpu.writeCorrection(memberIdx, mx, my, mz, arrival,
                    entries[o + 5], entries[o + 6], entries[o + 7], 1.0f);
        }
    }

    /**
     * Applies a hit correction from a server DAMAGE broadcast: every client
     * (authority included) eases the struck member to the attacker's exact
     * hit spot over a short strong window, so hurt-flash / corpse / poof all
     * play at the same place without any periodic snapshot needing to catch up.
     */
    public void applyHitCorrection(int memberIdx, Vec3 relToAnchor, long gameTime) {
        if (!this.stormActive || memberIdx < 0 || memberIdx >= engine.gpu().capacity())
            return;
        float arrival = clockSeconds(gameTime);
        engine.gpu().writeCorrection(memberIdx,
                (float) (this.center.x + relToAnchor.x),
                (float) (this.center.y + relToAnchor.y),
                (float) (this.center.z + relToAnchor.z),
                arrival, 0f, 0f, 0f, 10.0f);
    }

    /**
     * Applies a server DAMAGE broadcast for one storm member: the damage/
     * knockback entry goes through the GPU damage queue (matched by MEMBER
     * identity, immune to pool recompaction; the server's death bit forces
     * the corpse unconditionally) and a strong short hit-correction slot
     * eases the member to the attacker's exact hit spot. Every active client
     * — the attacker included — runs this identical path.
     */
    public void applyStormDamage(int memberIdx, float damage, float kbX, float kbZ,
            float light, boolean died, Vec3 relToAnchor, long gameTime) {
        if (!this.stormActive)
            return;
        engine.enqueueDamageEntry(memberIdx, damage, kbX, kbZ, light,
                ParticleBuffers.DAMAGE_FLAG_MEMBER | (died ? ParticleBuffers.DAMAGE_FLAG_DIED : 0));
        applyHitCorrection(memberIdx, relToAnchor, gameTime);
    }

    /** Full storm teardown (level clear/shutdown): forget the emitter id too. */
    public void resetStormState() {
        this.stormActive = false;
        this.pendingStormSpawns = 0;
        this.stormHeaderWritten = false;
        this.stormEmitId = -1;
        this.stormKillEmitId = -1;
        this.stormOmega = 0f;
        this.stormDead.clear();
        this.stormAuthority = false;
        this.stormSpawnCursor = 0;
        this.stormCount = 0;
        this.stormPosPending = false;
        this.lastSnapshotGameTime = Long.MIN_VALUE;
        this.centerValid = false;
        this.center = Vec3.ZERO;
    }

    /**
     * Effective vortex angular velocity. Co-rotation needs the tangential
     * speed {@code omega * homeR}, and member speed is hard-capped at
     * {@link AllayStormSpec#MAX_SPEED}, so any |omega| beyond
     * {@code MAX_SPEED / radius} would make the update.comp servo targets
     * permanently unreachable — the members would chase a pattern they can
     * never catch. Clamped by the STORM radius (an upper bound of every
     * member's homeR), so the whole pattern stays rigidly co-rotating, and
     * deterministically on every client: both operands are synced params.
     * The sign survives (zero still selects ball mode).
     */
    private static float clampVortexOmega(float omega, double radius) {
        if (omega == 0f)
            return 0f;
        float mag = (float) Math.min(Math.abs(omega), AllayStormSpec.MAX_SPEED / Math.max(radius, 2.0));
        return Math.signum(omega) * mag;
    }

    // ---- runCompute skeleton hooks (single reads, never inside loops) --------

    /** True while a storm generation is live (grid pass + spawn scheduling). */
    public boolean active() {
        return this.stormActive;
    }

    /** True while a storm emitter kill is queued for the next update dispatch. */
    public boolean killPending() {
        return this.stormKillEmitId >= 0;
    }

    /**
     * The grid pass must run when storm steering can execute: an active storm,
     * or a kill request still standing (whose targets are still in the read
     * pool until the next update compacts them away).
     */
    public boolean needsGridPass() {
        return this.stormActive || this.stormKillEmitId >= 0;
    }

    /** {@code uKillEmit} uniform value: the emitter id to expire, -1 = none (~0u). */
    public int killEmitId() {
        return this.stormKillEmitId;
    }

    /** SIGNED vortex angular velocity for the {@code uStormOmega} uniforms. */
    public float omega() {
        return this.stormOmega;
    }

    /** 24-bit instance seed for the {@code uStormSeed} emit uniform. */
    public float seed() {
        return this.stormSeed;
    }

    /**
     * Retires the queued kill at the update phase's SUCCESS TAIL (after the
     * pool swap): the just-swapped pool is now the authoritative read source,
     * so every kill target provably compacted away. Until then the pending id
     * keeps the grid pass armed and refires the kill.
     */
    public void retireKill() {
        this.stormKillEmitId = -1;
    }

    // ---- crosshair hit-query snapshot plumbing -------------------------------

    /**
     * Stores the fence-fresh hit-query readback (engine's
     * {@code pollCounterSnapshot}): packed key, winner HP bits, storm member
     * identity ({@code HIT_MISS} = non-storm MODEL winner).
     */
    public void onHitReadback(int key, int hpBits, int memberIdx) {
        this.hitKeySnapshot = key;
        this.crosshairHitKey = key;
        this.hitHpBits = hpBits;
        this.hitMemberIdx = memberIdx == ParticleBuffers.HIT_MISS ? -1 : memberIdx;
    }

    /** Full hit-snapshot drop (pool reset): keys + HP + member identity. */
    public void dropHitSnapshots() {
        this.hitKeySnapshot = ParticleBuffers.HIT_MISS;
        this.crosshairHitKey = ParticleBuffers.HIT_MISS;
        this.hitHpBits = 0;
        this.hitMemberIdx = -1;
    }

    /**
     * Keys-only drop (storm kill frame / ACTIVATE / STOP): identities
     * reshuffle, but the HP and member slots are refreshed by the next fence
     * poll regardless.
     */
    public void dropHitKeys() {
        this.hitKeySnapshot = ParticleBuffers.HIT_MISS;
        this.crosshairHitKey = ParticleBuffers.HIT_MISS;
    }

    /** Packed key of the newest hit snapshot (consume-once click path). */
    public int hitKeySnapshot() {
        return this.hitKeySnapshot;
    }

    /**
     * Consume-once click path: clears ONLY {@link #hitKeySnapshot} — the
     * crosshair mirror {@link #crosshairHitKey} stays so the crosshair does
     * not flicker in the frame between a click and the next fence poll.
     */
    public void consumeHitSnapshot() {
        this.hitKeySnapshot = ParticleBuffers.HIT_MISS;
    }

    /** Non-consuming mirror for the crosshair pick injection. */
    public int crosshairHitKey() {
        return this.crosshairHitKey;
    }

    /** Winner HP (float bits decoded) for the hurt/death sound choice. */
    public float hitHp() {
        return Float.intBitsToFloat(this.hitHpBits);
    }

    /** Storm member identity of the newest snapshot (-1 = non-storm winner). */
    public int hitMemberIdx() {
        return this.hitMemberIdx;
    }

    /**
     * Storm center (world space) — the INTERPOLATED chased center. Every
     * anchor-relative wire coordinate is produced and consumed against this
     * reference; the raw packet anchor stays internal (fallback until the
     * first center snapshot lands).
     */
    public Vec3 center() {
        return this.center;
    }

    // ---- synced player collection ---------------------------------------------

    /**
     * Collects the nearest {@link ParticleBuffers#MAX_STORM_PLAYERS} synced
     * players (vanilla entity sync positions) by distance to the storm anchor
     * into {@link #playerScratch}. Every client feeds the SAME player set
     * (others lagged by interpolation), so the repulsion field is
     * near-identical across clients with zero extra packets.
     */
    public int collectSyncedPlayers() {
        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null) {
            this.lastPlayerCount = 0;
            return 0;
        }
        int count = 0;
        double[] dist = this.playerDistScratch;
        for (var p : mc.level.players()) {
            double dx = p.getX() - this.center.x;
            double dy = p.getY() - this.center.y;
            double dz = p.getZ() - this.center.z;
            double d2 = dx * dx + dy * dy + dz * dz;
            int max = ParticleBuffers.MAX_STORM_PLAYERS;
            if (count >= max) {
                // worse than the farthest kept player: skip
                if (d2 >= dist[max - 1])
                    continue;
                count = max - 1; // overwrite the farthest, then re-insert
            }
            int i = count++;
            while (i > 0 && dist[i - 1] > d2) {
                dist[i] = dist[i - 1];
                this.playerScratch[i * 3] = this.playerScratch[(i - 1) * 3];
                this.playerScratch[i * 3 + 1] = this.playerScratch[(i - 1) * 3 + 1];
                this.playerScratch[i * 3 + 2] = this.playerScratch[(i - 1) * 3 + 2];
                i--;
            }
            dist[i] = d2;
            this.playerScratch[i * 3] = (float) p.getX();
            this.playerScratch[i * 3 + 1] = (float) p.getY();
            this.playerScratch[i * 3 + 2] = (float) p.getZ();
        }
        this.lastPlayerCount = count;
        return count;
    }

    /** Player positions (xyz) from the last {@link #collectSyncedPlayers}. */
    public float[] playerScratch() {
        return this.playerScratch;
    }

    // ---- authority position readback -------------------------------------------

    /**
     * 6c. Authority-only: dispatches {@code stormpos.comp} over the freshly
     * written pool at the configured snapshot rate, compacting storm members
     * within melee reach of any synced player into the staging buffer. The
     * readback happens at the next fence poll ({@link #pollPositionSnapshot})
     * and ships as a {@link ServerboundStormPositionsPacket}. Skipped on
     * non-authority clients and while no storm runs; costs the distance tests
     * alone in steady state. {@code upper} is the engine's dispatch bound
     * over its pool census ({@code aliveKnown + spawnDelta + 64}) — pool
     * bookkeeping stays on the engine side of the seam.
     */
    public void dispatchStormPosReadback(int slot, int cap, int upper) {
        if (!this.stormActive || !this.stormAuthority || this.stormCorrectionHz <= 0)
            return;
        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null)
            return;
        long now = mc.level.getGameTime();
        if (this.lastSnapshotGameTime != Long.MIN_VALUE
                && now - this.lastSnapshotGameTime < 20.0 / this.stormCorrectionHz)
            return;
        if (upper <= 0 || this.lastPlayerCount <= 0)
            return;
        this.lastSnapshotGameTime = now;
        int sp = engine.programs().stormPos();
        if (sp == 0)
            return;
        engine.gpu().clearStormPosCount();
        org.lwjgl.opengl.GL20.glUseProgram(sp);
        engine.gpu().bindParticleWrite(ParticleBuffers.PARTICLE_BB_WRITE);
        engine.gpu().bindCounter(3, slot);
        engine.gpu().bindEmitters(5);
        engine.gpu().bindPlayers();
        engine.gpu().bindStormPos();
        Vec3 a = this.center;
        CMIParticleEngine.setFloatUniform(sp, "uAnchor", (float) a.x, (float) a.y, (float) a.z);
        CMIParticleEngine.setIntUniform(sp, "uPlayerCount", this.lastPlayerCount);
        org.lwjgl.opengl.GL43.glDispatchCompute(Math.max(1, (upper + 63) / 64), 1, 1);
        org.lwjgl.opengl.GL42.glMemoryBarrier(
                org.lwjgl.opengl.GL43.GL_SHADER_STORAGE_BARRIER_BIT | org.lwjgl.opengl.GL42.GL_ATOMIC_COUNTER_BARRIER_BIT);
        this.stormPosPending = true;
        this.stormPosGameTime = now;
    }

    /**
     * Consumes a pending storm position readback at the fence poll (success
     * path): reads the staging buffer and ships it to the server.
     */
    public void pollPositionSnapshot() {
        if (!this.stormPosPending)
            return;
        this.stormPosPending = false;
        float[] entries = engine.gpu().readbackStormPos();
        if (entries != null && entries.length >= 8)
            PacketDistributor.sendToServer(
                    new ServerboundStormPositionsPacket(this.stormPosGameTime, entries));
    }

    /** Drops a pending readback (fence WAIT_FAILED; a fresh one is scheduled anyway). */
    public void dropPositionSnapshot() {
        this.stormPosPending = false;
    }

    // ---- emit scheduling ---------------------------------------------------------

    /**
     * Storm trickle-in: at most ~60 frames of member batches regardless of
     * population (the swarm assembles over ~1s instead of popping in), SPLIT
     * around the server-reported dead members so each command's member index
     * base + inner (GPU-side) reproduces the server's member numbering
     * exactly — identity stays cross-client. Runs every active frame, not
     * just while trickle-spawning: the first members of frame one draw in
     * this very tick (so the allay atlas must be live BEFORE emission), and a
     * stray burst evicting a storm volume via LRU must heal instead of
     * silently stripping REST collision. The swarm pins a dedicated 2x2 bake
     * grid (+-48 block footprint); routing it through ensureEmitterRuntime
     * would squat an extra off-grid centred slot the swarm never needs.
     */
    public void scheduleSpawnRuns(EmitSchedule s) {
        if (this.stormActive) {
            engine.allayAtlas().ensureLoaded();
            engine.collisionBake().ensureQuadrants(this.center);
        }
        if (this.stormActive && this.pendingStormSpawns > 0) {
            int id = engine.ensureEmitter(AllayStormSpec.SPEC);
            if (id >= 0) {
                if (!this.stormHeaderWritten) {
                    // Anchor + storm parameters ride the per-id header slots 18/19
                    // (specs stay position-free; this spec dedupes to its own id).
                    // AllayStormSpec owns the slot layout; only the death-chain
                    // emitter id is patched here (MODEL particles poof on expiry).
                    // refreshCenterHeader rewrites the center every frame after
                    // this, so the initial value is just the seed.
                    float[] h = AllayStormSpec.packedHeader(this.stormOmega, this.stormRadius, this.center);
                    h[16 * 4 + 0] = engine.ensurePoofEmitter();
                    engine.gpu().setEmitterHeader(id, h); // uploaded below this frame
                    this.stormEmitId = id;
                    this.stormHeaderWritten = true;
                }
                // Drain in ~60 frames regardless of population (see javadoc).
                // The storm spec is FIXED (always MODEL) — resolve its
                // translucency once, outside the scheduling loop.
                boolean translucent = engine.isTranslucent(AllayStormSpec.SPEC);
                int budget = Math.min(this.pendingStormSpawns,
                        Math.max(ParticleBuffers.MAX_EMIT_COMMANDS,
                                (this.pendingStormSpawns + 59) / 60));
                int scheduled = 0;
                while (scheduled < budget && s.entryCount < ParticleBuffers.MAX_EMIT_COMMANDS
                        && this.stormSpawnCursor < this.stormCount) {
                    while (this.stormSpawnCursor < this.stormCount && this.stormDead.get(this.stormSpawnCursor))
                        this.stormSpawnCursor++;
                    if (this.stormSpawnCursor >= this.stormCount)
                        break;
                    int runStart = this.stormSpawnCursor;
                    while (this.stormSpawnCursor < this.stormCount && !this.stormDead.get(this.stormSpawnCursor)
                            && scheduled + (this.stormSpawnCursor - runStart) < budget)
                        this.stormSpawnCursor++;
                    int runLen = this.stormSpawnCursor - runStart;
                    if (runLen <= 0)
                        break;
                    s.emitIds[s.entryCount] = id;
                    s.emitCounts[s.entryCount] = runLen;
                    s.emitOrigins[s.entryCount] = this.center;
                    s.emitTranslucent[s.entryCount] = translucent;
                    s.emitOriginRef[s.entryCount] = 0f;
                    s.emitLight[s.entryCount] = 0f;
                    s.emitMemberBase[s.entryCount] = runStart;
                    s.emitMemberKey[s.entryCount] = 0; // c.z unused by the storm spawn style
                    s.totalSpawn += runLen;
                    scheduled += runLen;
                    s.entryCount++;
                }
                if (this.stormSpawnCursor >= this.stormCount)
                    this.pendingStormSpawns = 0;
                else
                    this.pendingStormSpawns -= scheduled;
            }
        }
    }

    /**
     * Per-frame (compute phase, before the emitter upload): re-packs the storm
     * emitter header with the INTERPOLATED center so the servo targets ride
     * the continuous chase — snapshots land at 1 Hz, this renders the motion
     * at frame rate. Marks the header dirty for this frame's upload (~1.25 KB,
     * noise). Cheap no-op on every non-storm frame.
     */
    public void refreshCenterHeader() {
        if (!this.stormActive || !this.stormHeaderWritten || this.stormEmitId < 0)
            return;
        float[] h = AllayStormSpec.packedHeader(this.stormOmega, this.stormRadius, this.center);
        h[16 * 4 + 0] = engine.ensurePoofEmitter();
        engine.gpu().setEmitterHeader(this.stormEmitId, h);
    }
}
