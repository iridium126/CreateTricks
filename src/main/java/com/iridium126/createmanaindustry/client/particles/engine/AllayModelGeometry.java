package com.iridium126.createmanaindustry.client.particles.engine;

import java.util.ArrayList;
import java.util.List;

/**
 * Static vertex bake of the vanilla allay entity model ({@code AllayModel.
 * createBodyLayer()}) for MODEL particles. Each vertex is 6 floats —
 * {@code pos.xyz} (model units, 1/16 block), {@code uv} (normalised against
 * the 32x32 texture) and {@code partId} — flattened into one array consumed by
 * {@code model.vsh} (SSBO vertex pulling). Faces are emitted as 6-vertex
 * triangle pairs with the exact per-face UV rects and vertex order of vanilla
 * {@code ModelPart.Cube}, so the shared 32x32 {@code allay.png} maps correctly.
 * Degenerate faces (zero-area sides of the 0-thickness wings) are skipped.
 * <p>
 * Part-space pivots and the pose math itself live in the shader; the part ids
 * here are: 0=head, 1=body, 2=right_arm, 3=left_arm, 4=right_wing, 5=left_wing
 * (the body's two stacked cubes share id 1 — vertices are part-local).
 */
final class AllayModelGeometry {

    /** 32x32 texture (LayerDefinition.create(..., 32, 32)). */
    private static final float TEX_SIZE = 32f;
    /** Vertex stride in floats: pos.xyz, uv.xy, partId. */
    public static final int VERTEX_FLOATS = 6;

    static final float[] BAKED = bake();

    private AllayModelGeometry() {
    }

    private static float[] bake() {
        List<Float> out = new ArrayList<>(2048);
        // part 0: head  texOffs(0,0)  box(-2.5,-5,-2.5, 5,5,5)
        cube(out, 0, 0, 0, -2.5f, -5f, -2.5f, 5f, 5f, 5f, 0f);
        // part 1: body  texOffs(0,10) box(-1.5,0,-1, 3,4,2)   (skin)
        cube(out, 1, 0, 10, -1.5f, 0f, -1f, 3f, 4f, 2f, 0f);
        //        body  texOffs(0,16) box(-1.5,0,-1, 3,5,2) inflate -0.2 (cloak)
        cube(out, 1, 0, 16, -1.5f, 0f, -1f, 3f, 5f, 2f, -0.2f);
        // part 2: right_arm texOffs(23,0) box(-0.75,-0.5,-1, 1,4,2) inflate -0.01
        cube(out, 2, 23, 0, -0.75f, -0.5f, -1f, 1f, 4f, 2f, -0.01f);
        // part 3: left_arm  texOffs(23,6) box(-0.25,-0.5,-1, 1,4,2) inflate -0.01
        cube(out, 3, 23, 6, -0.25f, -0.5f, -1f, 1f, 4f, 2f, -0.01f);
        // parts 4/5: wings texOffs(16,14) box(0,1,0, 0,5,8) — zero-thickness
        cube(out, 4, 16, 14, 0f, 1f, 0f, 0f, 5f, 8f, 0f);
        cube(out, 5, 16, 14, 0f, 1f, 0f, 0f, 5f, 8f, 0f);

        float[] baked = new float[out.size()];
        for (int i = 0; i < baked.length; i++)
            baked[i] = out.get(i);
        return baked;
    }

    /**
     * Emits one vanilla {@code ModelPart.Cube}: for each non-degenerate face,
     * two triangles with the cube's exact UV rect and vertex order (unmirrored
     * — the allay model sets no mirror flag).
     */
    private static void cube(List<Float> out, int partId, int texU, int texV,
            float ox, float oy, float oz, float dx, float dy, float dz, float grow) {
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
        face(out, partId, c, new int[] { 5, 4, 0, 1 }, uD, v0, uDx, vD, dz, dx);
        // UP (y1): verts {2,3,7,6} rect (uDx,vD)-(u0+dz+2dx,v0)
        face(out, partId, c, new int[] { 2, 3, 7, 6 }, uDx, vD, texU + dz + dx + dx, v0, dx, dz);
        // WEST (x0): verts {0,4,7,3} rect (u0,vD)-(uD,vDy)
        face(out, partId, c, new int[] { 0, 4, 7, 3 }, u0, vD, uD, vDy, dz, dy);
        // NORTH (z0): verts {1,0,3,2} rect (uD,vD)-(uDx,vDy)
        face(out, partId, c, new int[] { 1, 0, 3, 2 }, uD, vD, uDx, vDy, dx, dy);
        // EAST (x1): verts {5,1,2,6} rect (uDx,vD)-(uDxx+? see below,vDy)
        face(out, partId, c, new int[] { 5, 1, 2, 6 }, uDx, vD, uDxx, vDy, dz, dy);
        // SOUTH (z1): verts {4,5,6,7} rect (uDxx,vD)-(uDxx+dx,vDy)
        face(out, partId, c, new int[] { 4, 5, 6, 7 }, uDxx, vD, uDxx + dx, vDy, dx, dy);
    }

    /**
     * One quad as two triangles (0,1,2 / 0,2,3) with vanilla's per-vertex UV
     * assignment — vertex i maps to (u2,v1),(u1,v1),(u1,v2),(u2,v2). Degenerate
     * faces (a zero-length spanning axis) are skipped. The two span lengths are
     * only used for the degeneracy test.
     */
    private static void face(List<Float> out, int partId, float[][] c, int[] idx,
            float u1, float v1, float u2, float v2, float spanA, float spanB) {
        if (spanA == 0f || spanB == 0f)
            return;
        float[] us = { u2, u1, u1, u2 };
        float[] vs = { v1, v1, v2, v2 };
        int[] tri = { 0, 1, 2, 0, 2, 3 };
        for (int t : tri) {
            float[] p = c[idx[t]];
            out.add(p[0]);
            out.add(p[1]);
            out.add(p[2]);
            out.add(us[t] / TEX_SIZE);
            out.add(vs[t] / TEX_SIZE);
            out.add((float) partId);
        }
    }
}
