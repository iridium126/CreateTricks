package com.iridium126.createmanaindustry.client.particles.engine;

import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.phys.Vec3;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL12;
import org.lwjgl.opengl.GL13;
import org.lwjgl.opengl.GL30;

/**
 * Block-collision occupancy bakes for the particle engine.
 * <p>
 * A single {@code GL_TEXTURE_3D} hosts up to {@link #MAX_SLICES} independent
 * volumes stacked along the depth axis: each slice is {@link #SX} x {@link #SY}
 * x {@link #SZ} texels at 1 block/texel, one byte per texel (255 solid, 0 air).
 * Slices are keyed by their ANCHOR GRID CELL (not by emitter spec): every
 * colliding spawn site maps to the slice anchored at its rounded origin, so
 * nearby sites share one volume while distant sites get their own.
 * <p>
 * The ~73k {@code getBlockState} queries of a rebuild run on a BACKGROUND
 * daemon worker (one build in flight at a time) — the render thread only
 * resolves the covered chunk references (the client chunk map must not be
 * iterated off-thread) and uploads the finished voxel volume. A slice's
 * presence flag in the meta SSBO stays 0 until its volume matches the anchor,
 * so particles never collide against a stale volume from a previous occupant.
 * Results are discarded when the world changed or the slice was evicted in
 * between (the slice simply stays dirty and is retried). Slices are refreshed
 * once per {@link #REBUILD_TICKS} while used and evicted (LRU) when all slots
 * are taken.
 */
public final class CollisionBake {

    /** Max 3D slices stacked in the texture (each a full 3D occupancy volume). */
    public static final int MAX_SLICES = 8;
    public static final int SX = 48;
    public static final int SY = 32;
    public static final int SZ = 48;
    /** Rebuild cadence in game ticks (20 = ~1 s). */
    private static final long REBUILD_TICKS = 20;
    /** XZ/Y centring around the anchor block. */
    private static final int CENTER_XZ = SX / 2;
    private static final int CENTER_Y = SY / 2;
    private static final byte SOLID = (byte) 255;
    private static final byte AIR = 0;

    /** Anchor grid cell a slice is centred on (the map key). */
    private record Anchor(int x, int y, int z) {
    }

    /** One assigned occupant slice, pinned to its anchor. */
    private static final class Slot {
        final int slice;
        final int ax;
        final int ay;
        final int az;
        boolean dirty = true;
        long lastBuilt = Long.MIN_VALUE;

        Slot(int slice, int ax, int ay, int az) {
            this.slice = slice;
            this.ax = ax;
            this.ay = ay;
            this.az = az;
        }
    }

    /**
     * One background rebuild: the world and chunk references are resolved on
     * the render thread; the worker only performs plain reads inside the
     * captured chunks (unloaded chunks stay alive via the reference and read
     * their last contents — no exceptions, just momentarily stale data).
     */
    private static final class BuildTask {
        final Level level;
        final Slot slot;
        final Map<Long, LevelChunk> chunks;
        final int minY;
        final int maxY;

        BuildTask(Level level, Slot slot, Map<Long, LevelChunk> chunks, int minY, int maxY) {
            this.level = level;
            this.slot = slot;
            this.chunks = chunks;
            this.minY = minY;
            this.maxY = maxY;
        }
    }

    /** accessOrder=true: iteration order = LRU -> MRU. */
    private final Map<Anchor, Slot> slots = new LinkedHashMap<>(8, 0.75f, true);
    private final boolean[] occupied = new boolean[MAX_SLICES];
    private final float[] meta = new float[MAX_SLICES * 4];

    private int textureId = -1;
    private boolean metaDirty = false;

    /** Lazily created daemon worker (one bake at a time); shut down in free(). */
    private ExecutorService worker;
    private Future<byte[]> inFlight;
    private BuildTask inFlightTask;

    // Render-thread scratch for the finished-volume upload (73,728 B).
    private final ByteBuffer upload = ByteBuffer.allocateDirect(SX * SY * SZ);

    /**
     * 1-based bake slice covering {@code origin} (0 = none). The slice is
     * anchored at the grid cell rounded from the origin; equal anchors share.
     */
    public int ensure(Vec3 origin) {
        if (origin == null)
            return 0;
        if (textureId < 0 && !createTexture())
            return 0;
        return ensureAnchor(floor(origin.x - CENTER_XZ), floor(origin.y - CENTER_Y),
                floor(origin.z - CENTER_XZ));
    }

