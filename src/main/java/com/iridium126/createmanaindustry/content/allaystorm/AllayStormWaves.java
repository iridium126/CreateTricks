package com.iridium126.createmanaindustry.content.allaystorm;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.PriorityQueue;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.phys.Vec3;

/**
 * Server-side math for the Allay Storm dive waves (see docs/allay-storm-ai.md):
 * the FLOAT-EXACT hash chains shared with the GPU shaders, and the wave
 * corridor (coarse heightmap A* + smoothing).
 * <p>
 * <b>Float discipline</b>: both hash chains below are line-by-line ports of
 * the GLSL in {@code chunks/allay_pose.glsl} ({@code cmiHash1},
 * {@code cmiStormWaveRoll}) and {@code emit.comp} (the identity chain). Java
 * float ops are IEEE-754 single precision and NEVER fuse into FMA; the GLSL
 * side keeps the same guarantee through its named single-mul statement
 * discipline (no inline {@code a*b+c} for the compiler to contract), so the
 * server's contact-report re-derivation and every client's GPU selection
 * agree bit-for-bit. Do not "simplify" these expressions.
 */
public final class AllayStormWaves {

    /** Maximum waypoints shipped to clients (GPU uniform budget: 4 slots x 6). */
    public static final int MAX_WAYPOINTS = 6;

    private AllayStormWaves() {
    }

    // ---- hash chains (float-exact GLSL ports) --------------------------------

    /** GLSL {@code fract}: x - floor(x), exact for the magnitudes used here. */
    private static float fract(float x) {
        return x - (float) Math.floor(x);
    }

    /** GLSL {@code cmiHash1} (chunks/allay_pose.glsl), verbatim. */
    public static float hash1(float p) {
        p = p * 0.1031f;
        p = p - (float) Math.floor(p);
        p = p * (p + 33.33f);
        p = p * (p + p);
        p = p - (float) Math.floor(p);
        return p;
    }

    /**
     * Member identity seed: GLSL {@code emit.comp}'s storm-style chain
     * {@code hash(stormSeed, memberIdx)} in [0,1). {@code stormSeed} must be
     * pre-masked to 24 bits (float-exact); {@code memberIdx} &lt; 2^24 is exact
     * in float. Consumed by the wave-membership re-derivation below.
     */
    public static float memberSeed(int stormSeed, int memberIdx) {
        float s0 = hash1((float) stormSeed + 31.7f);
        float shift = s0 * 97.0f;
        float t = (float) memberIdx * 0.6123f;
        float p = t + 0.7f;
        float q = p + shift;
        float hA = hash1(q);
        float q2 = q * 7.31f;
        float hB = hash1(q2);
        float mA = hA * 0.618f;
        float mB = hB * 0.382f;
        return mA + mB;
    }

    /**
     * GLSL {@code cmiStormWaveRoll}: the per-member wave-squad roll in [0,1).
     * A member belongs to the wave iff {@code waveRoll(...) < fraction}.
     */
    public static float waveRoll(float memberSeed, int waveSeed) {
        float s0 = hash1((float) waveSeed + 13.37f);
        float shift = s0 * 89.0f;
        float base = memberSeed * 47.29f;
        float q = base + shift;
        return hash1(q);
    }

    /**
     * Server-side membership test for a contact report — the exact inverse of
     * the GPU test {@code cmiStormWaveMember(p3.z, waveSeed, fraction)} (p3.z
     * holds the member's identity seed, derived from the SAME chain above).
     */
    public static boolean isWaveMember(int stormSeed, int memberIdx, int waveSeed, float fraction) {
        float mseed = memberSeed(stormSeed, memberIdx);
        return waveRoll(mseed, waveSeed) < fraction;
    }

    // ---- wave corridor --------------------------------------------------------

    /** A* cell size in blocks (coarse — the servo smooths the rest). */
    private static final int CELL = 3;
    /** Bounding-box padding around start/end, blocks. */
    private static final int PAD = 8;
    /** A* expansion cap; past it the straight-line fallback is used. */
    private static final int MAX_EXPANSIONS = 200_000;
    /** Ramer-Douglas-Peucker epsilon for the raw A* polyline, blocks. */
    private static final double RDP_EPSILON = 3.0;

