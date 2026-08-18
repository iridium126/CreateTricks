#include veil:space_helper

uniform sampler2D DiffuseSampler0;
uniform sampler2D DiffuseDepthSampler;

uniform int RodCount;
uniform float RodData[192]; // packed: x,y,z,maxRadius,height,intensity per rod, max 32
uniform float RingStrength;
uniform float RingTime;

in vec2 texCoord;
out vec4 fragColor;

const float DEPTH_CUT_NEAR = 0.02; // source unoccluded when the scene surface is closer than this to it
const float DEPTH_CUT_FAR = 0.2; // source fully occluded when a surface lies this far in front of it

// Dual-tone ring palette: the molten rose quartz / liquid soul colour
// conflict, resolved radially. The ring core burns hot pink-white and cools
// toward purple-red over the pulse cycle; the outer edge is the soul's cold
// cyan; the transition band between them blends core and cyan.
const vec3 CORE_PINK = vec3(1.0, 0.7, 0.9);
const vec3 CORE_PURPLE = vec3(0.7, 0.3, 1.0);
const vec3 OUTER_CYAN = vec3(0.2, 0.8, 1.0);

// Exponential tone map for the accumulated glow: the ring core may exceed
// 1.0 in HDR; this compresses it smoothly instead of clipping. Applied to
// the glow only, so the scene colour is untouched.
const float GLOW_EXPOSURE = 2.0;

/**
 * Evaluates the ring response at one ray parameter and keeps the sample with
 * the strongest response (response, distance and ray parameter travel
 * together). The distance is the 3D distance to the ring circle: horizontal
 * offset from the spread radius combined with the vertical offset from the
 * ring plane, wrapped in the tube-width gaussian.
 */
