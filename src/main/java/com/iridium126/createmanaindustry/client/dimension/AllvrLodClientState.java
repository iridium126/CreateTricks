package com.iridium126.createmanaindustry.client.dimension;

import java.util.ArrayList;
import java.util.List;

import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;

import com.iridium126.createmanaindustry.CreateManaIndustry;
import com.iridium126.createmanaindustry.client.dimension.render.AllvrRenderStateMap;
import com.iridium126.createmanaindustry.client.dimension.render.AllvrRenderer;
import com.iridium126.createmanaindustry.config.ClientConfig;
import com.iridium126.createmanaindustry.dimension.AllvrDimensions;
import com.iridium126.createmanaindustry.dimension.cube.AllvrCubePos;
import com.iridium126.createmanaindustry.dimension.lod.AllvrLodPos;
import com.iridium126.createmanaindustry.dimension.net.ClientboundAllvrLodBitmapPacket;
import com.iridium126.createmanaindustry.dimension.net.ClientboundAllvrLodForgetPacket;
import com.iridium126.createmanaindustry.dimension.net.ClientboundAllvrLodMeshPacket;
import com.iridium126.createmanaindustry.dimension.net.ServerboundAllvrLodRequestPacket;

/**
 * Client half of the LOD pipeline (doc §13 4c-1): holds the per-level
 * surface-node bitmaps streamed by the server, walks them once per client
 * tick to issue batched mesh requests (64/tick, per-level in-flight cap
 * 256 — grilling Q5), remaps server quads from vanilla state ids to render
 * ids on receive, and evicts nodes beyond the bitmap box's hysteresis.
 * <p>
 * Nodes whose cells fall inside the full-resolution streaming radius
 * (Chebyshev 8 cubes) are never requested: 256 blocks is a multiple of every
 * node size, so no node straddles the boundary and that terrain comes
 * entirely from the full-res cube stream. All apply/forget/tick run on the
 * main thread ({@code enqueueWork} / client tick), which is the render
 * thread.
 */
public final class AllvrLodClientState {

    /** Mesh requests sent per tick (grilling Q5). */
    private static final int REQUESTS_PER_TICK = 64;
    /** Per-level in-flight cap. */
    private static final int MAX_PENDING = 256;
    /** Cell Chebyshev distance where "fully outside the full-res radius" begins. */
    private static final int FULL_RES_RADIUS_CUBES = 8;

    private static final class LevelState {
        int originX;
        int originY;
        int originZ;
        int dim;
        long[] words;
    }

    private static final LevelState[] levels = new LevelState[4];
    private static final LongOpenHashSet[] pending = new LongOpenHashSet[4];
    private static final LongOpenHashSet[] meshed = new LongOpenHashSet[4];
    private static boolean warnedNeedsGpu;
    private static boolean loggedFirstBitmap;
    private static boolean loggedFirstMesh;

    static {
        for (int i = 0; i < 4; i++) {
            pending[i] = new LongOpenHashSet();
            meshed[i] = new LongOpenHashSet();
        }
    }

    // ------------------------------------------------------------------
    // packet application (main thread)
    // ------------------------------------------------------------------

    public static void applyBitmap(ClientboundAllvrLodBitmapPacket packet) {
        if (!inDimension()) {
            return; // level switched — the bitmap died with it
        }
        int lvl = packet.level();
        if (lvl < 0 || lvl > AllvrLodPos.MAX_LEVEL) {
            return;
        }
        LevelState state = new LevelState();
        state.originX = packet.originCellX();
        state.originY = packet.originCellY();
        state.originZ = packet.originCellZ();
        state.dim = packet.dimCells();
        state.words = packet.words();
        levels[lvl] = state;
        if (!loggedFirstBitmap) {
            loggedFirstBitmap = true;
            CreateManaIndustry.LOGGER.info(
                "[Allvr] LOD bitmap L{}: box {}³ cells at ({},{},{}) — {} surface nodes",
                lvl, state.dim, state.originX, state.originY, state.originZ, countBits(state));
        }
    }

