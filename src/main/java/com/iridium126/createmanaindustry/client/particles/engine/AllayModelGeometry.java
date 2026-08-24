package com.iridium126.createmanaindustry.client.particles.engine;

import java.util.ArrayList;
import java.util.List;

/**
 * Static vertex bake of the vanilla allay entity model ({@code AllayModel.
 * createBodyLayer()}) for MODEL particles, emitted as an INDEXED mesh: 4
 * unique vertices per quad plus 6 triangle indices (the wings additionally
 * carry a second, reversed index set so the zero-thickness planes stay
 * double-sided under backface culling — same vertices, no vertex cost).
 * Each vertex is 6 floats — {@code pos.xyz} (model units, 1/16 block),
 * {@code uv} (normalised against the 32x32 texture) and {@code partId} —
 * consumed by {@code model.vsh} (SSBO vertex pulling through the element
 * buffer). Faces use the exact per-face UV rects and vertex order of vanilla
 * {@code ModelPart.Cube}; degenerate faces (zero-area sides) are skipped.
 * <p>
 * The mesh is split into two contiguous index ranges so the model renders as
 * two sub-draws sharing one instance permutation: the OPAQUE segment (head,
 * skin, arms — pure 0/255 texels) keeps the cutout + depth-write path, and
 * the TRANSLUCENT segment (cloak + wings — the texture's alpha&lt;255 texels)
 * draws alpha-blended without depth writes after every other particle pass.
 * <p>
 * Part ids: 0=head, 1=body skin, 2=right_arm, 3=left_arm, 4=right_wing,
 * 5=left_wing, 6=body cloak (shares the body transform; sorted into the
 * translucent range). Pivots and pose math live in the shader.
 */
final class AllayModelGeometry {

    /** 32x32 texture (LayerDefinition.create(..., 32, 32)). */
    private static final float TEX_SIZE = 32f;
    /** Vertex stride in floats: pos.xyz, uv.xy, partId. */
    public static final int VERTEX_FLOATS = 6;

    /** Flat vertex array (pos.xyz model units / uv normalised / partId). */
    static final float[] VERTICES;
    /** Triangle indices; wing faces carry both windings (double-sided planes). */
    static final int[] INDICES;
    /**
     * {@code INDICES[0 .. OPAQUE_INDEX_COUNT)} = opaque cutout segment
     * (indirect cmd2); the remainder = translucent blended segment (cmd3).
     */
    static final int OPAQUE_INDEX_COUNT;

    static {
        List<Float> verts = new ArrayList<>(1024);
        List<Integer> idx = new ArrayList<>(600);
        // ---- opaque segment (cutout + depth write) ----
        // part 0: head  texOffs(0,0)  box(-2.5,-5,-2.5, 5,5,5)
        cube(verts, idx, 0, 0, 0, -2.5f, -5f, -2.5f, 5f, 5f, 5f, 0f, false);
        // part 1: body skin texOffs(0,10) box(-1.5,0,-1, 3,4,2)
        cube(verts, idx, 1, 0, 10, -1.5f, 0f, -1f, 3f, 4f, 2f, 0f, false);
        // part 2: right_arm texOffs(23,0) box(-0.75,-0.5,-1, 1,4,2) inflate -0.01
        cube(verts, idx, 2, 23, 0, -0.75f, -0.5f, -1f, 1f, 4f, 2f, -0.01f, false);
        // part 3: left_arm  texOffs(23,6) box(-0.25,-0.5,-1, 1,4,2) inflate -0.01
        cube(verts, idx, 3, 23, 6, -0.25f, -0.5f, -1f, 1f, 4f, 2f, -0.01f, false);
        OPAQUE_INDEX_COUNT = idx.size();
        // ---- translucent segment (alpha blend; cloak + wings are double-wound
        // so both shell sides are visible under cull — the sub-draw writes
        // depth, which keeps each pixel to a single blend) ----
        // part 6: body cloak texOffs(0,16) box(-1.5,0,-1, 3,5,2) inflate -0.2
        // (the texture's cloak texels are alpha 160 — genuinely translucent)
        cube(verts, idx, 6, 0, 16, -1.5f, 0f, -1f, 3f, 5f, 2f, -0.2f, true);
        // parts 4/5: wings texOffs(16,14) box(0,1,0, 0,5,8) — zero-thickness,
        // double-winding indices keep them visible from both sides under cull
        cube(verts, idx, 4, 16, 14, 0f, 1f, 0f, 0f, 5f, 8f, 0f, true);
        cube(verts, idx, 5, 16, 14, 0f, 1f, 0f, 0f, 5f, 8f, 0f, true);

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
     * Emits one vanilla {@code ModelPart.Cube}: for each non-degenerate face,
     * four unique vertices with the cube's exact UV rect and vertex order
     * (unmirrored — the allay model sets no mirror flag) plus two triangles.
     */
    private static void cube(List<Float> out, List<Integer> idx, int partId, int texU, int texV,
            float ox, float oy, float oz, float dx, float dy, float dz, float grow,
            boolean doubleSided) {
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

        // DOWN (y0): verts {5,4,0,1} rect (uD,v0)-(uDx,vD)
        face(out, idx, partId, c, new int[] { 5, 4, 0, 1 }, uD, v0, uDx, vD, dz, dx, doubleSided);
        // UP (y1): verts {2,3,7,6} rect (uDx,vD)-(u0+dz+2dx,v0)
        face(out, idx, partId, c, new int[] { 2, 3, 7, 6 }, uDx, vD, texU + dz + dx + dx, v0, dx, dz, doubleSided);
        // WEST (x0): verts {0,4,7,3} rect (u0,vD)-(uD,vDy)
        face(out, idx, partId, c, new int[] { 0, 4, 7, 3 }, u0, vD, uD, vDy, dz, dy, doubleSided);
        // NORTH (z0): verts {1,0,3,2} rect (uD,vD)-(uDx,vDy)
        face(out, idx, partId, c, new int[] { 1, 0, 3, 2 }, uD, vD, uDx, vDy, dx, dy, doubleSided);
        // EAST (x1): verts {5,1,2,6} rect (uDx,vD)-(uDxx,vDy)
        face(out, idx, partId, c, new int[] { 5, 1, 2, 6 }, uDx, vD, uDxx, vDy, dz, dy, doubleSided);
        // SOUTH (z1): verts {4,5,6,7} rect (uDxx,vD)-(uDxx+dx,vDy)
        face(out, idx, partId, c, new int[] { 4, 5, 6, 7 }, uDxx, vD, uDxx + dx, vDy, dx, dy, doubleSided);
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
            float u1, float v1, float u2, float v2, float spanA, float spanB, boolean doubleSided) {
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
            out.add(us[t] / TEX_SIZE);
            out.add(vs[t] / TEX_SIZE);
            out.add((float) partId);
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