    /**
     * Ensures a 2x2 grid of bake volumes tiled in the HORIZONTAL plane around
     * {@code origin} (all four share one Y band): the tile SEAM passes through
     * the origin itself, so the seamless 96 x 96 XZ footprint is always
     * CENTRED on it — coverage extends exactly one tile (48 blocks) on every
     * side, regardless of where the origin falls relative to any world
     * lattice. Allay Storm swarms use this because their radius (up to
     * 64 · 1.15 fringe ≈ 74 blocks) reaches past a single centred volume. Tiles abut rather than overlap —
     * containment bounds are exclusive, so a particle crossing a seam lands
     * in exactly one neighbour and the coverage stays watertight. Each tile
     * allocates independently; full-pool LRU eviction applies per tile as in
     * {@link #ensure}.
     */
    public void ensureQuadrants(Vec3 origin) {
        if (origin == null)
            return;
        if (textureId < 0 && !createTexture())
            return;

        // seam THROUGH the origin: the union always spans
        // [floor(x)-48, floor(x)+48) XZ — centred coverage, no lattice swing
        int ax = floor(origin.x);
        int az = floor(origin.z);
        int ay = floor(origin.y) - CENTER_Y;

        ensureAnchor(ax - SX, ay, az - SZ);
        ensureAnchor(ax, ay, az - SZ);
        ensureAnchor(ax - SX, ay, az);
        ensureAnchor(ax, ay, az);
    }

    /**
     * Wave-target collision shaft: one slice whose XZ center is the target
     * player and whose Y band STARTS at the player's feet (docs/allay-storm-ai.md
     * §6 — the 32-texel budget goes where the contact happens, not the empty
     * sky column). Distinct from {@link #ensure}'s centered anchoring, which
     * would waste half the band below the feet and center on the block
     * lattice anyway.
     */
    public int ensureColumn(double px, double feetY, double pz) {
        if (textureId < 0 && !createTexture())
            return 0;
        return ensureAnchor(floor(px - CENTER_XZ), floor(feetY), floor(pz - CENTER_XZ));
    }

    /**
     * Touch (LRU recency) or allocate the slice pinned to an explicit anchor
     * cell — the wave runtime re-ensures its pinned shaft anchor every frame
     * and re-pins through {@link #ensureColumn} only when its hysteresis
     * trips, so the per-frame call must NOT re-derive the anchor from the
     * (sub-cell) player position.
     */
    public int ensureAnchorCell(int ax, int ay, int az) {
        if (textureId < 0 && !createTexture())
            return 0;
        return ensureAnchor(ax, ay, az);
    }

    /**
     * Allocates or touches the slot pinned to one anchor grid cell (LRU
     * recency rides the map lookup); returns the 1-based slice index.
     */
    private int ensureAnchor(int ax, int ay, int az) {
        Anchor key = new Anchor(ax, ay, az);
        Slot s = slots.get(key);
        if (s == null) {
            int free = -1;
            for (int i = 0; i < MAX_SLICES; i++) {
                if (!occupied[i]) {
                    free = i;
                    break;
                }
            }
            if (free < 0) {
                evictLru();
                for (int i = 0; i < MAX_SLICES; i++) {
                    if (!occupied[i]) {
                        free = i;
                        break;
                    }
                }
            }
            if (free < 0)
                return 0;
            s = new Slot(free, ax, ay, az);
            occupied[free] = true;
            slots.put(key, s);
            metaDirty = true;
        }
        return s.slice + 1;
    }

    /**
     * Keeps the slices fresh: collects a finished background build (upload on
     * the render thread), then hands at most one stale/dirty slice to the
     * worker. The ~73k block queries never touch the render thread. Call every
     * frame on the render thread.
     */
    public void tick() {
        if (slots.isEmpty() || textureId < 0)
            return;
        Level level = Minecraft.getInstance().level;
        if (level == null)
            return;

        if (this.inFlightTask != null && this.inFlight.isDone())
            finishBuild(level);
        if (this.inFlightTask != null)
            return; // one build in flight — pick the next slice when it lands

        long now = level.getGameTime();
        Slot dirtyPick = null;
        Slot stalePick = null;
        for (Slot s : slots.values()) { // LRU -> MRU: keep the LAST match
            if (s.dirty)
                dirtyPick = s;
            else if (now - s.lastBuilt >= REBUILD_TICKS)
                stalePick = s;
        }
        Slot target = dirtyPick != null ? dirtyPick : stalePick;
        if (target != null)
            submitBuild(level, target);
    }

