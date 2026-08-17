#include veil:space_helper

uniform sampler2D DiffuseSampler0;
uniform sampler2D DiffuseDepthSampler;

uniform int RodCount;
uniform float RodData[256]; // packed: x,y,z,maxRadius,height,intensity,windowStart,windowCount per rod, max 32
uniform float WindowData[512]; // packed: cx,cy,cz,type per window (type 0..3 = ±X/±Z side, 4 = top), max 128
uniform vec3 GlowColor;
uniform float GlowStrength;
uniform vec3 RingColor;
uniform float RingStrength;
uniform float RingTime;

in vec2 texCoord;
out vec4 fragColor;

const float WINDOW_BAND = 1.0; // coarse window test: ray within maxRadius + this
const float SIDE_HALF = 0.25; // 8x8 side window: 4..12/16 -> half extent 0.25
const float TOP_HALF = 0.375; // top window: 2..14/16 -> half extent 0.375
const float HALO_SCALE = 0.5; // window halo radial falloff scale
const float DEPTH_CUT_NEAR = 0.02; // source unoccluded when the scene surface is closer than this to it
const float DEPTH_CUT_FAR = 0.2; // source fully occluded when a surface lies this far in front of it

/**
 * Closest approach of the ray to the rod's vertical axis segment
 * (center -> center + (0, height, 0)). Returns vec3(ray parameter of the
 * closest point, distance to the segment, axis parameter u in [0, height]).
 * Used only as the coarse band test before the per-window loop.
 */
vec3 closestToSegment(vec3 ro, vec3 rd, vec3 center, float height, float rayLength) {
    float rh2 = rd.x * rd.x + rd.z * rd.z;
    if (rh2 > 1e-6) {
        float t0 = -((ro.x - center.x) * rd.x + (ro.z - center.z) * rd.z) / rh2;
        float y0 = ro.y + t0 * rd.y;
        if (t0 >= 0.0 && t0 <= rayLength && y0 >= center.y && y0 <= center.y + height) {
            float dx = ro.x + t0 * rd.x - center.x;
            float dz = ro.z + t0 * rd.z - center.z;
            return vec3(t0, sqrt(dx * dx + dz * dz), y0 - center.y);
        }
        vec3 best = vec3(0.0, 1e18, 0.0);
        for (int k = 0; k < 2; k++) {
            vec3 ep = k == 0 ? center : vec3(center.x, center.y + height, center.z);
            vec3 oe = ep - ro;
            float t = clamp(dot(oe, rd), 0.0, rayLength);
            vec3 cp = ro + rd * t - ep;
            float d = length(cp);
            if (d < best.y)
                best = vec3(t, d, k == 0 ? 0.0 : height);
        }
        return best;
    }
    float u = clamp(ro.y - center.y, 0.0, height);
    float t = (u + center.y - ro.y) / max(abs(rd.y), 1e-6);
    float dx = ro.x - center.x;
    float dz = ro.z - center.z;
    return vec3(clamp(t, 0.0, rayLength), sqrt(dx * dx + dz * dz), u);
}

vec3 axisFromType(int type) {
    if (type == 0)
        return vec3(1.0, 0.0, 0.0);
    if (type == 1)
        return vec3(-1.0, 0.0, 0.0);
    if (type == 2)
        return vec3(0.0, 0.0, -1.0);
    if (type == 3)
        return vec3(0.0, 0.0, 1.0);
    return vec3(0.0, 1.0, 0.0); // type 4: top pane
}

// ---------------------------------------------------------------------------
// Main
// ---------------------------------------------------------------------------

