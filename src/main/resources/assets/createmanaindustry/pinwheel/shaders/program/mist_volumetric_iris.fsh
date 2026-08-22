// Iris gbuffer variant of mist_volumetric.
//
// Identical to the vanilla shader (depth-based ray endpoint + march cutoff at
// the scene surface, so solid geometry fully occludes the fog behind it), but
// compiled as a separate program: MistIrisHook manipulates its sampler
// uniforms manually, and doing that on the shared vanilla program polluted the
// vanilla post pipeline after a shaderpack switch (red screen). Keeping a
// dedicated program isolates all hook-side state.

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

// Tyndall effect — sun occlusion from the iris shadow map. Bound by
// MistIrisHook to shadowtex0 (the pack's directional-sun depth map).
uniform sampler2D ShadowMap0;
uniform int ShadowMapBound;          // 1 when ShadowMap0 is valid and bound
uniform mat4 ShadowModelView;
uniform mat4 ShadowProjection;
uniform float ShadowMapResolution;   // actual shadow texture width (texels)

// Pack shadow-map distortion (mirrors the shaderpack's own shadow.vsh). The pack
// renders its shadow map with these baked into gl_Position, so the mist sampling
// must apply the same remap. Photon ships 0.85 / 0.2; packs without distortion
// use 1.0 / 1.0. Populated from the active pack by MistIrisHook.
uniform float ShadowDistortion;
uniform float ShadowDepthScale;
// 0 = no distortion (identity), 1 = quartic (Photon SHADOW_DISTORTION),
// 2 = euclidean (Complementary shadowMapBias), 3 = logarithmic
// (Bliss BiasShadowProjection — curve parameters in ShadowLogParams).
uniform int ShadowDistortionMode;
uniform vec4 ShadowLogParams; // mode 3 only: x = k, y = a, z = b, w = z scale

// Output composition target: 0 = composite over the sampled scene colour
// (scene-colour packs' colortex0), 1 = premultiplied under-operator into the
// translucent layer (Bliss-family colortex2, stored at 0.1x with coverage alpha).
uniform int MistTargetMode;
// Translucent mode only: the pack's colortex4, whose texel (10,37).r carries the
// auto-exposure scalar its final composite multiplies the frame by.
uniform sampler2D ExposureSampler;
uniform int ExposureBound; // 1 when ExposureSampler is bound to the pack's colortex4

// DEBUG: when 1, mist is colored by the shadow sample (green = lit, red =
// occluded) so the Tyndall occlusion can be verified visually.
uniform int DebugShadowVisualization;

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
// Sun elevation model + Tyndall occlusion (mirrors Photon global.glsl /
// light_color.glsl / raymarched.glsl)
// ---------------------------------------------------------------------------

float linear_step(float edge0, float edge1, float x) {
    return clamp((x - edge0) / (edge1 - edge0), 0.0, 1.0);
}

float sqr(float x) {
    return x * x;
}

/** Photon quartic_length — sqrt(sqrt(x^4 + y^4)). */
float quartic_length(vec2 v) {
    return sqrt(sqrt(v.x * v.x * v.x * v.x + v.y * v.y * v.y * v.y));
}

/**
 * Remaps undistorted shadow clip coords into the shaderpack's distorted shadow
 * space, mirroring the pack's own distortion (Photon uses quartic_length with
 * SHADOW_DISTORTION, Complementary uses euclidean length with shadowMapBias,
 * Bliss uses a logarithmic length compression with its own k/a/b curve and a
 * z scale of 1/6). The pack renders its shadow map with this remap baked into
 * gl_Position, so skipping it samples the wrong texels (everything reads as
 * lit). Mode 0 is the identity (pack without distortion).
 * <p>
 * Extension point: to adapt a new pack's radial-length function, add a
 * {@code ShadowDistortionMode == N} branch here (matching the glslMode returned
 * by the pack's {@code ShadowDistortionConvention} on the Java side).
 */
vec3 distort_shadow_space(vec3 shadowClipPos) {
    if (ShadowDistortionMode == 0)
        return shadowClipPos;
    if (ShadowDistortionMode == 3) {
        // Bliss: distortFactor = log(len * b + a) * k; xy /= distortFactor;
        // depth is compressed separately by gl_Position.z /= 6.
        float logFactor = log(length(shadowClipPos.xy) * ShadowLogParams.z + ShadowLogParams.y)
                * ShadowLogParams.x;
        return vec3(shadowClipPos.xy / logFactor, shadowClipPos.z * ShadowLogParams.w);
    }
    float l = ShadowDistortionMode == 2
            ? length(shadowClipPos.xy)
            : quartic_length(shadowClipPos.xy);
    float distortionFactor = l * ShadowDistortion + (1.0 - ShadowDistortion);
    return vec3(shadowClipPos.xy / distortionFactor, shadowClipPos.z * ShadowDepthScale);
}

/**
 * Returns 1.0 where the sun reaches the world-space position and 0.0 where it
 * is occluded, by testing the iris shadow map. Mirrors Photon's
 * raymarch_air_fog shadow block: transform to shadow clip space (ortho
 * projection — no perspective divide), apply the pack's shadow distortion, then
 * compare the clip z against the packed depth. Out-of-bounds shadow texels are
 * treated as lit (Photon's clamp01(shadow_screen_pos) == shadow_screen_pos
 * branch), so geometry outside the shadow frustum never darkens the mist.
 */