    public static void applyMesh(ClientboundAllvrLodMeshPacket packet) {
        if (!inDimension()) {
            return;
        }
        int lvl = packet.level();
        if (lvl < 0 || lvl > AllvrLodPos.MAX_LEVEL) {
            return;
        }
        pending[lvl].remove(packet.cellLong());
        long[] quads = remapQuads(packet.quads());
        AllvrRenderer.INSTANCE.applyLodMesh(lvl, packet.cellLong(), quads);
        meshed[lvl].add(packet.cellLong());
        if (!loggedFirstMesh) {
            loggedFirstMesh = true;
            CreateManaIndustry.LOGGER.info("[Allvr] first LOD mesh: {} ({} quads after remap)",
                AllvrLodPos.fromCellLong(lvl, packet.cellLong()), quads.length);
        }
    }

    public static void applyForget(ClientboundAllvrLodForgetPacket packet) {
        if (!inDimension()) {
            return;
        }
        int lvl = packet.level();
        if (lvl < 0 || lvl > AllvrLodPos.MAX_LEVEL) {
            return;
        }
        pending[lvl].remove(packet.cellLong());
        meshed[lvl].remove(packet.cellLong());
        AllvrRenderer.INSTANCE.forgetLod(lvl, packet.cellLong());
    }

    /** Drops all LOD state (level unload / dimension switch / logout). */
    public static void clear() {
        for (int i = 0; i < 4; i++) {
            levels[i] = null;
            pending[i].clear();
            meshed[i].clear();
        }
        loggedFirstBitmap = false;
        loggedFirstMesh = false;
    }

    // ------------------------------------------------------------------
    // per-tick request walk (main thread)
    // ------------------------------------------------------------------

