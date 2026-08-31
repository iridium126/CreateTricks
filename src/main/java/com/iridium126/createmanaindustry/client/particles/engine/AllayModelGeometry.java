package com.iridium126.createmanaindustry.client.particles.engine;

import java.util.ArrayList;
import java.util.List;

/**
 * Static vertex bake of the vanilla allay entity model ({@code AllayModel.
 * createBodyLayer()}) for MODEL particles, emitted as an INDEXED mesh: 4
 * unique vertices per quad plus 6 triangle indices (the wings additionally
 * carry a second, reversed index set so the zero-thickness planes stay
 * double-sided under backface culling — same vertices, no vertex cost).
 * Each vertex is 7 floats — {@code pos.xyz} (model units, 1/16 block),
 * {@code uv} (normalised against the 32x32 texture), {@code partId} and the
 * face normal axis id (+X,-X,+Y,-Y,+Z,-Z = 0..5, LUT-decoded in model.vsh) —
 * consumed by {@code model.vsh} (SSBO vertex pulling through the element
 * buffer). Faces use the exact per-face UV rects and vertex order of vanilla
 * {@code ModelPart.Cube}; degenerate faces (zero-area sides) are skipped.
 * Some faces are culled at bake time because their UV rects in
 * {@code allay_0.png} are verified ALL fully transparent -- every fragment
 * they produce is discarded anyway: the cloak's TOP/BOTTOM caps (x[2,8) x
 * y[16,18)) and each wing's EAST face (x[24,32) x y[19,24); only the wing's
 * WEST rect x[16,24) carries the art). Emitting them would cost 8 double-wound
 * cap triangles plus 4+4 wing triangles per instance, and under shader packs
 * whose entity alpha test is looser than GREATER 0.1 they form an INVISIBLE
 * DEPTH-OCCLUDING SHELL (the ghost segment writes depth). If the texture is
 * ever replaced, re-verify these UV rects before re-enabling those faces.
 * <p>
 * The mesh is split into three contiguous index ranges so the model renders as
 * three sub-draws: the body-opaque segment (head, skin, arms) and the held
 * item's CARRIER segment keep the cutout + depth-write path (the carrier
 * segment is drawn with its own instance permutation, so only carrier
 * instances pay the item's vertices), and the TRANSLUCENT segment (cloak +
 * wings — the texture's alpha&lt;255 texels) draws alpha-blended without depth
 * writes after every other particle pass.
 * <p>
 * Part ids: 0=head, 1=body skin, 2=right_arm, 3=left_arm, 4=right_wing,
 * 5=left_wing, 6=body cloak (shares the body transform; sorted into the
 * translucent range), 7=held item ({@link HeldItemGeometry}: pos.xyz in
 * ITEM-MODEL space [0,1] with CANONICAL sprite UVs, remapped per tier in the
 * vertex shader — unlike the body parts, whose UVs are baked atlas-space).
 * Pivots and pose math live in the shader.
 */
final class AllayModelGeometry {

    /** 32x32 texture (LayerDefinition.create(..., 32, 32)). */
    private static final float TEX_SIZE = 32f;
    /**
     * Vertex stride in floats: pos.xyz, uv.xy, partId, normalAxis. Injected
     * into every shader source as MODEL_VERTEX_FLOATS by ParticlePrograms —
     * this constant is the single source of truth for the stride.
     */
    public static final int VERTEX_FLOATS = 7;

    /** Face normalAxis ids, matching the per-vertex attribute from {@link #face}.
     * Declared before the bake block so its cube() calls can use them. */
    static final int AXIS_EAST = 0, AXIS_WEST = 1, AXIS_UP = 2,
            AXIS_DOWN = 3, AXIS_SOUTH = 4, AXIS_NORTH = 5;