void main() {
    vec4 sceneColor = texture(DiffuseSampler0, texCoord);
    float sceneDepth = texture(DiffuseDepthSampler, texCoord).r;

    // No rods active — pass through
    if (RodCount <= 0) {
        fragColor = sceneColor;
        gl_FragDepth = sceneDepth;
        return;
    }

    // --- Ray setup (world space) ---

    vec3 worldEnd;
    if (sceneDepth >= 1.0) {
        worldEnd = VeilCamera.CameraPosition + viewDirFromUv(texCoord) * 64.0;
    } else {
        worldEnd = screenToWorldSpace(texCoord, sceneDepth).xyz;
    }
    vec3 rayStart = VeilCamera.CameraPosition;
    vec3 rayDir = worldEnd - rayStart;
    float rayLength = length(rayDir);
    if (rayLength < 1e-5) {
        fragColor = sceneColor;
        gl_FragDepth = sceneDepth;
        return;
    }
    rayDir /= rayLength;

    // Additive glow accumulation: window panes (rectangular spot + halo) and a
    // thin top ring. Depth-based occlusion fades each contribution when a scene
    // surface lies in front of its source; the glow is its own light, so there
    // is no scattering or absorption.
    vec3 glow = vec3(0.0);
    for (int i = 0; i < RodCount; i++) {
        vec3 center = vec3(RodData[i * 8], RodData[i * 8 + 1], RodData[i * 8 + 2]);
        float radius = RodData[i * 8 + 3];
        float height = RodData[i * 8 + 4];
        float intensity = RodData[i * 8 + 5];
        float wStart = RodData[i * 8 + 6];
        float wCount = RodData[i * 8 + 7];
        if (intensity <= 0.0 || radius <= 0.0 || height <= 0.0)
            continue;

        float strength = GlowStrength * intensity;

        // --- window glow ---
        // Coarse band: only rays passing near the rod's boundary loop its windows.
        vec3 closest = closestToSegment(rayStart, rayDir, center, height, rayLength);
        if (closest.y <= radius + WINDOW_BAND && wCount > 0.0) {
            // Constant loop bound (driver-safe); the per-rod count comes from the uniform.
            for (int w = 0; w < 128; w++) {
                if (float(w) >= wCount)
                    break;
                int wi = int(wStart) + w;
                vec3 wc = vec3(WindowData[wi * 4], WindowData[wi * 4 + 1], WindowData[wi * 4 + 2]);
                int type = int(WindowData[wi * 4 + 3] + 0.5);

                vec3 n = axisFromType(type);
                float dn = dot(rayDir, n);
                if (abs(dn) < 1e-4)
                    continue;
                float t = dot(wc - rayStart, n) / dn;
                if (t < 0.0 || t > rayLength)
                    continue;

                vec3 q = rayStart + rayDir * t;
                vec2 local;
                if (type == 0 || type == 1)
                    local = vec2(q.z - wc.z, q.y - wc.y); // normal along X
                else if (type == 2 || type == 3)
                    local = vec2(q.x - wc.x, q.y - wc.y); // normal along Z
                else
                    local = vec2(q.x - wc.x, q.z - wc.z); // top pane
                float half = type == 4 ? TOP_HALF : SIDE_HALF;

                // rectangular spot hugging the pane, with a softer outer halo
                vec2 outside = max(abs(local) - half, vec2(0.0));
                float dRect = length(outside);
                float core = exp(-(dRect * dRect) / (0.12 * 0.12));
                float dCenter = length(local);
                float halo = exp(-(dCenter * dCenter) / (HALO_SCALE * HALO_SCALE));
                float light = max(core, halo * 0.35);
                if (light < 0.01)
                    continue;

                // depth occlusion: the source is occluded only when a scene
                // surface lies meaningfully in front of the pane — the pane
                // itself (same depth) and glass in front of it both keep the glow
                float depthFade = 1.0 - smoothstep(DEPTH_CUT_NEAR, DEPTH_CUT_FAR, t - rayLength);
                if (depthFade <= 0.0)
                    continue;

                glow += GlowColor * strength * light * depthFade;
            }
        }

        // --- top ring ---
        // A pulsing horizontal ring 0.5 blocks below the rod's top plane.
        // Each 3s cycle the radius diffuses from maxRadius to 2x maxRadius
        // while the tube widens 0 -> maxRadius/10 and the brightness breathes
        // on a sine (rise, peak midway, decay; the phase wrap is invisible at
        // zero brightness). Per-rod phase comes from a position hash so nearby
        // rods pulse out of sync. Two cheap ray candidates cover most angles:
        // (1) the crossing with the ring's plane, (2) the closest approach to
        // the axis (rays nearly parallel to the plane).
        float ringPeriod = 3.0;
        float ringSeed = fract(sin(dot(vec2(center.x, center.z), vec2(12.9898, 78.233))) * 43758.5453);
        float phase = fract(RingTime / ringPeriod + ringSeed);
        float pulse = sin(3.14159265 * phase);
        if (pulse > 0.01) {
            float spreadR = mix(radius, 2.0 * radius, phase);
            float tube = max(radius * phase / 10.0, 1e-3); // 0 -> maxRadius/10
            float ringY = center.y + height - 0.5;
            float ring = 0.0;
            float tUsed = -1.0;
            if (abs(rayDir.y) > 1e-4) {
                float tQ = (ringY - rayStart.y) / rayDir.y;
                if (tQ >= 0.0 && tQ <= rayLength) {
                    vec3 q = rayStart + rayDir * tQ;
                    float hQ = length(vec2(q.x - center.x, q.z - center.z));
                    float dH = abs(hQ - spreadR);
                    ring = max(ring, exp(-(dH * dH) / (tube * tube)));
                    tUsed = tQ;
                }
            }
            float rh2 = rayDir.x * rayDir.x + rayDir.z * rayDir.z;
            if (rh2 > 1e-6) {
                float tP = -((rayStart.x - center.x) * rayDir.x + (rayStart.z - center.z) * rayDir.z) / rh2;
                tP = clamp(tP, 0.0, rayLength);
                vec3 p = rayStart + rayDir * tP;
                float hP = length(vec2(p.x - center.x, p.z - center.z));
                float dTorus = length(vec2(hP - spreadR, p.y - ringY));
                ring = max(ring, exp(-(dTorus * dTorus) / (tube * tube)));
                if (tUsed < 0.0)
                    tUsed = tP;
            }
            if (ring > 0.01 && tUsed >= 0.0) {
                float depthFade = 1.0 - smoothstep(DEPTH_CUT_NEAR, DEPTH_CUT_FAR, tUsed - rayLength);
                glow += RingColor * strength * RingStrength * pulse * ring * depthFade;
            }
        }
    }

    fragColor = sceneColor;
    fragColor.rgb += glow;
    fragColor.a = sceneColor.a;
    gl_FragDepth = sceneDepth;
}