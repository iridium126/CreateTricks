package com.iridium126.createmanaindustry.client.dimension.render;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

import net.minecraft.client.Minecraft;
import net.minecraft.client.resources.model.BakedModel;
import net.minecraft.core.Direction;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.block.state.BlockState;

/**
 * BlockState → 16-bit render-state id table (doc §7.1 {@code AllvrRenderStateMap}).
 * <p>
 * V0 simplification: ids are assigned lazily at mesh time (not at resource
 * load) and carry only what the V0 forward pass consumes — the atlas sprite
 * rect of the state's block model (assumed a full cube: exactly one culled
 * quad per direction, the island generator only produces stone/dirt/grass),
 * the resolved biome tint, and a {@code renderable} flag. Non-full-cube
 * models (stairs, torches, …) and translucent states (water/glass) resolve to
 * a non-renderable entry — V0 leaves them unrendered (documented phase-3
 * deviation; the model-geometry path of §7.3 lands later).
 * <p>
 * Accessed from mesher worker threads; id assignment and table growth are
 * synchronized (lookups of existing states are unsynchronized map reads).
 * The float table is uploaded to the {@code AllvrBuffers} state TBO by the
 * render thread.
 */
public final class AllvrRenderStateMap {

    public static final short ID_AIR = 0;

    /** Per-id material: sprite rect (u0,v0,du,dv in atlas space), tint rgb, flags, half-texel inset. */
    public static final class Entry {
        public final float u0, v0, du, dv;
        public final float tintR, tintG, tintB;
        public final boolean renderable;
        /** Half-texel inset in TILE space (0.5/pxWidth, 0.5/pxHeight) — mip bleed guard. */
        public final float insetU, insetV;

        Entry(float u0, float v0, float du, float dv, float tr, float tg, float tb, boolean renderable,
              float insetU, float insetV) {
            this.u0 = u0;
            this.v0 = v0;
            this.du = du;
            this.dv = dv;
            this.tintR = tr;
            this.tintG = tg;
            this.tintB = tb;
            this.renderable = renderable;
            this.insetU = insetU;
            this.insetV = insetV;
        }
    }

    /** Lock-free reads from mesher workers; id assignment is atomic per state. */
    private static final Map<BlockState, Short> IDS = new ConcurrentHashMap<>();
    /** CopyOnWrite so worker {@link #entryOf} reads never race a resize. */
    private static final List<Entry> ENTRIES = new CopyOnWriteArrayList<>();

    static {
        ENTRIES.add(new Entry(0, 0, 0, 0, 1, 1, 1, false, 0, 0)); // id 0 = air
    }

    /** Id for a state, assigning one lazily. Thread-safe. */
    public static short idOf(BlockState state) {
        if (state.isAir()) {
            return ID_AIR;
        }
        Short boxed = IDS.get(state);
        if (boxed != null) {
            return boxed;
        }
        synchronized (AllvrRenderStateMap.class) {
            boxed = IDS.get(state);
            if (boxed != null) {
                return boxed;
            }
            short id = (short) ENTRIES.size();
            if (id >= Short.MAX_VALUE) {
                com.iridium126.createmanaindustry.CreateManaIndustry.LOGGER
                    .error("[Allvr] render state id space exhausted at {}", state);
                return ID_AIR;
            }
            IDS.put(state, id);
            ENTRIES.add(resolveEntry(state));
            return id;
        }
    }

    public static Entry entryOf(int id) {
        return ENTRIES.get(id);
    }

    public static int entryCount() {
        return ENTRIES.size();
    }

    /** Packed float table for the state TBO: 3 vec4 per id (uvRect, tint+flag, inset). */
    public static float[] packedTable() {
        float[] out = new float[ENTRIES.size() * 12];
        int i = 0;
        for (Entry e : ENTRIES) {
            out[i++] = e.u0;
            out[i++] = e.v0;
            out[i++] = e.du;
            out[i++] = e.dv;
            out[i++] = e.tintR;
            out[i++] = e.tintG;
            out[i++] = e.tintB;
            out[i++] = e.renderable ? 1.0f : 0.0f;
            out[i++] = e.insetU;
            out[i++] = e.insetV;
            out[i++] = 0.0f;
            out[i++] = 0.0f;
        }
        return out;
    }

    /**
     * Full-cube assumption: the block model must expose exactly one culled quad
     * per direction; its sprite becomes the state's texture. Anything else
     * (partial models, none, multiple) resolves to non-renderable.
     */
    private static Entry resolveEntry(BlockState state) {
        Minecraft mc = Minecraft.getInstance();
        BakedModel model = mc.getBlockRenderer().getBlockModelShaper().getBlockModel(state);
        if (model == null) {
            return new Entry(0, 0, 0, 0, 1, 1, 1, false, 0, 0);
        }
        RandomSource rand = RandomSource.create();
        float u0 = 0, v0 = 0, du = 0, dv = 0, insetU = 0, insetV = 0;
        int tint = -1;
        boolean tinted = false;
        for (Direction dir : Direction.values()) {
            List<net.minecraft.client.renderer.block.model.BakedQuad> quads = model.getQuads(state, dir, rand);
            if (quads.size() != 1) {
                return new Entry(0, 0, 0, 0, 1, 1, 1, false, 0, 0);
            }
            net.minecraft.client.renderer.block.model.BakedQuad quad = quads.get(0);
            var sprite = quad.getSprite();
            u0 = sprite.getU0();
            v0 = sprite.getV0();
            du = sprite.getU1() - sprite.getU0();
            dv = sprite.getV1() - sprite.getV0();
            int pxW = sprite.contents().width();
            int pxH = sprite.contents().height();
            insetU = 0.5f / Math.max(1, pxW);
            insetV = 0.5f / Math.max(1, pxH);
            if (quad.isTinted()) {
                tinted = true;
                tint = quad.getTintIndex();
            }
        }
        float tr = 1, tg = 1, tb = 1;
        if (tinted) {
            // null level/pos → the colorer's documented default branch
            int rgb = mc.getBlockColors().getColor(state, null, null, tint);
            tr = ((rgb >> 16) & 0xFF) / 255.0f;
            tg = ((rgb >> 8) & 0xFF) / 255.0f;
            tb = (rgb & 0xFF) / 255.0f;
        }
        return new Entry(u0, v0, du, dv, tr, tg, tb, true, insetU, insetV);
    }

    private AllvrRenderStateMap() {}
}
