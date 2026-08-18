#include veil:space_helper

uniform sampler2D DiffuseSampler0;
uniform sampler2D DiffuseDepthSampler;

uniform int RodCount;
uniform float RodData[192]; // packed: x,y,z,maxRadius,height,intensity per rod, max 32
uniform vec3 RingColor;
uniform float RingStrength;
uniform float RingTime;

in vec2 texCoord;
out vec4 fragColor;

const float DEPTH_CUT_NEAR = 0.02; // source unoccluded when the scene surface is closer than this to it
const float DEPTH_CUT_FAR = 0.2; // source fully occluded when a surface lies this far in front of it

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
            float spreadRadius = mix(radius, 2.0 * radius, phase);
            float tubeWidth = max(radius * phase / 10.0, 1e-3); // 0 -> maxRadius/10
            float ringY = center.y + height - 0.5;
            float ring = 0.0;
            float ringHitT = -1.0;
            if (abs(rayDir.y) > 1e-4) {
                float planeHitT = (ringY - rayStart.y) / rayDir.y;
                if (planeHitT >= 0.0 && planeHitT <= rayLength) {
                    vec3 planePoint = rayStart + rayDir * planeHitT;
                    float horizontalDistance = length(vec2(planePoint.x - center.x, planePoint.z - center.z));
                    float distanceFromRing = abs(horizontalDistance - spreadRadius);
                    ring = max(ring, exp(-(distanceFromRing * distanceFromRing) / (tubeWidth * tubeWidth)));
                    ringHitT = planeHitT;
                }
            }
            float horizontalRayLength2 = rayDir.x * rayDir.x + rayDir.z * rayDir.z;
            if (horizontalRayLength2 > 1e-6) {
                float axisClosestT = -((rayStart.x - center.x) * rayDir.x + (rayStart.z - center.z) * rayDir.z) / horizontalRayLength2;
                axisClosestT = clamp(axisClosestT, 0.0, rayLength);
                vec3 axisHit = rayStart + rayDir * axisClosestT;
                float horizontalDistance = length(vec2(axisHit.x - center.x, axisHit.z - center.z));
                float ringTorusDistance = length(vec2(horizontalDistance - spreadRadius, axisHit.y - ringY));
                ring = max(ring, exp(-(ringTorusDistance * ringTorusDistance) / (tubeWidth * tubeWidth)));
                if (ringHitT < 0.0)
                    ringHitT = axisClosestT;
            }
            if (ring > 0.01 && ringHitT >= 0.0) {
                float depthFade = 1.0 - smoothstep(DEPTH_CUT_NEAR, DEPTH_CUT_FAR, ringHitT - rayLength);
                glow += RingColor * intensity * RingStrength * pulse * ring * depthFade;
            }
        }
    }

    fragColor = sceneColor;
    fragColor.rgb += glow;
    fragColor.a = sceneColor.a;
    gl_FragDepth = sceneDepth;
}