package com.iridium126.createmanaindustry.dimension.lod;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

import it.unimi.dsi.fastutil.longs.Long2IntOpenHashMap;
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.block.state.BlockState;

import com.iridium126.createmanaindustry.CreateManaIndustry;
import com.iridium126.createmanaindustry.config.ServerConfig;
import com.iridium126.createmanaindustry.dimension.cube.AllvrCubeMap;
import com.iridium126.createmanaindustry.dimension.cube.AllvrCubePos;
import com.iridium126.createmanaindustry.dimension.gen.AllvrIslandFieldGenerator;
import com.iridium126.createmanaindustry.dimension.mesh.AllvrMesher;
import com.iridium126.createmanaindustry.dimension.net.ClientboundAllvrLodBitmapPacket;
import com.iridium126.createmanaindustry.dimension.net.ClientboundAllvrLodForgetPacket;
import com.iridium126.createmanaindustry.dimension.net.ClientboundAllvrLodMeshPacket;

/**
 * Server-side LOD pipeline for one allay-dimension {@link ServerLevel} (doc
 * §13 4c-1, grilling 2026-09-06): per-player surface-node bitmaps (S→C), a
 * C2S mesh-request channel answered from a shared LRU cache or a parallel
 * build pool, and the player-edit invalidation loop.
 * <p>
 * Thread discipline: everything on the server main thread except the build
 * pool, which receives immutable jobs (generator math is pure; world reads
 * happen only in the main-thread overlay capture). The main-thread slice is
 * budgeted ({@link #PREP_BUDGET_NANOS}, grilling Q5: &lt;0.5ms — queue
 * dispatch plus edited-cube overlay capture; a single oversized capture may
 * overrun, the budget only paces how many jobs dequeue per tick).
 * <p>
 * Throughput revision over the 4a text (grilling Q5): build pool (default 2
 * threads, config) instead of a 2ms/tick server-thread budget, and request
 * pacing 64/tick in-flight 256 instead of 8/32 — R=2048 holds ~50k surface
 * nodes, which the original figures would fill in tens of minutes.
 * <p>
 * Invalidation (grilling Q4, in 4c-1): a player block edit bumps the
 * containing node's generation per level (≤4 nodes), drops the cache entry
 * and queues a forget broadcast (flushed deduped per tick — a /fill must not
 * emit 16k packets). Stale build results (generation moved while building)
 * are dropped and answered with a forget so the client re-requests fresh.
 */
public final class AllvrLodMap {

    /** Shared mesh cache budget (4a grilling decision, kept). */
    private static final long CACHE_BUDGET_BYTES = 256L << 20;
    /** Mesh requests dequeued per tick (grilling Q5 revision). */
    private static final int REQUESTS_PER_TICK = 64;
    /** Per-level in-flight request cap. */
    private static final int MAX_INFLIGHT = 256;
    /** Main-thread prep slice budget (grilling Q5). */
    private static final long PREP_BUDGET_NANOS = 500_000L;
    /** Cache distance-eviction cadence (ticks). */
    private static final int EVICT_SCAN_TICKS = 40;

    private final ServerLevel level;
    private final AllvrCubeMap cubeMap;
    private final AllvrIslandFieldGenerator generator;
    private final AllvrLodField field;
    private final ExecutorService pool;

    private static final class Sub {
        final AllvrCubePos[] lastCenter = new AllvrCubePos[4];
    }

    private final Map<UUID, Sub> subs = new HashMap<>();

    private static final class Request {
        final long gen;
        final List<UUID> requesters = new ArrayList<>(1);

        Request(long gen) {
            this.gen = gen;
        }
    }

    private final Long2ObjectOpenHashMap<Request>[] requests = new Long2ObjectOpenHashMap[4];
    /** Per-level mesh cache; access-order LRU under {@link #cacheBytes}. */
    @SuppressWarnings("unchecked")
    private final LinkedHashMap<Long, long[]>[] cache = new LinkedHashMap[4];
    /** Per-node generation, bumped on every edit inside the node. */
    private final Long2IntOpenHashMap[] gens = new Long2IntOpenHashMap[4];
    /** Edits awaiting their forget broadcast (flushed deduped per tick). */
    private final LongOpenHashSet[] dirtyNodes = new LongOpenHashSet[4];

    private record PendingJob(int level, long cellLong) {}