    /**
     * Builds the wave corridor: start = chased center at chase altitude, end =
     * {@code endY} blocks above the player's feet (the top of the shaft's
     * useful band). The A* runs over CELL-sized cells in a bounded box, with
     * cells blocked by the WORLD_SURFACE heightmap — one height lookup per
     * column (cached), so the whole search costs a few thousand block-meta
     * queries, not per-voxel scans. The GOAL column is exempt: the target is
     * open-sky (launch eligibility), so the vertical segment inside it is
     * clear by construction — that is what lets the corridor descend below
     * the heightmap into the player's own column. Returns up to
     * {@link #MAX_WAYPOINTS} world-space points (flat xyz), start and goal
     * kept; a failed search degrades to the two-point straight line.
     */
    public static double[] buildCorridor(ServerLevel level, Vec3 start, Vec3 end) {
        List<Vec3> raw = searchAStar(level, start, end);
        List<Vec3> simplified = rdp(raw, RDP_EPSILON);
        List<Vec3> smoothed = chaikin(chaikin(simplified));
        return decimate(smoothed, MAX_WAYPOINTS);
    }

    private static final class Node {
        final int x;
        final int y;
        final int z;
        final float g;
        final float f;

        Node(int x, int y, int z, float g, float f) {
            this.x = x;
            this.y = y;
            this.z = z;
            this.g = g;
            this.f = f;
        }
    }

