package com.iridium126.createmanaindustry.client.particles.engine;

import com.iridium126.createmanaindustry.CreateManaIndustry;

import org.joml.Matrix4f;
import org.joml.Quaternionf;

/**
 * Static bake of the vanilla held item (the {@code item/handheld} parent model
 * shape — {@code ItemModelGenerator.processFrames}' output) for MODEL particles
 * carrying a held item ({@code EmitterSpec.HeldItem}, header 17.z, and the
 * dive-wave sword tier uniform). The six vanilla sword textures are COPIED into
 * mod assets ({@code textures/particle/sword_*.png}) and packed as frames 1..6
 * of the MODEL atlas — self-owned like the allay body, so resource packs can
 * neither restyle nor re-resolution this path.
 *
 * <p>Geometry — a verbatim port of vanilla {@code ItemModelGenerator}
 * (reference: .refs/neoforge-21.1.227 .../renderer/block/model/ItemModelGenerator.java):
 * the full-quad pair spanning {@code (0,0,7.5) .. (16,16,8.5)} in pixel units
 * (front SOUTH face at z = 8.5/16, back NORTH face at z = 7.5/16, 1 px apart)
 * plus the silhouette SIDE SHELLS the old bake omitted. The side shell is what
 * makes the sword read as a solid slab edge-on: vanilla scans the sprite for
 * every opaque pixel whose neighbour (up/down/left/right) is transparent — the
 * 1-px exposed boundary — merges same-facing transitions that share an anchor
 * line (a row for UP/DOWN, a column for LEFT/RIGHT) into one SPAN even across
 * gaps (the gap texels are transparent and the cutout discards them, so one
 * quad per span is fragment-identical and vertex-minimal: each (plane, facing)
 * pair's run union is always a single interval, and spans on different planes
 * or opposite facings can never merge — a quad has one UV set), then emits one
 * quad per span on the boundary plane sampling the boundary texels. The
 * NeoForge {@code fixItemModelSeams} patch is deliberately NOT ported: it
 * re-aligns side quads against the atlas sprite-expansion insets the vanilla
 * pipeline applies to the front/back UVs, and this engine's self-hosted
 * exact-pixel atlas has no such insets.
 *
 * <p>All six sword sprites share the same silhouette (vanilla palette swaps),
 * so ONE geometry serves every tier — the same shared-silhouette bet the
 * front/back pair has always made, now simply more visible. Spans are computed
 * at bake time from the tier-1 sprite itself ({@code sword_wooden.png}, read
 off the CLASSPATH so the geometry clinit never depends on Minecraft state);
 * if the read fails the bake falls back to the bare quad pair with a warning.
 * Vertices carry CANONICAL sprite UVs ([0,1], u = item x, v = 1 - item y) and
 * the vertex shader remaps them into the carrier's atlas frame through
 * {@link #uvTable()} — a single instanced draw command cannot pick an element
 * range per instance, so the tier lives in the UV remap, not the geometry.
 * Side quads sample the boundary pixel runs, so the canonical UVs ride the
 * same per-tier remap unchanged.
 *
 * <p>Vertex record: pos.xyz in ITEM-MODEL space ([0,1], NOT 1/16 units — the
 * display transform maps it into the hand's model frame), canonical uv.xy,
 * partId 7 (the carrier cutout segment; {@code vSeg} selects translucent parts
 * by the explicit id set {4,5,6}), face normal axis id.
 */
final class HeldItemGeometry {

    /** MODEL atlas cells: frame 0 = allay body, frames 1..6 = the tiers in
     * {@link com.iridium126.createmanaindustry.client.particles.emitter.EmitterSpec.HeldItem}
     * order (wooden, stone, golden, iron, diamond, netherite). 4x2 of 32x32
     * cells = 128x64; the 16x16 sword sprites nearest-upscale 2x to fill their
     * cells (the atlas builder's small-frame rule), keeping frame 0's texels —
     * and therefore the allay body's atlas-space UVs — at their original
     * positions. */
    public static final int ATLAS_COLS = 4;
    public static final int ATLAS_ROWS = 2;
    public static final int ATLAS_CELL = 32;

