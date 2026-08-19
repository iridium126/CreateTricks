package com.iridium126.createmanaindustry.client.particles.engine;

import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.iridium126.createmanaindustry.client.particles.emitter.EmitterSpec;

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
 * Colliding emitters (per-spec) are assigned a slice anchored at their spawn
 * origin; nearby emitters share when they round to the same anchor grid cell.
 * Slices are rebuilt once per {@link #REBUILD_TICKS} while used, and evicted
 * (LRU) when all slots are taken.
 * <p>
 * The per-slice anchor/origin table is exposed as a flat float array
 * ({@link #meta()}) uploaded to the bake-meta SSBO so {@code update.comp} can
 * sample the right slice for each emitter's bake index.
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

    /** One assigned occupant slice. */
    private static final class Slot {
        final int slice;
        int ax;
        int ay;
        int az;
        boolean dirty = true;
        long lastBuilt = Long.MIN_VALUE;

        Slot(int slice) {
            this.slice = slice;
        }
    }

    private final Map<EmitterSpec, Slot> slots = new LinkedHashMap<>(8, 0.75f, true);
    private final boolean[] occupied = new boolean[MAX_SLICES];
    private final float[] meta = new float[MAX_SLICES * 4];

    private int textureId = -1;
    private boolean metaDirty = false;

    /** 1-based bake slice for a spec (0 = none). (Re)anchors on origin change. */
    public int ensure(EmitterSpec spec, Vec3 origin) {
        if (spec.collideMode == EmitterSpec.CollideMode.NONE || origin == null)
            return 0;
        if (textureId < 0 && !createTexture())
            return 0;

        int ax = floor(origin.x - CENTER_XZ);
        int ay = floor(origin.y - CENTER_Y);
        int az = floor(origin.z - CENTER_XZ);

        Slot s = slots.get(spec);
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
            s = new Slot(free);
            occupied[free] = true;
            slots.put(spec, s);
            metaDirty = true;
        }

        if (s.ax != ax || s.ay != ay || s.az != az) {
            s.ax = ax;
            s.ay = ay;
            s.az = az;
            s.dirty = true;
            s.lastBuilt = Long.MIN_VALUE;
            metaDirty = true;
        }
        return s.slice + 1;
    }

    /** Rebuilds dirty or stale slices. Call every frame on the render thread. */
    public void tick() {
        if (slots.isEmpty() || textureId < 0)
            return;
        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null)
            return;
        long now = mc.level.getGameTime();
        for (Slot s : slots.values()) {
            if (s.dirty || now - s.lastBuilt >= REBUILD_TICKS) {
                rebuild(mc, s);
            }
        }
    }

    /** Current bake-meta float array for the SSBO (origin.xyz + presence). */
    public float[] meta() {
        for (int i = 0; i < MAX_SLICES; i++) {
            int base = i * 4;
            Slot s = findSlot(i);
            if (s != null) {
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
        return metaDirty;
    }

    public void markMetaClean() {
        metaDirty = false;
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
        // LinkedHashMap accessOrder=true: iterator order = LRU → MRU.
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
        GL11.glTexParameteri(GL12.GL_TEXTURE_3D, GL12.GL_TEXTURE_WRAP_S, GL12.GL_CLAMP_TO_EDGE);
        GL11.glTexParameteri(GL12.GL_TEXTURE_3D, GL12.GL_TEXTURE_WRAP_T, GL12.GL_CLAMP_TO_EDGE);
        GL11.glTexParameteri(GL12.GL_TEXTURE_3D, GL12.GL_TEXTURE_WRAP_R, GL12.GL_CLAMP_TO_EDGE);
        ParticleGLUtil.prepareClientUpload(); // guard PBO / unpack-state leftovers
        GL12.glTexImage3D(GL12.GL_TEXTURE_3D, 0, GL30.GL_R8, SX, SY, SZ * MAX_SLICES, 0,
                GL11.GL_RED, GL11.GL_UNSIGNED_BYTE, (ByteBuffer) null);
        return true;
    }

    private void rebuild(Minecraft mc, Slot s) {
        BlockPos base = new BlockPos(s.ax, s.ay, s.az);
        byte[] voxels = new byte[SX * SY * SZ];
        for (int z = 0; z < SZ; z++) {
            for (int y = 0; y < SY; y++) {
                for (int x = 0; x < SX; x++) {
                    BlockPos p = base.offset(x, y, z);
                    boolean solid = !mc.level.getBlockState(p).isAir();
                    voxels[(z * SY + y) * SX + x] = solid ? SOLID : AIR;
                }
            }
        }
        ByteBuffer upload = BufferUtils.createByteBuffer(voxels.length);
        upload.put(voxels).flip();

        GL12.glBindTexture(GL12.GL_TEXTURE_3D, textureId);
        ParticleGLUtil.prepareClientUpload(); // guard PBO / unpack-state leftovers
        GL12.glTexSubImage3D(GL12.GL_TEXTURE_3D, 0, 0, 0, s.slice * SZ, SX, SY, SZ,
                GL11.GL_RED, GL11.GL_UNSIGNED_BYTE, upload);
        s.dirty = false;
        s.lastBuilt = mc.level.getGameTime();
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