    /**
     * Coarse 3D A*. Neighbors: 6 axis steps (any Y) + 8 same-level XZ
     * diagonals. Blocked = the cell's 3x3 column footprint intersects the
     * WORLD_SURFACE heightmap (conservative for overhangs BELOW the surface —
     * a floating bridge reads as solid at all depths, which only makes the
     * corridor route around, never through).
     */
    private static List<Vec3> searchAStar(ServerLevel level, Vec3 start, Vec3 end) {
        List<Vec3> fallback = List.of(start, end);
        int minX = Math.min((int) Math.floor(start.x), (int) Math.floor(end.x)) - PAD;
        int maxX = Math.max((int) Math.floor(start.x), (int) Math.floor(end.x)) + PAD;
        int minZ = Math.min((int) Math.floor(start.z), (int) Math.floor(end.z)) - PAD;
        int maxZ = Math.max((int) Math.floor(start.z), (int) Math.floor(end.z)) + PAD;
        int minY = Math.min((int) Math.floor(start.y), (int) Math.floor(end.y)) - PAD;
        int maxY = Math.max((int) Math.floor(start.y), (int) Math.floor(end.y)) + PAD;
        int nx = (maxX - minX) / CELL + 1;
        int ny = (maxY - minY) / CELL + 1;
        int nz = (maxZ - minZ) / CELL + 1;
        if (nx <= 0 || ny <= 0 || nz <= 0 || (long) nx * ny * nz > 4_000_000L)
            return fallback;

        int sx = clampCell((int) Math.floor((start.x - minX) / CELL), nx);
        int sy = clampCell((int) Math.floor((start.y - minY) / CELL), ny);
        int sz = clampCell((int) Math.floor((start.z - minZ) / CELL), nz);
        int gx = clampCell((int) Math.floor((end.x - minX) / CELL), nx);
        int gy = clampCell((int) Math.floor((end.y - minY) / CELL), ny);
        int gz = clampCell((int) Math.floor((end.z - minZ) / CELL), nz);

        // per-column surface heights, cached (one heightmap lookup per column)
        Map<Long, Integer> heights = new HashMap<>();
        // goal column exemption: open-sky eligibility guarantees the vertical
        // segment inside it is clear, so the corridor may descend to the goal
        int goalColX = gx;
        int goalColZ = gz;

        var open = new PriorityQueue<Node>((a, b) -> Float.compare(a.f, b.f));
        Map<Integer, Float> gScore = new HashMap<>();
        Map<Integer, Integer> cameFrom = new HashMap<>();
        int startIdx = index(sx, sy, sz, nx, ny, nz);
        int goalIdx = index(gx, gy, gz, nx, ny, nz);
        gScore.put(startIdx, 0.0f);
        open.add(new Node(sx, sy, sz, 0.0f, heuristic(sx, sy, sz, gx, gy, gz)));
        int expansions = 0;
        boolean found = false;
        while (!open.isEmpty() && expansions++ < MAX_EXPANSIONS) {
            Node cur = open.poll();
            int curIdx = index(cur.x, cur.y, cur.z, nx, ny, nz);
            if (curIdx == goalIdx) {
                found = true;
                break;
            }
            Float g = gScore.get(curIdx);
            if (g == null || cur.g > g + 1e-4f)
                continue; // stale queue entry
            for (int dx = -1; dx <= 1; dx++) {
                for (int dz = -1; dz <= 1; dz++) {
                    if (dx == 0 && dz == 0)
                        continue;
                    boolean diagonal = dx != 0 && dz != 0;
                    for (int dy = -1; dy <= 1; dy++) {
                        if (diagonal && dy != 0)
                            continue; // no vertical diagonals
                        int tx = cur.x + dx;
                        int ty = cur.y + dy;
                        int tz = cur.z + dz;
                        if (tx < 0 || tx >= nx || ty < 0 || ty >= ny || tz < 0 || tz >= nz)
                            continue;
                        boolean exempt = tx == goalColX && tz == goalColZ;
                        if (!exempt && blocked(level, minX, minY, minZ, heights, tx, ty, tz))
                            continue;
                        float cost = diagonal ? 1.4142f : 1.0f;
                        float ng = cur.g + cost;
                        int tIdx = index(tx, ty, tz, nx, ny, nz);
                        Float old = gScore.get(tIdx);
                        if (old != null && old <= ng + 1e-4f)
                            continue;
                        gScore.put(tIdx, ng);
                        cameFrom.put(tIdx, curIdx);
                        open.add(new Node(tx, ty, tz, ng, ng + heuristic(tx, ty, tz, gx, gy, gz)));
                    }
                }
            }
        }
        if (!found)
            return fallback;

        // reconstruct (cell coords), then convert to world-space points
        List<Vec3> out = new ArrayList<>();
        int cur = goalIdx;
        while (true) {
            int cy = cur / (nx * nz);
            int rem = cur - cy * nx * nz;
            int cz = rem / nx;
            int cx = rem - cz * nx;
            out.add(new Vec3(minX + cx * CELL + CELL * 0.5,
                    minY + cy * CELL + CELL * 0.5, minZ + cz * CELL + CELL * 0.5));
            if (cur == startIdx)
                break;
            Integer from = cameFrom.get(cur);
            if (from == null)
                return fallback; // broken chain: degrade instead of guessing
            cur = from;
        }
        // walked goal -> start; restore start -> goal order
        Collections.reverse(out);
        // exact endpoints over the cell centers
        out.set(0, start);
        out.set(out.size() - 1, end);
        return out;
    }

    private static int clampCell(int c, int n) {
        return Math.max(0, Math.min(n - 1, c));
    }

    private static int index(int x, int y, int z, int nx, int ny, int nz) {
        return (y * nx + x) * nz + z;
    }

    private static float heuristic(int x, int y, int z, int gx, int gy, int gz) {
        double dx = x - gx, dy = y - gy, dz = z - gz;
        return (float) Math.sqrt(dx * dx + dy * dy + dz * dz) * 1.001f;
    }