    /** The silhouette source: the first sword tier, whose PNG defines the
     * shared side-shell silhouette (see the class javadoc). */
    private static final String SILHOUETTE_SPRITE = "sword_wooden";

    /** z of the front (SOUTH, +Z) face in item-model units: 8.5/16. */
    private static final float Z_FRONT = 8.5f / 16f;
    /** z of the back (NORTH, -Z) face: 7.5/16 (vanilla's 1 px item thickness). */
    private static final float Z_BACK = 7.5f / 16f;

    private static final float[] UV_TABLE = buildUvTable();
    private static final String DISPLAY_GLSL = computeDisplayMatrixGLSL();

    private HeldItemGeometry() {
    }

    /**
     * Appends the held-item quads to the MODEL geometry bake. Emitted into the
     * OPAQUE-class segments (called between {@code BODY_OPAQUE_INDEX_COUNT} and
     * {@code OPAQUE_INDEX_COUNT} capture in {@link AllayModelGeometry}'s static
     * init — the sword is alpha-cutout, it must never land in the translucent
     * sub-draw's index range).
     */
    static void appendQuads(java.util.List<Float> out, java.util.List<Integer> idx) {
        // front face (SOUTH, +Z): ring (0,0),(1,0),(1,1),(0,1) in item x/y,
        // canonical uv (x, 1-y) — the sprite reads unmirrored from +Z
        quad(out, idx,
                new float[][] {
                        { 0f, 0f, Z_FRONT, 0f, 1f },
                        { 1f, 0f, Z_FRONT, 1f, 1f },
                        { 1f, 1f, Z_FRONT, 1f, 0f },
                        { 0f, 1f, Z_FRONT, 0f, 0f },
                }, AllayModelGeometry.AXIS_SOUTH);
        // back face (NORTH, -Z): ring (1,0),(0,0),(0,1),(1,1) (CCW seen from
        // -Z — cull is on), canonical uv (x, 1-y): the sprite reads MIRRORED
        // from behind, exactly like a vanilla generated item's back face
        quad(out, idx,
                new float[][] {
                        { 1f, 0f, Z_BACK, 1f, 1f },
                        { 0f, 0f, Z_BACK, 0f, 1f },
                        { 0f, 1f, Z_BACK, 0f, 0f },
                        { 1f, 1f, Z_BACK, 1f, 0f },
                }, AllayModelGeometry.AXIS_NORTH);
        appendSideShells(out, idx);
    }

    // ------------------------------------------------------------------
    // Vanilla silhouette side shells (ItemModelGenerator port)
    // ------------------------------------------------------------------

    /** Span facings, vanilla {@code SpanFacing} semantics. */
    private static final int UP = 0, DOWN = 1, LEFT = 2, RIGHT = 3;

    /** One vanilla {@code Span}: transitions of one facing merged along one
     * anchor line ({@code anchor} = the pixel row for UP/DOWN, pixel column
     * for LEFT/RIGHT; {@code min/max} bracket the run coordinate, INCLUSIVE). */
    private static final class Span {
        final int facing;
        final int anchor;
        int min;
        int max;

        Span(int facing, int run, int anchor) {
            this.facing = facing;
            this.min = run;
            this.max = run;
            this.anchor = anchor;
        }

        void expand(int pos) {
            if (pos < this.min)
                this.min = pos;
            else if (pos > this.max)
                this.max = pos;
        }
    }