    private record BuiltMesh(int level, long cellLong, long gen, long[] quads, List<UUID> requesters) {}

    private final ArrayDeque<PendingJob> prepQueue = new ArrayDeque<>();
    private final ConcurrentLinkedQueue<BuiltMesh> results = new ConcurrentLinkedQueue<>();
    private long cacheBytes;
    private int evictScanTicks;

    @SuppressWarnings("unchecked")
    public AllvrLodMap(ServerLevel level, AllvrCubeMap cubeMap) {
        this.level = level;
        this.cubeMap = cubeMap;
        this.generator = new AllvrIslandFieldGenerator(level.getSeed());
        this.field = new AllvrLodField(this.generator);
        for (int i = 0; i < 4; i++) {
            this.requests[i] = new Long2ObjectOpenHashMap<>();
            this.gens[i] = new Long2IntOpenHashMap();
            this.gens[i].defaultReturnValue(0);
            this.dirtyNodes[i] = new LongOpenHashSet();
            // access-order: cache hits refresh recency, so the budget eviction
            // drops genuinely cold nodes, not the most recently served ones
            this.cache[i] = new LinkedHashMap<>(16, 0.75f, true);
        }
        int threads = Math.max(1, ServerConfig.allvrLodBuildThreads);
        AtomicInteger index = new AtomicInteger();
        this.pool = Executors.newFixedThreadPool(threads, r -> {
            Thread t = new Thread(r, "CMI-AllvrLodBuild-" + index.incrementAndGet());
            t.setDaemon(true);
            return t;
        });
        CreateManaIndustry.LOGGER.info("[Allvr] LOD pipeline up ({} build thread(s), view distance {})",
            threads, ServerConfig.allvrLodDistance);
    }

    public void resetPlayer(UUID uuid) {
        this.subs.remove(uuid);
    }

    // ------------------------------------------------------------------
    // tick (server thread)
    // ------------------------------------------------------------------

    public void tick() {
        List<ServerPlayer> players = this.level.players();
        if (players.isEmpty()) {
            return;
        }
        int viewDistance = ServerConfig.allvrLodDistance;

        this.flushDirtyNodes(players);
        this.refreshBitmaps(players, viewDistance);
        this.dispatchPrep();
        this.drainResults(players);
        if (++this.evictScanTicks >= EVICT_SCAN_TICKS) {
            this.evictScanTicks = 0;
            this.evictCache(players, viewDistance);
        }
    }

    /** Bitmap recompute + resend when a player crossed the per-level threshold. */
    private void refreshBitmaps(List<ServerPlayer> players, int viewDistance) {
        for (ServerPlayer player : players) {
            Sub sub = this.subs.computeIfAbsent(player.getUUID(), k -> new Sub());
            for (int lvl = 0; lvl <= AllvrLodBands.MAX_LEVEL; lvl++) {
                int cellShift = 5 + lvl;
                int cx = player.getBlockX() >> cellShift;
                int cy = player.getBlockY() >> cellShift;
                int cz = player.getBlockZ() >> cellShift;
                AllvrCubePos last = sub.lastCenter[lvl];
                int threshold = AllvrLodBands.resendThresholdCells(lvl, viewDistance);
                if (last != null
                    && Math.abs(last.getX() - cx) < threshold
                    && Math.abs(last.getY() - cy) < threshold
                    && Math.abs(last.getZ() - cz) < threshold) {
                    continue;
                }
                int dim = AllvrLodBands.bitmapBoxCells(lvl, viewDistance);
                int ox = cx - (dim >> 1);
                int oy = cy - (dim >> 1);
                int oz = cz - (dim >> 1);
                long[] words = this.field.compute(lvl, ox, oy, oz, dim);
                player.connection.send(new ClientboundAllvrLodBitmapPacket(lvl, ox, oy, oz, dim, words));
                sub.lastCenter[lvl] = AllvrCubePos.of(cx, cy, cz);
            }
        }
    }

