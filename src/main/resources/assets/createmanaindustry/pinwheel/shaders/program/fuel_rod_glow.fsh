#include veil:space_helper

uniform sampler2D DiffuseSampler0;
uniform sampler2D DiffuseDepthSampler;

uniform int RodCount;
uniform float RodData[192]; // packed: x,y,z,radius,height,intensity per rod, max 32
uniform vec3 GlowColor;
uniform float GlowStrength;

in vec2 texCoord;
out vec4 fragColor;

// ---------------------------------------------------------------------------
// Ray vs vertical rod helpers
// ---------------------------------------------------------------------------

/**
 * Closest approach of the ray to the rod's vertical axis segment
 * (center -> center + (0, height, 0)). Returns vec3(ray parameter of the
 * closest point, distance to the segment, axis parameter u in [0, height]).
 */
vec3 closestToSegment(vec3 ro, vec3 rd, vec3 center, float height, float rayLength) {
    float rh2 = rd.x * rd.x + rd.z * rd.z;
    if (rh2 > 1e-6) {
        // closest approach of the ray line to the infinite axis line — only
        // valid while the foot lies on the FORWARD ray and within the segment
        // (a rod behind the camera must measure from the camera, not from the
        // backward extension of the ray)
        float t0 = -((ro.x - center.x) * rd.x + (ro.z - center.z) * rd.z) / rh2;
        float y0 = ro.y + t0 * rd.y;
        if (t0 >= 0.0 && t0 <= rayLength && y0 >= center.y && y0 <= center.y + height) {
            float dx = ro.x + t0 * rd.x - center.x;
            float dz = ro.z + t0 * rd.z - center.z;
            return vec3(t0, sqrt(dx * dx + dz * dz), y0 - center.y);
        }
        // otherwise the closest point is at one of the segment's ends: measure
        // from the perpendicular foot of each end onto the clamped ray
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
    // vertical ray: the horizontal distance is constant; the closest point sits
    // at the segment's clamped height
    float u = clamp(ro.y - center.y, 0.0, height);
    float t = (u + center.y - ro.y) / max(abs(rd.y), 1e-6);
    float dx = ro.x - center.x;
    float dz = ro.z - center.z;
    return vec3(clamp(t, 0.0, rayLength), sqrt(dx * dx + dz * dz), u);
}

/**
 * Ray parameter where the ray enters the (infinite, vertical) cylinder of the
 * given radius; 0 when the ray never passes within it (or starts inside).
 */
float cylinderEntry(vec3 ro, vec3 rd, vec3 center, float radius) {
    float a = rd.x * rd.x + rd.z * rd.z;
    if (a <= 1e-6)
        return 0.0;
    float ox = ro.x - center.x;
    float oz = ro.z - center.z;
    float b = 2.0 * (ox * rd.x + oz * rd.z);
    float c = ox * ox + oz * oz - radius * radius;
    float disc = b * b - 4.0 * a * c;
    if (disc <= 0.0)
        return 0.0;
    float t0 = (-b - sqrt(disc)) / (2.0 * a);
    float t1 = (-b + sqrt(disc)) / (2.0 * a);
    // Both roots negative: the cylinder lies entirely behind the camera and is
    // never entered — a huge entry makes the visibility fraction negative so
    // the depth factor occludes the glow instead of clamping the entry to 0.
    if (t1 < 0.0)
        return 1e9;
    return max(t0, 0.0);
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

    // --- Ray setup (world space), mirrors the mist shader ---

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

    // --- Analytic glow accumulation ---
    // The glow is the rod's own light: no scattering, no absorption — just an
    // additive column of light shaped by the closest approach to the rod's axis
    // segment, soft vertical caps and a depth-based occlusion fade (the surface
    // cuts the visible span of the cylinder).

    vec3 glow = vec3(0.0);
    for (int i = 0; i < RodCount; i++) {
        vec3 center = vec3(RodData[i * 6], RodData[i * 6 + 1], RodData[i * 6 + 2]);
        float radius = RodData[i * 6 + 3];
        float height = RodData[i * 6 + 4];
        float intensity = RodData[i * 6 + 5];
        if (intensity <= 0.0 || radius <= 0.0 || height <= 0.0)
            continue;

        vec3 closest = closestToSegment(rayStart, rayDir, center, height, rayLength);
        float dist = closest.y;
        float u = closest.z;

        // radial falloff: a soft gaussian so the glow reads as light, not as the
        // rod's surface
        float rd2 = dist / radius;
        float radial = exp(-2.0 * rd2 * rd2);
        if (radial < 0.01)
            continue;

        // volumetric path weighting: the light is proportional to the ray's path
        // through the cylinder — longest at the axis, naturally zero at the
        // silhouette. Without it every ray crossing the axis reads full
        // brightness and the glow renders as a solid curved surface.
        float pathChord = 2.0 * sqrt(max(radius * radius - dist * dist, 0.0));
        float pathWeight = min(pathChord / max(2.0 * radius, 1e-4), 1.0);
        float volumetric = pathWeight * 0.85 + 0.15 * radial;

        // soft vertical caps, extending one block beyond the rod
        float vertical = smoothstep(-1.0, 1.0, u) * (1.0 - smoothstep(height - 1.0, height + 1.0, u));
        if (vertical <= 0.0)
            continue;

        // depth occlusion: visible only while the scene surface lies beyond the
        // cylinder's front; fade as the surface cuts into the cylinder
        float tIn = cylinderEntry(rayStart, rayDir, center, radius);
        float chord = 2.0 * sqrt(max(radius * radius - dist * dist, 0.0)) + 0.5;
        float vis = (rayLength - tIn) / chord;
        float depthFactor = smoothstep(0.0, 1.0, vis);
        if (depthFactor <= 0.0)
            continue;

        glow += GlowColor * (GlowStrength * intensity) * volumetric * vertical * depthFactor;
    }

    fragColor = sceneColor;
    fragColor.rgb += glow;
    fragColor.a = sceneColor.a;
    gl_FragDepth = sceneDepth;
}