    /**
     * Emits one quad per silhouette span. Same program order as vanilla
     * {@code createSideElements} (spans arrive grouped by facing); the pixel
     * scale generalises the vanilla constants ({@code 16/width},
     * {@code 16/height}) so a non-16x sprite still bakes correctly.
     */
    private static void appendSideShells(java.util.List<Float> out, java.util.List<Integer> idx) {
        int[] alpha = readSpriteAlpha(SILHOUETTE_SPRITE);
        if (alpha == null)
            return; // sprite unreadable: the flat pair above still renders (warned)
        int w = alpha[0];
        int h = alpha[1];
        float px = 16f / w; // pixel -> item-model unit scale (vanilla f10/f11)
        float py = 16f / h;

        for (Span s : silhouetteSpans(alpha, w, h)) {
            // vanilla's per-facing corner table: the span quad in pixel units
            // (uv1/uv2 = texture-space rect the side samples; the boundary
            // texels — the OPAQUE pixel's own column/row — so the edge shows
            // the sword's edge colours)
            float u1, v1, u2, v2; // texture-space uv rect (pixel units)
            switch (s.facing) {
                case UP -> {
                    u1 = s.min * px;
                    u2 = (s.max + 1) * px;
                    v1 = s.anchor * py;
                    v2 = (s.anchor + 1) * py;
                    // plane at the TOP of pixel row `anchor` (model y = 16 - a),
                    // normal +Y
                    float y = 16f - s.anchor * py;
                    sideQuad(out, idx, AllayModelGeometry.AXIS_UP, y, u1, u2, v1, v2, true);
                }
                case DOWN -> {
                    u1 = s.min * px;
                    u2 = (s.max + 1) * px;
                    v1 = s.anchor * py;
                    v2 = (s.anchor + 1) * py;
                    // plane at the BOTTOM of pixel row `anchor` (model y =
                    // 16 - (a+1)), normal -Y
                    float y = 16f - (s.anchor + 1) * py;
                    sideQuad(out, idx, AllayModelGeometry.AXIS_DOWN, y, u1, u2, v1, v2, true);
                }
                case LEFT -> {
                    u1 = s.anchor * px;
                    u2 = (s.anchor + 1) * px;
                    v1 = s.min * py;
                    v2 = (s.max + 1) * py;
                    // plane at the LEFT edge of pixel column `anchor` (model
                    // x = a), normal -X — visible from over the transparent
                    // neighbour (vanilla declares EAST for a zero-width
                    // element; the visible face is the one facing AWAY from
                    // the opaque pixel)
                    float x = s.anchor * px;
                    sideQuad(out, idx, AllayModelGeometry.AXIS_WEST, x, u1, u2, v1, v2, false);
                }
                default -> { // RIGHT
                    u1 = s.anchor * px;
                    u2 = (s.anchor + 1) * px;
                    v1 = s.min * py;
                    v2 = (s.max + 1) * py;
                    // plane at the RIGHT edge of pixel column `anchor` (model
                    // x = a+1), normal +X
                    float x = (s.anchor + 1) * px;
                    sideQuad(out, idx, AllayModelGeometry.AXIS_EAST, x, u1, u2, v1, v2, false);
                }
            }
        }
    }