    /**
     * True when any of the cell's 3x3 column footprint has terrain reaching
     * into the cell. {@code getHeight(WORLD_SURFACE)} returns the first free
     * Y (blocks below it are solid), so a column is clear for the cell iff
     * its surface sits at or below the cell's bottom.
     */
    private static boolean blocked(ServerLevel level, int minX, int minY, int minZ,
            Map<Long, Integer> heights, int cx, int cy, int cz) {
        int bottomY = minY + cy * CELL;
        int x0 = minX + cx * CELL;
        int z0 = minZ + cz * CELL;
        for (int dx = 0; dx < CELL; dx++) {
            for (int dz = 0; dz < CELL; dz++) {
                int bx = x0 + dx;
                int bz = z0 + dz;
                long key = BlockPos.asLong(bx, 0, bz);
                Integer h = heights.get(key);
                if (h == null) {
                    h = level.getHeight(Heightmap.Types.WORLD_SURFACE, bx, bz);
                    heights.put(key, h);
                }
                if (bottomY < h)
                    return true; // surface reaches into this cell
            }
        }
        return false; // every column is clear air for the whole cell
    }

    /** Ramer-Douglas-Peucker on a 3D polyline (iterative, epsilon in blocks). */
    private static List<Vec3> rdp(List<Vec3> pts, double epsilon) {
        int n = pts.size();
        if (n <= 2)
            return new ArrayList<>(pts);
        boolean[] keep = new boolean[n];
        keep[0] = keep[n - 1] = true;
        record Task(int lo, int hi) {}
        var stack = new ArrayDeque<Task>();
        stack.push(new Task(0, n - 1));
        while (!stack.isEmpty()) {
            Task t = stack.pop();
            Vec3 a = pts.get(t.lo);
            Vec3 b = pts.get(t.hi);
            double maxD2 = 0.0;
            int maxI = -1;
            Vec3 ab = b.subtract(a);
            double len2 = ab.lengthSqr();
            for (int i = t.lo + 1; i < t.hi; i++) {
                Vec3 p = pts.get(i);
                Vec3 ap = p.subtract(a);
                double t2 = len2 < 1e-12 ? 0.0 : ap.dot(ab) / len2;
                Vec3 c = a.add(ab.scale(Math.max(0.0, Math.min(1.0, t2))));
                double d2 = p.distanceToSqr(c);
                if (d2 > maxD2) {
                    maxD2 = d2;
                    maxI = i;
                }
            }
            if (maxI > 0 && maxD2 > epsilon * epsilon) {
                keep[maxI] = true;
                stack.push(new Task(t.lo, maxI));
                stack.push(new Task(maxI, t.hi));
            }
        }
        List<Vec3> out = new ArrayList<>();
        for (int i = 0; i < n; i++)
            if (keep[i])
                out.add(pts.get(i));
        return out;
    }

    /** One Chaikin corner-cut pass (endpoints preserved) — kills the voxel A*'s right angles. */
    private static List<Vec3> chaikin(List<Vec3> pts) {
        int n = pts.size();
        if (n <= 2)
            return new ArrayList<>(pts);
        List<Vec3> out = new ArrayList<>(n * 2);
        out.add(pts.get(0));
        for (int i = 0; i + 1 < n; i++) {
            Vec3 a = pts.get(i);
            Vec3 b = pts.get(i + 1);
            out.add(a.scale(0.75).add(b.scale(0.25)));
            out.add(a.scale(0.25).add(b.scale(0.75)));
        }
        out.add(pts.get(n - 1));
        return out;
    }

    /**
     * Caps a polyline at {@code max} points: endpoints kept, interior sampled
     * evenly. The pure-pursuit follower plus the servo smooth whatever detail
     * the decimation drops.
     */
    private static double[] decimate(List<Vec3> pts, int max) {
        int n = pts.size();
        if (n <= max) {
            double[] out = new double[n * 3];
            for (int i = 0; i < n; i++) {
                out[i * 3] = pts.get(i).x;
                out[i * 3 + 1] = pts.get(i).y;
                out[i * 3 + 2] = pts.get(i).z;
            }
            return out;
        }
        double[] out = new double[max * 3];
        for (int i = 0; i < max; i++) {
            int src = i == max - 1 ? n - 1 : Math.round(i * (float) (n - 1) / (max - 1));
            Vec3 p = pts.get(src);
            out[i * 3] = p.x;
            out[i * 3 + 1] = p.y;
            out[i * 3 + 2] = p.z;
        }
        return out;
    }
}