    /** Main-thread prep slice: overlay capture (world reads) → pool handoff. */
    private void dispatchPrep() {
        long deadline = System.nanoTime() + PREP_BUDGET_NANOS;
        while (!this.prepQueue.isEmpty()) {
            PendingJob job = this.prepQueue.poll();
            Request req = this.requests[job.level()].remove(job.cellLong());
            if (req == null) {
                continue; // invalidated while queued — its forget already went out
            }
            AllvrLodPos pos = AllvrLodPos.fromCellLong(job.level(), job.cellLong());
            AllvrLodSnapshot.Overlay overlay = AllvrLodSnapshot.capture(this.cubeMap, pos);
            List<UUID> requesters = List.copyOf(req.requesters);
            long gen = req.gen;
            this.pool.execute(() -> this.runJob(job.level(), job.cellLong(), gen, overlay, requesters));
            if (System.nanoTime() >= deadline) {
                break;
            }
        }
    }

    /** Pool thread: pure density math + mesher — no world access here. */
    private void runJob(int level, long cellLong, long gen,
                        AllvrLodSnapshot.Overlay overlay, List<UUID> requesters) {
        long[] quads = null;
        try {
            AllvrLodPos pos = AllvrLodPos.fromCellLong(level, cellLong);
            BlockState[] states = new BlockState[AllvrMesher.PADDED * AllvrMesher.PADDED * AllvrMesher.PADDED];
            java.util.Arrays.fill(states, net.minecraft.world.level.block.Blocks.AIR.defaultBlockState());
            byte[] occludes = new byte[states.length];
            AllvrLodSnapshot snapshot = AllvrLodSnapshot.create(this.generator, pos, overlay);
            snapshot.fill(states, occludes);
            quads = AllvrMesher.build(states, occludes, snapshot.light(), AllvrLodSnapshot.SERVER_CODEC);
        } catch (Throwable t) {
            CreateManaIndustry.LOGGER.error("[Allvr] LOD build failed on {} — client re-requests", pos(level, cellLong), t);
        }
        this.results.add(new BuiltMesh(level, cellLong, gen, quads, requesters));
    }

    private static String pos(int level, long cellLong) {
        return AllvrLodPos.fromCellLong(level, cellLong).toString();
    }

    private void drainResults(List<ServerPlayer> players) {
        BuiltMesh mesh;
        while ((mesh = this.results.poll()) != null) {
            long curGen = this.gens[mesh.level()].get(mesh.cellLong());
            boolean stale = mesh.gen() != curGen || mesh.quads() == null;
            if (!stale) {
                this.cachePut(mesh.level(), mesh.cellLong(), mesh.quads());
            }
            ClientboundAllvrLodMeshPacket packet = stale
                ? null
                : new ClientboundAllvrLodMeshPacket(mesh.level(), mesh.cellLong(), mesh.quads());
            ClientboundAllvrLodForgetPacket forget = stale
                ? new ClientboundAllvrLodForgetPacket(mesh.level(), mesh.cellLong())
                : null;
            for (UUID uuid : mesh.requesters()) {
                ServerPlayer player = findPlayer(players, uuid);
                if (player == null) {
                    continue;
                }
                player.connection.send(stale ? forget : packet);
            }
        }
    }

    private static ServerPlayer findPlayer(List<ServerPlayer> players, UUID uuid) {
        for (ServerPlayer player : players) {
            if (player.getUUID().equals(uuid)) {
                return player;
            }
        }
        return null;
    }

    // ------------------------------------------------------------------
    // requests (server thread, from the C2S packet)
    // ------------------------------------------------------------------

    public void onRequest(ServerPlayer player, List<long[]> entries) {
        int viewDistance = ServerConfig.allvrLodDistance;
        for (long[] entry : entries) {
            int lvl = (int) entry[0];
            long cellLong = entry[1];
            if (lvl < 0 || lvl > AllvrLodBands.MAX_LEVEL) {
                continue;
            }
            AllvrLodPos pos = AllvrLodPos.fromCellLong(lvl, cellLong);
            if (chebyshevToNode(player.getPosition(1.0f), pos) > viewDistance + pos.sizeBlocks()) {
                player.connection.send(new ClientboundAllvrLodForgetPacket(lvl, cellLong));
                continue;
            }
            Request existing = this.requests[lvl].get(cellLong);
            if (existing != null) {
                if (!existing.requesters.contains(player.getUUID())) {
                    existing.requesters.add(player.getUUID());
                }
                continue;
            }
            long[] cached = this.cache[lvl].get(cellLong);
            if (cached != null) {
                player.connection.send(new ClientboundAllvrLodMeshPacket(lvl, cellLong, cached));
                continue;
            }
            if (this.requests[lvl].size() >= MAX_INFLIGHT) {
                player.connection.send(new ClientboundAllvrLodForgetPacket(lvl, cellLong));
                continue;
            }
            Request req = new Request(this.gens[lvl].get(cellLong));
            req.requesters.add(player.getUUID());
            this.requests[lvl].put(cellLong, req);
            this.prepQueue.add(new PendingJob(lvl, cellLong));
        }
    }