    public static void tick() {
        if (!ClientConfig.allvrLod) {
            return;
        }
        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null || mc.level.dimension() != AllvrDimensions.ALLAY_LEVEL || mc.player == null) {
            return;
        }
        if (!AllvrRenderer.INSTANCE.lodGate()) {
            if (!warnedNeedsGpu) {
                warnedNeedsGpu = true;
                CreateManaIndustry.LOGGER.warn(
                    "[Allvr] allvrLod enabled but the GPU terrain pipeline is unavailable — LOD inactive");
            }
            return;
        }
        BlockPos player = mc.player.blockPosition();
        List<long[]> entries = new ArrayList<>();
        for (int lvl = 0; lvl <= AllvrLodPos.MAX_LEVEL; lvl++) {
            LevelState state = levels[lvl];
            if (state == null || state.words == null) {
                continue;
            }
            walkLevel(lvl, state, player, entries);
        }
        // the request packet carries at most 16 entries — flush in chunks
        for (int i = 0; i < entries.size(); i += ServerboundAllvrLodRequestPacket.MAX_ENTRIES) {
            int end = Math.min(entries.size(), i + ServerboundAllvrLodRequestPacket.MAX_ENTRIES);
            net.neoforged.neoforge.network.PacketDistributor.sendToServer(
                ServerboundAllvrLodRequestPacket.of(entries.subList(i, end)));
        }
    }

    private static void walkLevel(int lvl, LevelState state, BlockPos player, List<long[]> entries) {
        int half = state.dim >> 1;
        int playerCellX = player.getX() >> (5 + lvl);
        int playerCellY = player.getY() >> (5 + lvl);
        int playerCellZ = player.getZ() >> (5 + lvl);
        // nodes fully outside the full-res streaming radius begin at this cell
        // distance: L0→9, L1→5, L2→3, L3→2 (256 blocks is a multiple of every
        // node size, so no node can straddle the full-res boundary)
        int minDist = FULL_RES_RADIUS_CUBES / (1 << lvl) + 1;

        int budget = REQUESTS_PER_TICK - entries.size();
        if (budget <= 0 || pending[lvl].size() >= MAX_PENDING) {
            return;
        }

        // iterate the bitmap box but measure distance from the PLAYER's cell,
        // not the box center — the box lags the player by up to the resend
        // threshold, and box-relative distance would request nodes inside the
        // full-res zone (duplicate geometry) after the player walks toward them
        for (int cy = state.originY; cy < state.originY + state.dim && budget > 0; cy++) {
            int dy = cy - playerCellY;
            for (int cz = state.originZ; cz < state.originZ + state.dim && budget > 0; cz++) {
                int dz = cz - playerCellZ;
                for (int cx = state.originX; cx < state.originX + state.dim && budget > 0; cx++) {
                    int dx = cx - playerCellX;
                    int dist = Math.max(Math.abs(dx), Math.max(Math.abs(dy), Math.abs(dz)));
                    if (dist < minDist || dist > half) {
                        continue; // full-res territory, or beyond the band
                    }
                    if (!isSurface(state, cx - state.originX, cy - state.originY, cz - state.originZ)) {
                        continue;
                    }
                    long cellLong = AllvrCubePos.asLong(cx, cy, cz);
                    if (meshed[lvl].contains(cellLong) || pending[lvl].contains(cellLong)) {
                        continue;
                    }
                    pending[lvl].add(cellLong);
                    entries.add(new long[] {lvl, cellLong});
                    budget--;
                    if (pending[lvl].size() >= MAX_PENDING) {
                        return;
                    }
                }
            }
        }
        evictFar(lvl, playerCellX, playerCellY, playerCellZ, half);
    }

    /** Drops rendered/pending nodes beyond the box half ×1.25 (hysteresis). */
    private static void evictFar(int lvl, int pcx, int pcy, int pcz, int half) {
        int limit = half + Math.max(1, half >> 2);
        evictSet(lvl, meshed[lvl], pcx, pcy, pcz, limit, true);
        evictSet(lvl, pending[lvl], pcx, pcy, pcz, limit, false);
    }

    private static void evictSet(int lvl, LongOpenHashSet set, int pcx, int pcy, int pcz, int limit,
                                 boolean rendered) {
        if (set.isEmpty()) {
            return;
        }
        var it = set.iterator();
        List<Long> removed = null;
        while (it.hasNext()) {
            long cellLong = it.nextLong();
            AllvrLodPos pos = AllvrLodPos.fromCellLong(lvl, cellLong);
            int d = Math.max(Math.abs(pos.cellX() - pcx),
                Math.max(Math.abs(pos.cellY() - pcy), Math.abs(pos.cellZ() - pcz)));
            if (d > limit) {
                if (removed == null) {
                    removed = new ArrayList<>();
                }
                removed.add(cellLong);
                if (rendered) {
                    AllvrRenderer.INSTANCE.forgetLod(lvl, cellLong);
                }
            }
        }
        if (removed != null) {
            set.removeAll(removed);
        }
    }

    // ------------------------------------------------------------------
    // helpers
    // ------------------------------------------------------------------

    private static boolean isSurface(LevelState state, int ix, int iy, int iz) {
        int bit = (iy * state.dim + iz) * state.dim + ix;
        return (state.words[bit >> 6] & (1L << (bit & 63))) != 0;
    }

    private static int countBits(LevelState state) {
        int n = 0;
        for (long w : state.words) {
            n += Long.bitCount(w);
        }
        return n;
    }

    /**
     * Server quads carry vanilla global state ids in the stateId field; the
     * client remaps them to render ids in place (one lookup per quad, once
     * per node). Light nibbles (hi bits 12..19) pass through untouched. Quads
     * whose state resolves to a non-renderable client entry become 0 (empty
     * mask cell) — the server gate (canOcclude + full block) makes this
     * pathological, not structural.
     */
    private static long[] remapQuads(long[] quads) {
        for (int i = 0; i < quads.length; i++) {
            long lo = quads[i] & 0xFFFFFFFFL;
            long hi = (quads[i] >>> 32) & 0xFFFFFFFFL;
            int vid = (int) (((lo >> 28) & 0xFL) | ((hi & 0xFFFL) << 4));
            BlockState state = Block.stateById(vid);
            int rid = state == null ? 0 : AllvrRenderStateMap.CLIENT_CODEC.packId(state);
            if (rid == 0) {
                quads[i] = 0;
                continue;
            }
            lo = (lo & ~0xF0000000L) | ((rid & 0xFL) << 28);
            hi = (hi & ~0xFFFL) | ((long) ((rid >> 4) & 0xFFF) << 32);
            quads[i] = lo | (hi << 32);
        }
        return quads;
    }

    private static boolean inDimension() {
        Minecraft mc = Minecraft.getInstance();
        return mc.level != null && mc.level.dimension() == AllvrDimensions.ALLAY_LEVEL;
    }

    private AllvrLodClientState() {}
}
