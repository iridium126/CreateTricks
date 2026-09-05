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
 * load) and carry what the V0 forward pass consumes — the atlas sprite rect of
 * EACH FACE of the state's block model (assumed a full cube: at least one
 * culled quad per direction, the island generator only produces
 * stone/dirt/grass), the resolved per-face biome tint, and a {@code renderable}
 * flag. Per face the FIRST culled quad is used — tinted overlay layers (e.g.
 * the grass block's side overlay, whose second quad is what vanilla tints) are
 * dropped, so the grass side renders with its untinted base texture while its
 * top face keeps the tinted grass_block_top sprite. Untinted faces carry a
 * white tint (identity multiply). Non-full-cube models (stairs, torches, …)
 * and translucent states (water/glass) resolve to a non-renderable entry — V0
 * leaves them unrendered (documented phase-3 deviation; the model-geometry path
 * of §7.3 lands later).
 * <p>
 * Face order is {@link AllvrMesher#FACES} ({@code axis*2 + dir}, dir 0 =
 * positive axis) — the same index the vertex shader derives and uses to fetch
 * this table, so a multi-texture block (grass: dirt bottom / grass top /
 * grass side) textures every face with its own sprite.
 * <p>
 * Accessed from mesher worker threads; id assignment and table growth are
 * synchronized (lookups of existing states are unsynchronized map reads).
 * The float table is uploaded to the {@code AllvrBuffers} state TBO by the
 * render thread: {@link #TEXELS_PER_ENTRY} texels per id
 * (6 faces × uvRect / tint+flag / half-texel inset).
 */
public final class AllvrRenderStateMap {

    public static final short ID_AIR = 0;

    /** Texture-buffer layout: per face (uvRect, tint rgb + renderable flag, inset). */
    public static final int FACES = 6;
    public static final int TEXELS_PER_FACE = 3;
    public static final int TEXELS_PER_ENTRY = FACES * TEXELS_PER_FACE;

    /** One face's material: sprite rect (atlas space), tint rgb, half-texel inset (tile space). */
    private record FaceMaterial(float u0, float v0, float du, float dv,
            float tintR, float tintG, float tintB, float insetU, float insetV) {}

    /** Per-id material: per-face {@link FaceMaterial} (FACES order) + renderable flag. */
    public static final class Entry {
        public final FaceMaterial[] faces;
        public final boolean renderable;

        Entry(FaceMaterial[] faces, boolean renderable) {
            this.faces = faces;
            this.renderable = renderable;
        }
    }

    /** Lock-free reads from mesher workers; id assignment is atomic per state. */
    private static final Map<BlockState, Short> IDS = new ConcurrentHashMap<>();
    /** CopyOnWrite so worker {@link #entryOf} reads never race a resize. */
    private static final List<Entry> ENTRIES = new CopyOnWriteArrayList<>();
    /** Parallel to {@link #ENTRIES}: the state behind each id (null for air),
     *  feeding the iris customId resolution (grilling decision ⑦). */
    private static final List<BlockState> STATES = new CopyOnWriteArrayList<>();

    /**
     * Per-id iris block-material ids (the pack patch's {@code customId} —
     * Photon derives its material mask from {@code customId − 10000}). Written
     * render-thread only by {@link #setCustomIds}; read by
     * {@link #packedTable} for the spare texel of each face's inset entry.
     * {@link #customIdRevision} bumps on every change so the renderer can
     * re-upload the TBO lazily.
     */
    private static volatile int[] customIds = new int[] {0};
    private static volatile int customIdRevision = 0;

    private static final Entry NON_RENDERABLE = new Entry(zeroFaces(), false);

    private static FaceMaterial[] zeroFaces() {
        FaceMaterial[] faces = new FaceMaterial[FACES];
        for (int i = 0; i < FACES; i++) {
            faces[i] = new FaceMaterial(0, 0, 0, 0, 1, 1, 1, 0, 0);
        }
        return faces;
    }

    static {
        ENTRIES.add(NON_RENDERABLE); // id 0 = air
        STATES.add(null);
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
            STATES.add(state);
            // the fresh entry has no resolved customId yet — the renderer's
            // revision check re-runs setCustomIds and re-uploads the TBO
            customIdRevision++;
            return id;
        }
    }

    /**
     * Re-resolves the customId column against {@code ids} (iris's
     * {@code WorldRenderingSettings.getBlockStateIds()}, live read by the
     * pipeline data). Returns the new revision. Full re-resolve on every call
     * — pack switches re-run it wholesale, zero re-mesh (grilling decision ⑦).
     */
    public static int setCustomIds(it.unimi.dsi.fastutil.objects.Object2IntMap<BlockState> ids) {
        int n = ENTRIES.size();
        int[] out = new int[n];
        if (ids != null) {
            for (int i = 0; i < n && i < STATES.size(); i++) {
                BlockState state = STATES.get(i);
                out[i] = state == null ? 0 : ids.getOrDefault(state, 0);
            }
        }
        customIds = out;
        return ++customIdRevision;
    }

    /** Revision of the customId column — the renderer re-uploads when it moves. */
    public static int customIdRevision() {
        return customIdRevision;
    }

    public static Entry entryOf(int id) {
        return ENTRIES.get(id);
    }

    public static int entryCount() {
        return ENTRIES.size();
    }

    /** Packed float table for the state TBO: TEXELS_PER_FACE vec4 per face × 6 faces per id.
     *  The inset texel's z component carries the iris customId (spare texel, grilling
     *  decision ⑦); w stays spare. */
    public static float[] packedTable() {
        float[] out = new float[ENTRIES.size() * TEXELS_PER_ENTRY * 4];
        int[] ids = customIds;
        int i = 0;
        for (int e = 0; e < ENTRIES.size(); e++) {
            Entry entry = ENTRIES.get(e);
            int customId = e < ids.length ? ids[e] : 0;
            for (FaceMaterial f : entry.faces) {
                out[i++] = f.u0();
                out[i++] = f.v0();
                out[i++] = f.du();
                out[i++] = f.dv();
                out[i++] = f.tintR();
                out[i++] = f.tintG();
                out[i++] = f.tintB();
                out[i++] = entry.renderable ? 1.0f : 0.0f;
                out[i++] = f.insetU();
                out[i++] = f.insetV();
                out[i++] = (float) customId;
                out[i++] = 0.0f;
            }
        }
        return out;
    }

    /**
     * Full-cube assumption: every direction must expose at least one culled
     * quad; per face the first quad is used (the base face — tinted overlays
     * come later in the list and are dropped). Anything else (missing face,
     * partial models, null) resolves to non-renderable.
     */
    private static Entry resolveEntry(BlockState state) {
        Minecraft mc = Minecraft.getInstance();
        BakedModel model = mc.getBlockRenderer().getBlockModelShaper().getBlockModel(state);
        if (model == null) {
            return NON_RENDERABLE;
        }
        RandomSource rand = RandomSource.create();
        FaceMaterial[] faces = new FaceMaterial[FACES];
        for (int i = 0; i < FACES; i++) {
            List<net.minecraft.client.renderer.block.model.BakedQuad> quads =
                model.getQuads(state, AllvrMesher.FACES[i], rand);
            if (quads.isEmpty()) {
                return NON_RENDERABLE;
            }
            net.minecraft.client.renderer.block.model.BakedQuad quad = quads.get(0);
            var sprite = quad.getSprite();
            float tr = 1, tg = 1, tb = 1;
            if (quad.isTinted()) {
                // null level/pos → the colorer's documented default branch
                int rgb = mc.getBlockColors().getColor(state, null, null, quad.getTintIndex());
                tr = ((rgb >> 16) & 0xFF) / 255.0f;
                tg = ((rgb >> 8) & 0xFF) / 255.0f;
                tb = (rgb & 0xFF) / 255.0f;
            }
            faces[i] = new FaceMaterial(
                sprite.getU0(), sprite.getV0(),
                sprite.getU1() - sprite.getU0(), sprite.getV1() - sprite.getV0(),
                tr, tg, tb,
                0.5f / Math.max(1, sprite.contents().width()),
                0.5f / Math.max(1, sprite.contents().height()));
        }
        return new Entry(faces, true);
    }

    private AllvrRenderStateMap() {}
}
