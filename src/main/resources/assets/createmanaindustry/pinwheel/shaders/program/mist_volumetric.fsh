#include veil:space_helper

uniform sampler2D DiffuseSampler0;
uniform sampler2D DiffuseDepthSampler;

uniform int AtomizerCount;
uniform float AtomizerData[192]; // packed: x,y,z,invRadiusSq,colorIndex,absorptionScale per source, max 32
uniform int PaletteCount;

uniform vec4 MistPalette[32]; // packed: r,g,b,emission per distinct fluid color
uniform float MistOpacity;
uniform float MistDensity;
uniform float MistStepScale;
uniform vec3 SunDirection;

in vec2 texCoord;
out vec4 fragColor;

#define MAX_STEPS 32
#define MIN_STEPS 8
#define STEP_GROWTH 0.15
#define NOISE_SCALE 0.25
#define PI 3.14159265359
#define ISOTROPIC_PHASE (0.25 / PI)
#define FBM_OCTAVES 2

// ---------------------------------------------------------------------------
// Hash functions (IQ / Dave Hoskins style, from Photon)
// ---------------------------------------------------------------------------

float hash1(vec3 p) {
    p = fract(p * 0.1031);
    p += dot(p, p.zyx + 31.32);
    return fract((p.x + p.y) * p.z);
}

// ---------------------------------------------------------------------------
// 3D Value noise
// ---------------------------------------------------------------------------

float valueNoise(vec3 p) {
    vec3 i = floor(p);
    vec3 f = fract(p);
    f = f * f * (3.0 - 2.0 * f);
    return mix(mix(mix(hash1(i + vec3(0,0,0)), hash1(i + vec3(1,0,0)), f.x),
                   mix(hash1(i + vec3(0,1,0)), hash1(i + vec3(1,1,0)), f.x), f.y),
               mix(mix(hash1(i + vec3(0,0,1)), hash1(i + vec3(1,0,1)), f.x),
                   mix(hash1(i + vec3(0,1,1)), hash1(i + vec3(1,1,1)), f.x), f.y), f.z);
}

float fbm(vec3 p) {
    float f = 0.0;
    float amp = 0.5;
    float freq = 1.0;
    for (int i = 0; i < FBM_OCTAVES; i++) {
        f += amp * valueNoise(p * freq);
        freq *= 2.0;
        amp *= 0.5;
    }
    return f;
}

// ---------------------------------------------------------------------------
// Phase function (from Photon phase_functions.glsl)
// ---------------------------------------------------------------------------

float henyey_greenstein_phase(float nu, float g) {
    float gg = g * g;
    float denom = 1.0 + gg - 2.0 * g * nu;
    return (ISOTROPIC_PHASE - ISOTROPIC_PHASE * gg) / (denom * sqrt(denom));
}

// ---------------------------------------------------------------------------
// Sun elevation model (mirrors Photon global.glsl / light_color.glsl)
// ---------------------------------------------------------------------------

float linear_step(float edge0, float edge1, float x) {
    return clamp((x - edge0) / (edge1 - edge0), 0.0, 1.0);
}

float sqr(float x) {
    return x * x;
}

// ---------------------------------------------------------------------------
// Mist concentration at a world-space position (our custom model)
// ---------------------------------------------------------------------------

/**
 * Returns vec2(concentration, index) where index is the source that has
 * the maximum concentration at the given world position. If no source
 * contributes, returns vec2(0.0, -1.0).
 */
vec2 getConcentrationAndIdx(vec3 worldPos) {
    // Sqrt-free dominant selection: conc = 1 - d/r is monotonically ordered by
    // d^2 / r^2, so the winner is the source minimizing d^2 / r^2, and only the
    // winner needs a single sqrt at the end (keeps the per-step cost flat when
    // the source count grows).
    float best = 1e9;
    float maxIdx = -1.0;
    for (int i = 0; i < AtomizerCount; i++) {
        vec4 atomizer = vec4(
            AtomizerData[i * 6],
            AtomizerData[i * 6 + 1],
            AtomizerData[i * 6 + 2],
            AtomizerData[i * 6 + 3]
        );
        float invR2 = atomizer.w;
        if (invR2 <= 0.0) // vanished source
            continue;
        float dx = worldPos.x - atomizer.x;
        float dy = worldPos.y - atomizer.y;
        float dz = worldPos.z - atomizer.z;
        float t = (dx * dx + dy * dy + dz * dz) * invR2; // d2/r2, multiply instead of divide
        if (t > 1.0) // outside the radius — contributes nothing
            continue;
        if (t < best) {
            best = t;
            maxIdx = float(i);
        }
    }
    if (maxIdx < 0.0)
        return vec2(0.0, -1.0);
    return vec2(1.0 - sqrt(best), maxIdx);
}

// ---------------------------------------------------------------------------
// Main
// ---------------------------------------------------------------------------