    /**
     * Current bake-meta float array for the SSBO (origin.xyz + presence). The
     * presence flag is only raised once the slice has been built for its
     * anchor, so particles never collide against a previous occupant's voxels.
     */
    public float[] meta() {
        for (int i = 0; i < MAX_SLICES; i++) {
            int base = i * 4;
            Slot s = findSlot(i);
            if (s != null && !s.dirty && s.lastBuilt != Long.MIN_VALUE) {
                meta[base + 0] = s.ax;
                meta[base + 1] = s.ay;
                meta[base + 2] = s.az;
                meta[base + 3] = 1f;
            } else {
                meta[base + 0] = 0f;
                meta[base + 1] = 0f;
                meta[base + 2] = 0f;
                meta[base + 3] = 0f;
            }
        }
        return meta;
    }

    public boolean metaDirty() {
        return this.metaDirty;
    }

    public void markMetaClean() {
        this.metaDirty = false;
    }

    /** Whether the 3D occupancy texture exists (bindings/sampler usable). */
    public boolean ready() {
        return textureId >= 0;
    }

    public void bind(int unit) {
        if (textureId < 0)
            return;
        GL13.glActiveTexture(GL13.GL_TEXTURE0 + unit);
        GL12.glBindTexture(GL12.GL_TEXTURE_3D, textureId);
    }

    public void free() {
        if (this.worker != null) {
            this.worker.shutdownNow();
            this.worker = null;
        }
        this.inFlight = null;
        this.inFlightTask = null;
        if (textureId >= 0) {
            GL30.glDeleteTextures(textureId);
            textureId = -1;
        }
        slots.clear();
        for (int i = 0; i < MAX_SLICES; i++)
            occupied[i] = false;
        metaDirty = true;
    }

    /**
     * Drops every slice and discards any in-flight build (level change:
     * occupancy volumes are world coordinates and are meaningless across
     * dimensions — stale presence flags would both falsely collide
     * new-dimension particles near the old anchors and squat slots until LRU
     * eviction). Keeps the texture and the worker alive; {@code
     * finishBuild} re-validates slot identity, so a build completing after
     * this reset is discarded instead of resurrecting an evicted slice, and
     * the next {@link #ensure}/{@link #ensureQuadrants} re-allocates lazily.
     */
    public void reset() {
        // leave the old future to finish on the worker unpollled: nulling the
        // task reference makes tick() treat the worker as free and submit the
        // next build (queued behind the running one on the single thread)
        this.inFlight = null;
        this.inFlightTask = null;
        this.slots.clear();
        for (int i = 0; i < MAX_SLICES; i++)
            this.occupied[i] = false;
        this.metaDirty = true; // presence flags upload as 0 on the next frame
    }

    // ------------------------------------------------------------------
    // Async rebuild internals (render thread: submit/finish; worker: query)
    // ------------------------------------------------------------------

    private void submitBuild(Level level, Slot s) {
        // Resolve the covered chunk references HERE: the client chunk map is
        // not safe to iterate off-thread, but plain reads inside an already
        // captured LevelChunk are benign (worst case a momentarily stale
        // voxel in a volume that refreshes every second anyway).
        Map<Long, LevelChunk> chunks = new HashMap<>();
        for (int cx = s.ax >> 4; cx <= (s.ax + SX - 1) >> 4; cx++) {
            for (int cz = s.az >> 4; cz <= (s.az + SZ - 1) >> 4; cz++) {
                LevelChunk chunk = level.getChunk(cx, cz);
                if (chunk != null)
                    chunks.put(ChunkPos.asLong(cx, cz), chunk);
            }
        }
        if (this.worker == null) {
            this.worker = Executors.newSingleThreadExecutor(r -> {
                Thread t = new Thread(r, "CMI-collision-bake");
                t.setDaemon(true);
                return t;
            });
        }
        BuildTask task = new BuildTask(level, s, chunks,
                level.getMinBuildHeight(), level.getMaxBuildHeight());
        this.inFlightTask = task;
        this.inFlight = this.worker.submit(() -> buildVoxels(task));
    }

    /** Fills one 48x32x48 occupancy volume; null = interrupted/failed (retry). */
    private static byte[] buildVoxels(BuildTask task) {
        byte[] voxels = new byte[SX * SY * SZ];
        BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
        try {
            int i = 0;
            for (int z = 0; z < SZ; z++) {
                if (Thread.currentThread().isInterrupted())
                    return null;
                for (int y = 0; y < SY; y++) {
                    int wy = task.slot.ay + y;
                    boolean yInWorld = wy >= task.minY && wy < task.maxY;
                    for (int x = 0; x < SX; x++) {
                        boolean solid = false;
                        if (yInWorld) {
                            int wx = task.slot.ax + x;
                            int wz = task.slot.az + z;
                            LevelChunk chunk = task.chunks.get(ChunkPos.asLong(wx >> 4, wz >> 4));
                            // Bake only blocks with an actual COLLISION shape:
                            // grass, flowers, torches, crops, rails and fluids
                            // have none and let particles pass through (vanilla
                            // particle parity — vanilla particles ignore no-
                            // collision blocks and fluids too). The 2-arg
                            // getCollisionShape reads the per-state cached shape
                            // (BlockStateBase.Cache.collisionShape, computed
                            // against EmptyBlockGetter at state init), so the
                            // off-thread call carries the same benign-race
                            // tolerance as getBlockState above; a failed build
                            // retries on the next pick.
                            solid = chunk != null && !chunk.getBlockState(pos.set(wx, wy, wz))
                                    .getCollisionShape(chunk, pos).isEmpty();
                        }
                        voxels[i++] = solid ? SOLID : AIR;
                    }
                }
            }
            return voxels;
        } catch (Exception e) {
            return null; // benign race with a chunk packet — retried next pick
        }
    }

