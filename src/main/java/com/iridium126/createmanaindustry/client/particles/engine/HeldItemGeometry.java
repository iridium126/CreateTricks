package com.iridium126.createmanaindustry.client.particles.engine;

import org.joml.Matrix4f;
import org.joml.Quaternionf;

/**
 * Static bake of the vanilla FLAT held item (the {@code item/handheld}
 * parent model shape — one sprite rendered as a front/back quad pair) for
 * MODEL particles carrying a held item ({@code EmitterSpec.HeldItem}, header
 * 17.z, and the dive-wave sword tier uniform). The six vanilla sword textures
 * are COPIED into mod assets ({@code textures/particle/sword_*.png}) and
 * packed as frames 1..6 of the MODEL atlas — self-owned like the allay body,
 * so resource packs can neither restyle nor re-resolution this path.
 * <p>
 * Geometry: vanilla {@code ItemModelGenerator.processFrames} emits ONE full
 * element spanning {@code (0,0,7.5) .. (16,16,8.5)} in pixel units (front
 * SOUTH face at z = 8.5/16, back NORTH face at z = 7.5/16, 1 px apart) with
 * per-pixel-run side shells for overdraw; a full-quad pair + alpha cutout is
 * fragment-identical for the sprite (transparent texels discard) at a fixed
 * 8-vertex cost. All six sword sprites share the same silhouette, so ONE
 * geometry serves every tier: vertices carry CANONICAL sprite UVs ([0,1],
 * u = item x, v = 1 - item y) and the vertex shader remaps them into the
 * carrier's atlas frame through {@link #uvTable()} — a single instanced draw
 * command cannot pick an element range per instance, so the tier lives in the
 * UV remap, not the geometry.
 * <p>
 * Vertex record: pos.xyz in ITEM-MODEL space ([0,1], NOT 1/16 units — the
 * display transform maps it into the hand's model frame), canonical uv.xy,
 * partId 7 (the opaque cutout segment; {@code vSeg} selects translucent
 * parts by the explicit id set {4,5,6}), face normal axis id.
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
     * OPAQUE segment (called before {@code OPAQUE_INDEX_COUNT} is captured in
     * {@link AllayModelGeometry}'s static init — the sword is alpha-cutout, it
     * must never land in the translucent sub-draw's index range).
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