float sampleSunOcclusion(vec3 worldPos) {
    if (ShadowMapBound == 0 || ShadowMapResolution < 1.0)
        return 1.0;

    vec3 shadowViewPos = mat3(ShadowModelView) * (worldPos - VeilCamera.CameraPosition)
                       + ShadowModelView[3].xyz;
    vec3 shadowClipPos = vec3(ShadowProjection[0].x, ShadowProjection[1].y, ShadowProjection[2].z)
                       * shadowViewPos + ShadowProjection[3].xyz;
    vec3 shadowScreen = distort_shadow_space(shadowClipPos) * 0.5 + 0.5;
    vec2 inBounds = step(vec2(0.0), shadowScreen.xy) * step(shadowScreen.xy, vec2(1.0));
    if (inBounds.x * inBounds.y < 0.5)
        return 1.0;
    float depth = texelFetch(ShadowMap0, ivec2(clamp(shadowScreen.xy * ShadowMapResolution,
            0.0, ShadowMapResolution - 1.0)), 0).x;
    return step(shadowScreen.z, depth);
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
    // the view ray matter), so hoisted out of the march. Only the self-emission
    // and its shadow cancellation vary per step.
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

            // Per-source color + self-emission (dominant source)
            int idx = int(ci.y);
            int palIdx = clamp(int(AtomizerData[idx * 6 + 4] + 0.5), 0, max(PaletteCount - 1, 0));
            vec3 atomizerCol = MistPalette[palIdx].rgb;
            float emission = MistPalette[palIdx].a * (0.5 + 0.5 * baseConc);

            // Tyndall: shadow-occlude the mist. God rays (shadow-modulated sun
            // scattering) are intentionally absent — the shaderpack's own
            // volumetric fog provides those for non-luminous mist. Here the shadow
            // fully cancels the luminous self-emission in shadow. Skipped for
            // non-glowing fluids (no shadow effect) unless the debug visualisation
            // is active.
            float shadowFactor = 1.0;
            if (DebugShadowVisualization == 1 || emission > 0.001) {
                float shadow = sampleSunOcclusion(pos);

                // DEBUG: visualize the shadow sampling as mist color — green = lit,
                // red = occluded — to verify the Tyndall occlusion visually.
                if (DebugShadowVisualization == 1) {
                    vec3 debugCol = shadow > 0.5 ? vec3(0.2, 1.0, 0.2) : vec3(1.0, 0.1, 0.1);
                    float stepDensity = density * stepSize;
                    vec4 debugSample = vec4(debugCol, density * MistOpacity);
                    debugSample.rgb *= debugSample.a;
                    accumulatedMist += debugSample * transmittance;
                    transmittance *= exp(-stepDensity * 0.5 * AtomizerData[idx * 6 + 5]);
                    t += stepSize;
                    continue;
                }
                shadowFactor = shadow;
            }

            // Ambient floor + uniform sun scattering + shadow-cancelled emission.
            vec3 mistLit = atomizerCol * scatter + atomizerCol * emission * shadowFactor;

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

    if (MistTargetMode == 1) {
        // Translucent-layer RMW (Bliss colortex2): the pack stores lit translucent
        // colour at 0.1x scale with a coverage alpha and merges it via
        // color*(1-a) + rgb*10. Compose the mist as an extra layer UNDER the
        // existing translucent content (premultiplied under-operator), keeping
        // the pack's storage scale:
        //   rgb_out = dst.rgb + mistPremult * 0.1 * (1 - dst.a)
        //   a_out   = dst.a   + mistA * (1 - dst.a)
        float covA = clamp(sceneColor.a, 0.0, 1.0);
        float mistA = clamp(accumulatedMist.a, 0.0, 1.0);
        // Bliss-family auto-exposure compensation: the pack's final composite
        // multiplies the frame by an exposure scalar (~0.02 at high noon,
        // approaching ~1 at night) read from colortex4 texel (10,37). Our mist
        // radiance is calibrated for the non-exposed pipelines of the other
        // packs, so pre-multiplying by the inverse cancels the exposure pass and
        // keeps the on-screen mist identical across all of them — automatically
        // tracking day/night, rain and caves through the pack's own metric.
        float sceneScale = 1.0;
        if (ExposureBound == 1) {
            float exposure = texelFetch(ExposureSampler, ivec2(10, 37), 0).r;
            if (exposure > 0.001)
                sceneScale = clamp(1.0 / exposure, 0.125, 32.0);
        }
        // Only the radiance scales — the alpha stays a geometric coverage and
        // must not grow with scene brightness.
        vec3 outRGB = sceneColor.rgb + accumulatedMist.rgb * sceneScale * 0.1 * (1.0 - covA);
        fragColor = vec4(outRGB, covA + mistA * (1.0 - covA));
        gl_FragDepth = sceneDepth;
        return;
    }

    fragColor = sceneColor * transmittance + accumulatedMist;
    fragColor.a = sceneColor.a;
    gl_FragDepth = sceneDepth;
}