    /**
     * One side quad: a 1-px wall at the given model-x or model-y plane
     * ({@code horizontal} = the plane is a y constant, else an x constant),
     * spanning the run extent, z from {@link #Z_BACK} to {@link #Z_FRONT}.
     *
     * <p>UV CONVENTION (the first bake conflated two mirror operations and
     * sampled the vertically MIRRORED sprite — tip walls showed hilt texels):
     * canonical uv = RAW SPRITE COORDS / 16, i.e. u = pixel column/16 (u=0 =
     * sprite LEFT) and v = pixel ROW/16 (v=0 = sprite TOP). That is the same
     * convention the front quad's canonical uv (x, 1-item-y) already encodes —
     * 1 - item-y equals the sprite row because model y is the sprite row
     * flipped — but it is NOT "1 - sprite row". Do not "simplify" one into the
     * other.
     *
     * <p>The per-face front/back (horizontal walls) and front-edge (vertical
     * walls) assignments below reproduce vanilla FaceBakery's rendered result
     * corner for corner (BlockFaceUV slots through FaceInfo, including the
     * LEFT/RIGHT zero-width elements' MIN/MAX y swap and re-winding): UP walls
     * sample raw v [anchor, anchor+1] with the row's BOTTOM at z = Z_FRONT,
     * DOWN walls the row's TOP at z = Z_FRONT, LEFT walls raw u = anchor at
     * z = Z_FRONT, RIGHT walls u = anchor+1 at z = Z_FRONT.
     *
     * <p>Winding rings are lifted from {@link AllayModelGeometry#cube}'s
     * per-face vertex orders (they render with outward normals under the
     * engine's CCW cull); {@code runStart < runEnd} in MODEL units.
     */
    private static void sideQuad(java.util.List<Float> out, java.util.List<Integer> idx,
            int normalAxis, float plane, float uv1a, float uv2a, float uv1b, float uv2b,
            boolean horizontal) {
        // run extent along the wall, + the uv pair across it. The run ENDS are
        // pixel-unit values ([0,16]) and must be scaled into item-model space
        // ([0,1]) like the plane — the first bake used them raw, stretching
        // every side wall to N x the sword's length (N = run width in px).
        float run0, run1;
        float runUv0, runUv1; // canonical uv of the run ends
        if (horizontal) {
            run0 = uv1a / 16f;   // model x of run start (u = min px)
            run1 = uv2a / 16f;   // model x of run end (u = max+1 px)
            runUv0 = uv1a / 16f;
            runUv1 = uv2a / 16f;
        } else {
            run0 = (16f - uv2b) / 16f; // model y of the run's bottom (texture row max+1)
            run1 = (16f - uv1b) / 16f; // model y of the run's top (texture row min)
            runUv0 = uv2b / 16f;      // canonical v of the run's bottom (row max+1)
            runUv1 = uv1b / 16f;      // canonical v of the run's top (row min)
        }
        // canonical uv of the two z ends along the thickness
        float frontA, backA; // canonical (u or v) at z = Z_FRONT / Z_BACK
        if (horizontal) {
            // v across the thickness: vanilla puts the boundary row's BOTTOM
            // (anchor+1) at the front edge of UP walls and its TOP (anchor) at
            // the front edge of DOWN walls
            frontA = (normalAxis == AllayModelGeometry.AXIS_UP ? uv2b : uv1b) / 16f;
            backA = (normalAxis == AllayModelGeometry.AXIS_UP ? uv1b : uv2b) / 16f;
        } else {
            // u across the thickness: front edge at texture u1 (LEFT) / u2 (RIGHT)
            frontA = (normalAxis == AllayModelGeometry.AXIS_WEST ? uv1a : uv2a) / 16f;
            backA = (normalAxis == AllayModelGeometry.AXIS_WEST ? uv2a : uv1a) / 16f;
        }
        float planeC = plane / 16f; // item-model space is [0,1]

        float[][] verts;
        if (normalAxis == AllayModelGeometry.AXIS_UP) {
            // ring (x1,z0),(x0,z0),(x0,z1),(x1,z1)
            verts = new float[][] {
                    { run1, planeC, Z_BACK, runUv1, backA },
                    { run0, planeC, Z_BACK, runUv0, backA },
                    { run0, planeC, Z_FRONT, runUv0, frontA },
                    { run1, planeC, Z_FRONT, runUv1, frontA },
            };
        } else if (normalAxis == AllayModelGeometry.AXIS_DOWN) {
            // ring (x1,z1),(x0,z1),(x0,z0),(x1,z0)
            verts = new float[][] {
                    { run1, planeC, Z_FRONT, runUv1, frontA },
                    { run0, planeC, Z_FRONT, runUv0, frontA },
                    { run0, planeC, Z_BACK, runUv0, backA },
                    { run1, planeC, Z_BACK, runUv1, backA },
            };
        } else if (normalAxis == AllayModelGeometry.AXIS_WEST) {
            // ring (y0,z0),(y0,z1),(y1,z1),(y1,z0); u across z, v along y
            verts = new float[][] {
                    { planeC, run0, Z_BACK, backA, runUv0 },
                    { planeC, run0, Z_FRONT, frontA, runUv0 },
                    { planeC, run1, Z_FRONT, frontA, runUv1 },
                    { planeC, run1, Z_BACK, backA, runUv1 },
            };
        } else { // AXIS_EAST
            // ring (y0,z1),(y0,z0),(y1,z0),(y1,z1)
            verts = new float[][] {
                    { planeC, run0, Z_FRONT, frontA, runUv0 },
                    { planeC, run0, Z_BACK, backA, runUv0 },
                    { planeC, run1, Z_BACK, backA, runUv1 },
                    { planeC, run1, Z_FRONT, frontA, runUv1 },
            };
        }
        quad(out, idx, verts, normalAxis);
    }