void main() {
    vec4 sceneColor = texture(DiffuseSampler0, texCoord);
    float sceneDepth = texture(DiffuseDepthSampler, texCoord).r;

    // No atomizers active — pass through
    if (AtomizerCount <= 0) {
        fragColor = sceneColor;
        gl_FragDepth = sceneDepth;
        return;
    }

    // --- Ray setup (world space) ---

    vec3 worldEnd;
    if (sceneDepth >= 1.0) {
        vec3 viewDir = viewDirFromUv(texCoord);
        worldEnd = VeilCamera.CameraPosition + viewDir * 48.0;
    } else {
        worldEnd = screenToWorldSpace(texCoord, sceneDepth).xyz;
    }

    vec3 rayStart = VeilCamera.CameraPosition;
    vec3 rayDir = worldEnd - rayStart;
    float rayLengthSq = dot(rayDir, rayDir);
    float invRayLength = inversesqrt(rayLengthSq);
    float rayLength = rayLengthSq * invRayLength;
    rayDir *= invRayLength;
    rayLength = min(rayLength, 64.0);

    // --- Adaptive step count (Photon style) ---

    int stepCount = int(float(MIN_STEPS) + STEP_GROWTH * rayLength);
    stepCount = min(stepCount, MAX_STEPS);

    float stepSize = (rayLength / float(stepCount)) * MistStepScale;
    stepSize = max(stepSize, 0.1);

    // Recalculate with clamped step size
    stepCount = int(rayLength / stepSize);
    stepCount = min(stepCount, MAX_STEPS);

    // --- Ray-source bounds: skip the march entirely when the ray reaches no
    // source, and only march within the span the ray spends near any source.
    // Exact: a sample can only land inside a source if the ray passes within
    // its radius, which is exactly what the loop below tests. ---

    float marchStart = 1e9;
    float marchEnd = -1e9;
    for (int i = 0; i < AtomizerCount; i++) {
        vec4 atomizer = vec4(
            AtomizerData[i * 6],
            AtomizerData[i * 6 + 1],
            AtomizerData[i * 6 + 2],
            AtomizerData[i * 6 + 3]
        );
        float invR2 = atomizer.w;
        if (invR2 <= 0.0)
            continue;
        vec3 oc = atomizer.xyz - rayStart;
        float tc = clamp(dot(oc, rayDir), 0.0, rayLength);
        float d2 = dot(oc, oc) - tc * tc;
        float r2 = 1.0 / invR2;
        if (d2 <= r2) {
            float dt = sqrt(r2 - d2);
            marchStart = min(marchStart, max(tc - dt, 0.0));
            marchEnd = max(marchEnd, min(tc + dt, rayLength));
        }
    }
    if (marchEnd < marchStart) {
        // The ray reaches no mist volume — pass the scene through
        fragColor = sceneColor;
        gl_FragDepth = sceneDepth;
        return;
    }

    // --- Dither start to hide banding (from Photon r1) ---

    float dither = hash1(vec3(gl_FragCoord.xy, 0.0));

    float t = marchStart + stepSize * dither;

    // --- Ray march loop ---

    float LoV = dot(rayDir, SunDirection);

    // Per-pixel sun scattering — constant along the ray (only SunDirection and
    // the view ray matter), so hoisted out of the march.
    float miePhase = 0.7 * henyey_greenstein_phase(LoV, 0.5)
                   + 0.3 * henyey_greenstein_phase(LoV, -0.2);
    float sunElevation = SunDirection.y;
    float sunDayFactor = clamp(sunElevation * 50.0, 0.0, 1.0);
    float eveningGlow = 0.75 * linear_step(0.05, 1.0, exp(-300.0 * sqr(sunElevation + 0.02)));
    float scatter = 0.5 + sunDayFactor * (1.0 + eveningGlow) * 0.5 * miePhase;

    vec4 accumulatedMist = vec4(0.0);
    float transmittance = 1.0;

    for (int i = 0; i < stepCount; i++) {
        if (t > marchEnd) break;
        if (transmittance < 0.01) break;

        vec3 pos = rayStart + rayDir * t;
        vec2 ci = getConcentrationAndIdx(pos);
        float baseConc = ci.x;

        if (baseConc > 0.001 && ci.y >= 0.0) {
            // Modulate with procedural noise for natural mist wisps
            float noise = fbm(pos * NOISE_SCALE);
            float density = baseConc * (0.5 + 0.5 * noise) * MistDensity;

            // Per-source color from the fluid palette (dominant source)
            int idx = int(ci.y);
            int palIdx = clamp(int(AtomizerData[idx * 6 + 4] + 0.5), 0, max(PaletteCount - 1, 0));
            vec3 atomizerCol = MistPalette[palIdx].rgb;
            // Glowing fluids make the mist luminous: self-emission, brighter
            // toward the center (conc-weighted), composited with the scattered fog.
            vec3 mistLit = atomizerCol * scatter
                    + atomizerCol * MistPalette[palIdx].a * (0.5 + 0.5 * baseConc);

            // Front-to-back alpha compositing. Absorption uses the dominant
            // source's own scale (full while the camera sits inside it, very
            // weak from the outside) so the scene behind stays nearly un-dimmed.
            float stepDensity = density * stepSize;
            vec4 sampleCol = vec4(mistLit, density * MistOpacity);
            sampleCol.rgb *= sampleCol.a;
            accumulatedMist += sampleCol * transmittance;
            transmittance *= exp(-stepDensity * 0.5 * AtomizerData[idx * 6 + 5]);
        }

        t += stepSize;
    }

    fragColor = sceneColor * transmittance + accumulatedMist;
    fragColor.a = sceneColor.a;
    gl_FragDepth = sceneDepth;
}