    /**
     * MODEL atlas layout (shared with {@link ParticleAtlas#ALLAY}, whose frame
     * grid is sized from these): frame 0 = the allay body texture, frames
     * 1..6 = the held-item sword tiers ({@link HeldItemGeometry}). The cell
     * equals the 32x32 allay texture; the 16x16 sword sprites upscale to fill.
     */
    public static final int ATLAS_COLS = HeldItemGeometry.ATLAS_COLS;
    public static final int ATLAS_ROWS = HeldItemGeometry.ATLAS_ROWS;
    public static final int ATLAS_CELL = HeldItemGeometry.ATLAS_CELL;
    /** Atlas-space UV scale for frame 0 (the allay, cell (0,0)). */
    private static final float UV_SCALE_U = (float) ATLAS_CELL / (ATLAS_COLS * ATLAS_CELL);
    private static final float UV_SCALE_V = (float) ATLAS_CELL / (ATLAS_ROWS * ATLAS_CELL);

    /** Flat vertex array (pos.xyz model units / uv normalised / partId). */
    static final float[] VERTICES;
    /** Triangle indices; wing faces carry both windings (double-sided planes). */
    static final int[] INDICES;
    /**
     * {@code INDICES[0 .. BODY_OPAQUE_INDEX_COUNT)} = body-opaque cutout
     * range (indirect cmd2); {@code [BODY_OPAQUE_INDEX_COUNT ..
     * OPAQUE_INDEX_COUNT)} = the held item's CARRIER segment (cmd3, drawn
     * only for carrier instances); the remainder = translucent blended
     * segment (cmd4).
     */
    static final int BODY_OPAQUE_INDEX_COUNT;
    static final int OPAQUE_INDEX_COUNT;

    /**
     * Rest-pose model-space Y extents per part: {@code {pivotY rel root, cube
     * y0, cube y1}} in model units (1/16 block), mirroring the pivots in
     * chunks/allay_pose.glsl's {@code cmiAllayPartTransform} and the cube()
     * calls above (including CubeDeformation) -- the two MUST stay in sync.
     * Used only for the vanilla-size scale divisor below.
     */
    private static final float[][] REST_Y_EXTENTS = {
            { -3.99f, -5f, 0f },      // head
            { -4.0f, 0f, 4f },        // body skin
            { -3.5f, -0.49f, 3.49f }, // arms (pivot -4 + 0.5; grow -0.01)
            { -4.0f, 0.2f, 4.8f },    // body cloak (grow -0.2)
            { -4.0f, 1f, 6f },        // wings
    };

    /**
     * Above-feet height (blocks) of the rest-pose model: {@code (1.501*16 -
     * top) / 16} where the top is the highest model corner (the head cube at
     * model y 14.51 units) -- 0.594 blocks, matching the vanilla allay's
     * visual height (~= its 0.6-block hitbox; vanilla sizes the hitbox to the
     * model top). This is the scale divisor used by model.vsh, hit.comp and
     * the shader-pack merged program so an above-feet height of exactly
     * {@code 2 x size} renders; the previous hardcoded 0.625 was ~5.5% too
     * tall. Emitted into GLSL as {@code MODEL_ABOVE_FEET}.
     */
    public static final float MODEL_ABOVE_FEET = computeAboveFeet();

    private static float computeAboveFeet() {
        float minY = Float.MAX_VALUE;
        for (float[] p : REST_Y_EXTENTS)
            minY = Math.min(minY, 23.5f + p[0] + p[1]);
        return (1.501f * 16f - minY) / 16f;
    }

