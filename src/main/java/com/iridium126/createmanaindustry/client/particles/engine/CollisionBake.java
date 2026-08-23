package com.iridium126.createmanaindustry.client.particles.engine;

import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.world.phys.Vec3;
import org.lwjgl.BufferUtils;
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
 * nearby sites share one volume while distant sites get their own — two far
 * apart emitters with identical specs can no longer fight over one slot
 * (which used to re-anchor and rebuild the volume every frame).
 * <p>
 * At most ONE slice is (re)built per frame (dirty slices first) to avoid the
 * 73k-block-query spike of rebuilding everything at once. A slice's presence
 * flag in the meta SSBO stays 0 until its volume matches the anchor, so
 * particles never collide against a stale volume from a previous occupant.
 * Slices are refreshed once per {@link #REBUILD_TICKS} while used and evicted
 * (LRU) when all slots are taken.
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

    /** accessOrder=true: iteration order = LRU -> MRU. */
    private final Map<Anchor, Slot> slots = new LinkedHashMap<>(8, 0.75f, true);
    private final boolean[] occupied = new boolean[MAX_SLICES];
    private final float[] meta = new float[MAX_SLICES * 4];

    private int textureId = -1;
    private boolean metaDirty = false;

    // Reused across rebuilds (73,728 B each) — no per-rebuild allocation churn.
    private byte[] voxels;
    private ByteBuffer upload;
    private final BlockPos.MutableBlockPos scratch = new BlockPos.MutableBlockPos();

    /**
     * 1-based bake slice covering {@code origin} (0 = none). The slice is
     * anchored at the grid cell rounded from the origin; equal anchors share.
     */
    public int ensure(Vec3 origin) {
        if (origin == null)
            return 0;
        if (textureId < 0 && !createTexture())
            return 0;

        int ax = floor(origin.x - CENTER_XZ);
        int ay = floor(origin.y - CENTER_Y);
        int az = floor(origin.z - CENTER_XZ);

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
     * Rebuilds at most one stale slice per frame (a dirty one first, else the
     * most recently used expired one), amortising the ~73k block queries each
     * rebuild costs. Call every frame on the render thread.
     */
    public void tick() {
        if (slots.isEmpty() || textureId < 0)
            return;
        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null)
            return;
        long now = mc.level.getGameTime();
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
            rebuild(mc, target);
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
        if (textureId >= 0) {
            GL30.glDeleteTextures(textureId);
            textureId = -1;
        }
        slots.clear();
        for (int i = 0; i < MAX_SLICES; i++)
            occupied[i] = false;
        metaDirty = true;
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

    private void rebuild(Minecraft mc, Slot s) {
        if (voxels == null) {
            voxels = new byte[SX * SY * SZ];
            upload = ByteBuffer.allocateDirect(voxels.length);
        }
        int i = 0;
        for (int z = 0; z < SZ; z++) {
            for (int y = 0; y < SY; y++) {
                for (int x = 0; x < SX; x++) {
                    boolean solid = !mc.level.getBlockState(scratch.set(s.ax + x, s.ay + y, s.az + z)).isAir();
                    voxels[i++] = solid ? SOLID : AIR;
                }
            }
        }
        upload.clear();
        upload.put(voxels);
        upload.flip();

        GL12.glBindTexture(GL12.GL_TEXTURE_3D, textureId);
        ParticleGLUtil.prepareClientUpload(); // guard PBO / unpack-state leftovers
        GL12.glTexSubImage3D(GL12.GL_TEXTURE_3D, 0, 0, 0, s.slice * SZ, SX, SY, SZ,
                GL11.GL_RED, GL11.GL_UNSIGNED_BYTE, upload);
        s.dirty = false;
        s.lastBuilt = mc.level.getGameTime();
        metaDirty = true; // presence flag for this slice can now be raised
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