    /** Uploads a finished build; discards it if the world or slot changed. */
    private void finishBuild(Level level) {
        BuildTask task = this.inFlightTask;
        Future<byte[]> future = this.inFlight;
        this.inFlightTask = null;
        this.inFlight = null;

        byte[] data;
        try {
            data = future.get(); // isDone() was true — never blocks
        } catch (Exception e) {
            return; // cancelled (free()) or worker crash — slot stays dirty
        }
        if (data == null)
            return; // interrupted/raced build — retried on the next pick

        // discard results whose world went away or whose slice was
        // evicted/re-anchored while the worker was running
        if (Minecraft.getInstance().level != task.level
                || this.slots.get(new Anchor(task.slot.ax, task.slot.ay, task.slot.az)) != task.slot)
            return;

        this.upload.clear();
        this.upload.put(data);
        this.upload.flip();

        GL12.glBindTexture(GL12.GL_TEXTURE_3D, textureId);
        ParticleGLUtil.prepareClientUpload(); // guard PBO / unpack-state leftovers
        GL12.glTexSubImage3D(GL12.GL_TEXTURE_3D, 0, 0, 0, task.slot.slice * SZ, SX, SY, SZ,
                GL11.GL_RED, GL11.GL_UNSIGNED_BYTE, upload);
        task.slot.dirty = false;
        task.slot.lastBuilt = level.getGameTime();
        metaDirty = true; // presence flag for this slice can now be raised
    }

    // ------------------------------------------------------------------
    // Internals
    // ------------------------------------------------------------------

    private Slot findSlot(int slice) {
        if (slice < 0 || slice >= MAX_SLICES)
            return null;
        for (Slot s : slots.values())
            if (s.slice == slice)
                return s;
        return null;
    }

    private void evictLru() {
        // LinkedHashMap accessOrder=true: iterator order = LRU -> MRU.
        var it = slots.entrySet().iterator();
        if (it.hasNext()) {
            var e = it.next();
            it.remove();
            occupied[e.getValue().slice] = false;
            metaDirty = true;
        }
    }

    private boolean createTexture() {
        textureId = GL11.glGenTextures();
        GL12.glBindTexture(GL12.GL_TEXTURE_3D, textureId);
        GL11.glTexParameteri(GL12.GL_TEXTURE_3D, GL11.GL_TEXTURE_MIN_FILTER, GL11.GL_NEAREST);
        GL11.glTexParameteri(GL12.GL_TEXTURE_3D, GL11.GL_TEXTURE_MAG_FILTER, GL11.GL_NEAREST);
        GL11.glTexParameteri(GL12.GL_TEXTURE_3D, GL11.GL_TEXTURE_WRAP_S, GL12.GL_CLAMP_TO_EDGE);
        GL11.glTexParameteri(GL12.GL_TEXTURE_3D, GL11.GL_TEXTURE_WRAP_T, GL12.GL_CLAMP_TO_EDGE);
        GL11.glTexParameteri(GL12.GL_TEXTURE_3D, GL12.GL_TEXTURE_WRAP_R, GL12.GL_CLAMP_TO_EDGE);
        ParticleGLUtil.prepareClientUpload(); // guard PBO / unpack-state leftovers
        GL12.glTexImage3D(GL12.GL_TEXTURE_3D, 0, GL30.GL_R8, SX, SY, SZ * MAX_SLICES, 0,
                GL11.GL_RED, GL11.GL_UNSIGNED_BYTE, (ByteBuffer) null);
        return true;
    }

    /** List of all slices currently in use (for diagnostics). */
    public List<Integer> activeSlices() {
        List<Integer> out = new ArrayList<>();
        for (Slot s : slots.values())
            out.add(s.slice);
        return out;
    }

    private static int floor(double v) {
        int i = (int) v;
        return v < i ? i - 1 : i;
    }
}