    static {
        List<Float> verts = new ArrayList<>(1024);
        List<Integer> idx = new ArrayList<>(600);
        // ---- opaque segment (cutout + depth write) ----
        // part 0: head  texOffs(0,0)  box(-2.5,-5,-2.5, 5,5,5)
        cube(verts, idx, 0, 0, 0, -2.5f, -5f, -2.5f, 5f, 5f, 5f, 0f, false, 0);
        // part 1: body skin texOffs(0,10) box(-1.5,0,-1, 3,4,2)
        cube(verts, idx, 1, 0, 10, -1.5f, 0f, -1f, 3f, 4f, 2f, 0f, false, 0);
        // part 2: right_arm texOffs(23,0) box(-0.75,-0.5,-1, 1,4,2) inflate -0.01
        cube(verts, idx, 2, 23, 0, -0.75f, -0.5f, -1f, 1f, 4f, 2f, -0.01f, false, 0);
        // part 3: left_arm  texOffs(23,6) box(-0.25,-0.5,-1, 1,4,2) inflate -0.01
        cube(verts, idx, 3, 23, 6, -0.25f, -0.5f, -1f, 1f, 4f, 2f, -0.01f, false, 0);
        // the held item's index range STARTS here: cmd2 (body cutout) ends and
        // the cmd3 carrier segment begins at this boundary
        BODY_OPAQUE_INDEX_COUNT = idx.size();
        // held item (partId 7): the vanilla flat handheld quad pair — emitted
        // into the OPAQUE-class segments (alpha cutout), so it lands BEFORE the
        // translucent index split below
        HeldItemGeometry.appendQuads(verts, idx);
        OPAQUE_INDEX_COUNT = idx.size();
        // ---- translucent segment (alpha blend; cloak + wings are double-wound
        // so both shell sides are visible under cull — the sub-draw writes
        // depth, which keeps each pixel to a single blend) ----
        // part 6: body cloak texOffs(0,16) box(-1.5,0,-1, 3,5,2) inflate -0.2
        // (side texels are alpha 131-160 — genuinely translucent; the TOP/BOTTOM
        // cap texels x[2,8) x y[16,18) are ALL alpha 0, so those two faces are
        // baked out below — see the class javadoc for the full rationale)
        cube(verts, idx, 6, 0, 16, -1.5f, 0f, -1f, 3f, 5f, 2f, -0.2f, true,
                (1 << AXIS_UP) | (1 << AXIS_DOWN));
        // parts 4/5: wings texOffs(16,14) box(0,1,0, 0,5,8) — zero-thickness:
        // dx=0 leaves only the WEST/EAST quads, which sample DIFFERENT rects —
        // WEST x[16,24) carries the wing art, EAST x[24,32) is ALL alpha 0 —
        // so EAST is baked out and ONE double-wound WEST quad serves both sides
        // (model.fsh flips the shading normal on !gl_FrontFacing)
        cube(verts, idx, 4, 16, 14, 0f, 1f, 0f, 0f, 5f, 8f, 0f, true,
                (1 << AXIS_EAST));
        cube(verts, idx, 5, 16, 14, 0f, 1f, 0f, 0f, 5f, 8f, 0f, true,
                (1 << AXIS_EAST));

        VERTICES = new float[verts.size()];
        for (int i = 0; i < VERTICES.length; i++)
            VERTICES[i] = verts.get(i);
        INDICES = new int[idx.size()];
        for (int i = 0; i < INDICES.length; i++)
            INDICES[i] = idx.get(i);
    }

    private AllayModelGeometry() {
    }