    /**
     * Vanilla {@code getSpans}: one pass over the sprite, merging transitions
     * per (facing, anchor line) — including across gaps on the same line, the
     * vanilla merge whose gap texels the cutout discards. A transition fires
     * for an OPAQUE pixel whose neighbour in the facing direction is
     * transparent (out of bounds reads as transparent, like vanilla).
     */
    private static java.util.List<Span> silhouetteSpans(int[] alpha, int w, int h) {
        java.util.List<Span> spans = new java.util.ArrayList<>();
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                if (alpha[2 + x + y * w] == 0)
                    continue; // vanilla only ever tests transitions FROM opaque pixels
                if (transparentAt(alpha, w, h, x, y - 1))
                    createOrExpand(spans, UP, x, y);
                if (transparentAt(alpha, w, h, x, y + 1))
                    createOrExpand(spans, DOWN, x, y);
                if (transparentAt(alpha, w, h, x - 1, y))
                    createOrExpand(spans, LEFT, x, y);
                if (transparentAt(alpha, w, h, x + 1, y))
                    createOrExpand(spans, RIGHT, x, y);
            }
        }
        return spans;
    }

    /** Vanilla {@code createOrExpandSpan}: merge into an existing span of the
     * same facing sharing the anchor line, else open a new one. */
    private static void createOrExpand(java.util.List<Span> spans, int facing, int x, int y) {
        boolean horizontal = facing == UP || facing == DOWN;
        int anchor = horizontal ? y : x;
        int run = horizontal ? x : y;
        for (Span s : spans) {
            if (s.facing == facing && s.anchor == anchor) {
                s.expand(run);
                return;
            }
        }
        spans.add(new Span(facing, run, anchor));
    }

    /** Vanilla {@code isTransparent}: out of bounds reads as transparent. */
    private static boolean transparentAt(int[] alpha, int w, int h, int x, int y) {
        if (x < 0 || y < 0 || x >= w || y >= h)
            return true;
        return alpha[2 + x + y * w] == 0;
    }

    /**
     * Decodes one mod-owned sprite PNG off the CLASSPATH into
     * {@code [width, height, alpha...]}. The classpath (not the ResourceManager)
     * is deliberate: this runs inside {@link AllayModelGeometry}'s static init,
     * before any Minecraft wiring can be assumed, and the sprite is self-owned
     * (resource packs cannot restyle this path). Alpha uses the vanilla
     * predicate ({@code getPixelRGBA >> 24 & 0xFF == 0} — SpriteContents.
     * isTransparent). {@code null} on any failure (the caller falls back to
     * the bare quad pair).
     */
    private static int[] readSpriteAlpha(String sprite) {
        String path = "/assets/createmanaindustry/textures/particle/" + sprite + ".png";
        try (java.io.InputStream in = HeldItemGeometry.class.getResourceAsStream(path)) {
            if (in == null) {
                CreateManaIndustry.LOGGER.warn(
                        "[CMI particles] held-item silhouette sprite missing: {} — side shells skipped", path);
                return null;
            }
            com.mojang.blaze3d.platform.NativeImage img =
                    com.mojang.blaze3d.platform.NativeImage.read(in);
            try {
                int w = img.getWidth();
                int h = img.getHeight();
                int[] out = new int[2 + w * h];
                out[0] = w;
                out[1] = h;
                for (int y = 0; y < h; y++)
                    for (int x = 0; x < w; x++)
                        out[2 + x + y * w] = (img.getPixelRGBA(x, y) >>> 24) & 0xFF;
                return out;
            } finally {
                img.close();
            }
        } catch (Exception e) {
            CreateManaIndustry.LOGGER.warn(
                    "[CMI particles] cannot read held-item silhouette {}: side shells skipped", path, e);
            return null;
        }
    }

    /** One quad: four unique vertices + two index triangles (single winding). */
    private static void quad(java.util.List<Float> out, java.util.List<Integer> idx,
            float[][] verts, int normalAxis) {
        int base = out.size() / AllayModelGeometry.VERTEX_FLOATS;
        for (float[] v : verts) {
            out.add(v[0]);
            out.add(v[1]);
            out.add(v[2]);
            out.add(v[3]); // canonical u
            out.add(v[4]); // canonical v
            out.add(7f);   // partId 7 = held item
            out.add((float) normalAxis);
        }
        idx.add(base);
        idx.add(base + 1);
        idx.add(base + 2);
        idx.add(base);
        idx.add(base + 2);
        idx.add(base + 3);
    }

    /**
     * Per-tier atlas UV rects for the vertex-shader remap
     * {@code uv = mix(uvMin, uvMax, canonical)} — {@code uHeldItemUV[t]} with
     * t the held-item id (1..6; slot 0 unused). Rects cover the tier's FULL
     * atlas cell (the small-frame fill-upscale maps the whole cell per frame).
     */
    public static float[] uvTable() {
        return UV_TABLE;
    }

    private static float[] buildUvTable() {
        float[] t = new float[7 * 4];
        float cw = (float) ATLAS_CELL / (ATLAS_COLS * ATLAS_CELL);
        float ch = (float) ATLAS_CELL / (ATLAS_ROWS * ATLAS_CELL);
        for (int tier = 1; tier <= 6; tier++) {
            int col = tier % ATLAS_COLS;
            int row = tier / ATLAS_COLS;
            t[tier * 4 + 0] = col * cw;
            t[tier * 4 + 1] = row * ch;
            t[tier * 4 + 2] = (col + 1) * cw;
            t[tier * 4 + 3] = (row + 1) * ch;
        }
        return t;
    }

    /**
     * The vanilla handheld THIRD_PERSON_RIGHT_HAND display transform +
     * {@code ItemRenderer.render}'s {@code translate(-0.5)} as ONE constant
     * mat4 (item-model space in, hand-frame out). Values are the frozen
     * {@code item/handheld.json} thirdperson_righthand block (rotation
     * [0, -90, 55], translation [0, 4.0, 0.5] scaled by the deserializer's
     * 1/16, scale 0.85 — rightRotation absent, identity). Computed once with
     * JOML replicating the vanilla PoseStack call order verbatim (scale(16)
     * → translate → mulPose(rotationXYZ) → scale(0.85) → translate(-0.5)), so
     * the quaternion composition semantics are exactly vanilla's — do not
     * re-derive this by hand. The leading {@code scale(16)} converts the
     * vanilla chain's BLOCK-unit layer posestack into this engine's RAW model
     * units (the /16 happens once at the call site) — without it the item
     * would render 16x too small. Emitted into the shaders as
     * {@code CMI_HELD_DISPLAY} (a plain const in the shader-pack merged
     * source, where #defines do not survive the AST transplant).
     */
    public static String displayMatrixGLSL() {
        return DISPLAY_GLSL;
    }

    private static String computeDisplayMatrixGLSL() {
        Matrix4f m = new Matrix4f();
        m.scale(16f, 16f, 16f); // vanilla BLOCK units -> engine RAW model units
        m.translate(0f, 4.0f * 0.0625f, 0.5f * 0.0625f);
        m.rotate(new Quaternionf().rotationXYZ(
                0f, (float) Math.toRadians(-90.0), (float) Math.toRadians(55.0)));
        m.scale(0.85f, 0.85f, 0.85f);
        m.translate(-0.5f, -0.5f, -0.5f);
        float[] c = new float[16];
        m.get(c); // column-major, the GLSL mat4 constructor's order
        StringBuilder sb = new StringBuilder("mat4(");
        for (int i = 0; i < 16; i++) {
            if (i > 0)
                sb.append(", ");
            sb.append(String.format(java.util.Locale.ROOT, "%.9g", c[i]));
        }
        return sb.append(")").toString();
    }
}