    private static int chebyshevToNode(net.minecraft.world.phys.Vec3 playerPos, AllvrLodPos pos) {
        double dx = Math.max(pos.minBlockX() - playerPos.x, playerPos.x - (pos.minBlockX() + (double) pos.sizeBlocks()));
        double dy = Math.max(pos.minBlockY() - playerPos.y, playerPos.y - (pos.minBlockY() + (double) pos.sizeBlocks()));
        double dz = Math.max(pos.minBlockZ() - playerPos.z, playerPos.z - (pos.minBlockZ() + (double) pos.sizeBlocks()));
        return (int) Math.max(0, Math.max(Math.max(dx, dy), dz));
    }

    // ------------------------------------------------------------------
    // invalidation (server thread, from AllvrCubeMap#setBlock)
    // ------------------------------------------------------------------

    public void onBlockChanged(net.minecraft.core.BlockPos pos) {
        for (int lvl = 0; lvl <= AllvrLodBands.MAX_LEVEL; lvl++) {
            long cellLong = AllvrCubePos.asLong(pos.getX() >> (5 + lvl), pos.getY() >> (5 + lvl), pos.getZ() >> (5 + lvl));
            this.gens[lvl].put(cellLong, this.gens[lvl].get(cellLong) + 1);
            this.requests[lvl].remove(cellLong);
            this.cacheRemove(lvl, cellLong);
            this.dirtyNodes[lvl].add(cellLong);
        }
    }

    /** Flushes forget broadcasts, deduped — a /fill edit storm collapses to
     *  one packet per touched node per level. */
    private void flushDirtyNodes(List<ServerPlayer> players) {
        for (int lvl = 0; lvl <= AllvrLodBands.MAX_LEVEL; lvl++) {
            LongOpenHashSet dirty = this.dirtyNodes[lvl];
            if (dirty.isEmpty()) {
                continue;
            }
            for (long cellLong : dirty) {
                ClientboundAllvrLodForgetPacket p = new ClientboundAllvrLodForgetPacket(lvl, cellLong);
                for (ServerPlayer player : players) {
                    player.connection.send(p);
                }
            }
            dirty.clear();
        }
    }

    // ------------------------------------------------------------------
    // cache
    // ------------------------------------------------------------------

    private void cachePut(int lvl, long cellLong, long[] quads) {
        long[] old = this.cache[lvl].put(cellLong, quads);
        if (old != null) {
            this.cacheBytes -= old.length << 3;
        }
        this.cacheBytes += quads.length << 3;
        while (this.cacheBytes > CACHE_BUDGET_BYTES) {
            var it = this.cache[lvl].entrySet().iterator();
            if (!it.hasNext()) {
                CreateManaIndustry.LOGGER.warn("[Allvr] LOD cache over budget with empty level map");
                break;
            }
            var eldest = it.next();
            it.remove();
            this.cacheBytes -= eldest.getValue().length << 3;
        }
    }

    private long cacheRemove(int lvl, long cellLong) {
        long[] old = this.cache[lvl].remove(cellLong);
        if (old == null) {
            return -1;
        }
        this.cacheBytes -= old.length << 3;
        return old.length;
    }

    /** Drops cache entries outside every player's R×1.25 hysteresis (4a decision). */
    private void evictCache(List<ServerPlayer> players, int viewDistance) {
        long limit = (long) viewDistance * 5 / 4;
        for (int lvl = 0; lvl <= AllvrLodBands.MAX_LEVEL; lvl++) {
            var it = this.cache[lvl].entrySet().iterator();
            while (it.hasNext()) {
                var e = it.next();
                AllvrLodPos pos = AllvrLodPos.fromCellLong(lvl, e.getKey());
                boolean near = false;
                for (ServerPlayer player : players) {
                    if (chebyshevToNode(player.getPosition(1.0f), pos) <= limit) {
                        near = true;
                        break;
                    }
                }
                if (!near) {
                    it.remove();
                    this.cacheBytes -= e.getValue().length << 3;
                }
            }
        }
    }
}