    /**
     * Emits one vanilla {@code ModelPart.Cube}: for each non-degenerate face
     * NOT masked out by {@code skipFaces}, four unique vertices with the
     * cube's exact UV rect and vertex order (unmirrored — the allay model sets
     * no mirror flag) plus two triangles. Bit N of {@code skipFaces} skips the
     * face whose normalAxis is N (used to bake away the cloak's fully
     * transparent top/bottom caps — see the class javadoc).
     */
    private static void cube(List<Float> out, List<Integer> idx, int partId, int texU, int texV,
            float ox, float oy, float oz, float dx, float dy, float dz, float grow,
            boolean doubleSided, int skipFaces) {
        // grow (CubeDeformation): expand each side; vanilla subtracts for
        // negative values (Cube constructor: origin -= grow, max += grow)
        float x0 = ox - grow, y0 = oy - grow, z0 = oz - grow;
        float x1 = ox + dx + grow, y1 = oy + dy + grow, z1 = oz + dz + grow;

        // the 8 cube corners, vanilla naming (v7..v6 in Cube's constructor)
        float[][] c = {
                { x0, y0, z0 }, { x1, y0, z0 }, { x1, y1, z0 }, { x0, y1, z0 }, // z0 face ring
                { x0, y0, z1 }, { x1, y0, z1 }, { x1, y1, z1 }, { x0, y1, z1 }  // z1 face ring
        };
        // corner index constants after the table above (constructor order):
        // 0=v7, 1=v, 2=v1, 3=v2, 4=v3, 5=v4, 6=v5, 7=v6
        // u-columns / v-rows of the vanilla atlas layout
        float u0 = texU, uD = texU + dz, uDx = texU + dz + dx, uDxx = texU + dz + dx + dz;
        float v0 = texV, vD = texV + dz, vDy = texV + dz + dy;

        // DOWN (y0): verts {5,4,0,1} rect (uD,v0)-(uDx,vD)   normal -Y
        if ((skipFaces & (1 << AXIS_DOWN)) == 0)
            face(out, idx, partId, c, new int[] { 5, 4, 0, 1 }, uD, v0, uDx, vD, dz, dx, doubleSided, AXIS_DOWN);
        // UP (y1): verts {2,3,7,6} rect (uDx,vD)-(u0+dz+2dx,v0)   normal +Y
        if ((skipFaces & (1 << AXIS_UP)) == 0)
            face(out, idx, partId, c, new int[] { 2, 3, 7, 6 }, uDx, vD, texU + dz + dx + dx, v0, dx, dz, doubleSided, AXIS_UP);
        // WEST (x0): verts {0,4,7,3} rect (u0,vD)-(uD,vDy)   normal -X
        if ((skipFaces & (1 << AXIS_WEST)) == 0)
            face(out, idx, partId, c, new int[] { 0, 4, 7, 3 }, u0, vD, uD, vDy, dz, dy, doubleSided, AXIS_WEST);
        // NORTH (z0): verts {1,0,3,2} rect (uD,vD)-(uDx,vDy)   normal -Z
        if ((skipFaces & (1 << AXIS_NORTH)) == 0)
            face(out, idx, partId, c, new int[] { 1, 0, 3, 2 }, uD, vD, uDx, vDy, dx, dy, doubleSided, AXIS_NORTH);
        // EAST (x1): verts {5,1,2,6} rect (uDx,vD)-(uDxx,vDy)   normal +X
        if ((skipFaces & (1 << AXIS_EAST)) == 0)
            face(out, idx, partId, c, new int[] { 5, 1, 2, 6 }, uDx, vD, uDxx, vDy, dz, dy, doubleSided, AXIS_EAST);
        // SOUTH (z1): verts {4,5,6,7} rect (uDxx,vD)-(uDxx+dx,vDy)   normal +Z
        if ((skipFaces & (1 << AXIS_SOUTH)) == 0)
            face(out, idx, partId, c, new int[] { 4, 5, 6, 7 }, uDxx, vD, uDxx + dx, vDy, dx, dy, doubleSided, AXIS_SOUTH);
    }

    /**
     * One quad: four unique vertices (vanilla's per-vertex UV assignment —
     * vertex i maps to (u2,v1),(u1,v1),(u1,v2),(u2,v2)) plus two index
     * triangles; {@code doubleSided} emits a second reversed-winding triangle
     * pair sharing the same vertices. Degenerate faces (a zero-length
     * spanning axis) are skipped. The span lengths are only used for the
     * degeneracy test.
     */
    private static void face(List<Float> out, List<Integer> idx, int partId, float[][] c, int[] q,
            float u1, float v1, float u2, float v2, float spanA, float spanB, boolean doubleSided,
            int normalAxis) {
        if (spanA == 0f || spanB == 0f)
            return;
        float[] us = { u2, u1, u1, u2 };
        float[] vs = { v1, v1, v2, v2 };
        int base = out.size() / VERTEX_FLOATS;
        for (int t = 0; t < 4; t++) {
            float[] p = c[q[t]];
            out.add(p[0]);
            out.add(p[1]);
            out.add(p[2]);
            // atlas-space UV: frame-0 (allay) texel UVs scaled into the frame's
            // atlas cell — the MODEL atlas is a 4x2 grid, allay at cell (0,0)
            out.add(us[t] / TEX_SIZE * UV_SCALE_U);
            out.add(vs[t] / TEX_SIZE * UV_SCALE_V);
            out.add((float) partId);
            out.add((float) normalAxis);
        }
        idx.add(base);
        idx.add(base + 1);
        idx.add(base + 2);
        idx.add(base);
        idx.add(base + 2);
        idx.add(base + 3);
        if (doubleSided) { // reversed winding — visible from the other side
            idx.add(base);
            idx.add(base + 2);
            idx.add(base + 1);
            idx.add(base);
            idx.add(base + 3);
            idx.add(base + 2);
        }
    }
}