void considerRing(float t, vec3 rayStart, vec3 rayDir, vec3 center, float spreadRadius, float tubeWidth, float ringY,
    inout float ring, inout float ringDistance, inout float ringHitT) {
    vec3 p = rayStart + rayDir * t;
    float horizontalDistance = length(vec2(p.x - center.x, p.z - center.z));
    float d = length(vec2(horizontalDistance - spreadRadius, p.y - ringY));
    float r = exp(-(d * d) / (tubeWidth * tubeWidth));
    if (r > ring) {
        ring = r;
        ringDistance = d;
        ringHitT = t;
    }
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

    // Additive glow accumulation for the pulsing top ring. Depth-based occlusion
    // fades each contribution when a scene surface lies in front of its source;
    // the glow is its own light, so there is no scattering or absorption.
    vec3 glow = vec3(0.0);
    for (int i = 0; i < RodCount; i++) {
        vec3 center = vec3(RodData[i * 6], RodData[i * 6 + 1], RodData[i * 6 + 2]);
        float radius = RodData[i * 6 + 3];
        float height = RodData[i * 6 + 4];
        float intensity = RodData[i * 6 + 5];
        if (intensity <= 0.0 || radius <= 0.0 || height <= 0.0)
            continue;

        // A pulsing horizontal ring 0.5 blocks below the rod's top plane.
        // Each 3s cycle the radius diffuses from maxRadius-0.5 to 1.5x maxRadius
        // while the tube widens 0 -> maxRadius/10 and the brightness breathes
        // on an asymmetric envelope: fast rise to the peak near phase 0.26,
        // slow decay through the rest of the cycle (the phase wrap is
        // invisible at zero brightness). Per-rod phase comes from a position
        // hash so nearby rods pulse out of sync.
        float ringPeriod = 3.0;
        float ringSeed = fract(sin(dot(vec2(center.x, center.z), vec2(12.9898, 78.233))) * 43758.5453);
        float phase = fract(RingTime / ringPeriod + ringSeed);
        float pulse = pow(phase, 0.35) * (1.0 - phase);
        if (pulse > 0.01) {
            float spreadRadius = mix(radius - 0.5, 1.5 * radius, phase);
            float tubeWidth = max(radius * phase / 10.0, 1e-3); // 0 -> maxRadius/10
            float ringY = center.y + height - 0.5;

            // Bounding-sphere cull: skip rays that cannot reach the ring (at
            // its widest spread plus the visible tube band) before the scene
            // surface, so rays passing nowhere near a rod cost almost nothing.
            vec3 sphereCenter = vec3(center.x, ringY, center.z);
            float sphereRadius = 2.0 * radius + tubeWidth * 3.0 + 0.5;
            vec3 oc = rayStart - sphereCenter;
            float b = dot(oc, rayDir);
            float c = dot(oc, oc) - sphereRadius * sphereRadius;
            float h = b * b - c;
            if (h >= 0.0) {
                float hSqrt = sqrt(h);
                if (-b + hSqrt >= 0.0 && -b - hSqrt <= rayLength) {
                    // Ring distance by sampling: the two analytic candidates
                    // (ring-plane crossing, closest approach to the axis) plus
                    // two blends between them cover grazing rays, where either
                    // candidate alone underestimates the ring and the glow
                    // would break. The strongest response wins; its distance
                    // drives the radial colour gradient and its ray parameter
                    // the depth fade.
                    float tPlane = -1.0;
                    if (abs(rayDir.y) > 1e-4) {
                        float planeHitT = (ringY - rayStart.y) / rayDir.y;
                        if (planeHitT >= 0.0 && planeHitT <= rayLength)
                            tPlane = planeHitT;
                    }
                    float tAxis = -1.0;
                    float horizontalRayLength2 = rayDir.x * rayDir.x + rayDir.z * rayDir.z;
                    if (horizontalRayLength2 > 1e-6)
                        tAxis = clamp(-((rayStart.x - center.x) * rayDir.x + (rayStart.z - center.z) * rayDir.z) / horizontalRayLength2, 0.0, rayLength);

                    float ring = 0.0;
                    float ringDistance = 0.0;
                    float ringHitT = -1.0;
                    if (tPlane >= 0.0)
                        considerRing(tPlane, rayStart, rayDir, center, spreadRadius, tubeWidth, ringY, ring, ringDistance, ringHitT);
                    if (tAxis >= 0.0)
                        considerRing(tAxis, rayStart, rayDir, center, spreadRadius, tubeWidth, ringY, ring, ringDistance, ringHitT);
                    if (tPlane >= 0.0 && tAxis >= 0.0) {
                        considerRing(0.5 * (tPlane + tAxis), rayStart, rayDir, center, spreadRadius, tubeWidth, ringY, ring, ringDistance, ringHitT);
                        considerRing(0.25 * tPlane + 0.75 * tAxis, rayStart, rayDir, center, spreadRadius, tubeWidth, ringY, ring, ringDistance, ringHitT);
                    }

                    if (ring > 0.01 && ringHitT >= 0.0) {
                        float depthFade = 1.0 - smoothstep(DEPTH_CUT_NEAR, DEPTH_CUT_FAR, ringHitT - rayLength);
                        // Radial dual-tone gradient: inside the tube the core
                        // colour (hot pink cooling to purple-red over the
                        // phase), outside the cold cyan; the transition band
                        // spans one tube width on each side of the ring line.
                        vec3 coreColor = mix(CORE_PINK, CORE_PURPLE, phase);
                        float t = clamp((ringDistance / tubeWidth) * 0.5 + 0.5, 0.0, 1.0);
                        vec3 ringCol = mix(coreColor, OUTER_CYAN, t);
                        glow += ringCol * intensity * RingStrength * pulse * ring * depthFade;
                    }
                }
            }
        }
    }

    // HDR tone map: the accumulated glow may exceed 1.0 at the ring core;
    // compress it exponentially instead of clipping (scene untouched).
    glow = vec3(1.0) - exp(-glow * GLOW_EXPOSURE);

    fragColor = sceneColor;
    fragColor.rgb += glow;
    fragColor.a = sceneColor.a;
    gl_FragDepth = sceneDepth;
}
