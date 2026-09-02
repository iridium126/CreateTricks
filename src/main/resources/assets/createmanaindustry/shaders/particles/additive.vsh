// Additive billboard particle vertex shader. Draws via glDrawArraysIndirect:
// 6 vertices per instance (2 triangles), corner derived from gl_VertexID.
// Instances walk the additive permutation (orderAdd) written by keygen.comp —
// the frustum-culled dense list of visible additive particles. Per-instance
// data is fetched from the particle SSBO, presentation params (size/color
// curves, tint) from the emitter SSBO.
uniform mat4 ModelViewMat;
uniform mat4 ProjMat;
uniform vec3 uCamPos;
uniform vec3 uCamRight;
uniform vec3 uCamUp;

layout(std430, binding = BIND_POOL_WRITE) readonly buffer ParticleRead { vec4 data[]; } particles; // freshly written pool
layout(std430, binding = BIND_EMITTER) readonly buffer EmitterBuf { vec4 u[]; } emitters;
layout(std430, binding = BIND_ORDER_ADD) readonly buffer OrderAddBuf { uint order[]; } orderAdd;

out vec2 vUv;
out vec3 vColor;
out float vAlpha;
out float vDist;
// colorMode 4 (Hexcasting pigment) marker for the fsh: hexagonal cloud shape
flat out float vHex;

vec2 quadCorner(int v) {
    switch (v) {
        case 0: return vec2(0.0, 0.0);
        case 1: return vec2(1.0, 0.0);
        case 2: return vec2(0.0, 1.0);
        case 3: return vec2(0.0, 1.0);
        case 4: return vec2(1.0, 0.0);
        default: return vec2(1.0, 1.0);
    }
}

void main() {
    uint inst = orderAdd.order[gl_InstanceID];
    uint base = inst * 4u;
    vec4 p0 = particles.data[base + 0u];
    vec4 p1 = particles.data[base + 1u];
    vec4 p2 = particles.data[base + 2u];
    vec4 p3 = particles.data[base + 3u];

    float life = clamp(p3.x / max(p3.y, 1e-5), 0.0, 1.0);
    uint eid = floatBitsToUint(p3.w);
    uint hb = eid * VEC4_PER_EMITTER;

    // size over lifetime (analytic curve, eased)
    float sizeStart = emitters.u[hb + 5u].z;
    float sizeEnd = emitters.u[hb + 5u].w;
    // pow(life, 0) at life=0 is undefined GLSL -- keep the exponent positive
    float sizeEase = max(emitters.u[hb + 6u].x, 0.001);
    float size = mix(sizeStart, sizeEnd, pow(life, sizeEase)) * p0.w;

    // color + alpha over lifetime (keyframe interpolation, blocks hb+8..hb+15)
    int cc = int(emitters.u[hb + 6u].z);
    cc = max(1, min(8, cc));
    vec3 col;
    float keyA = 1.0;
    if (cc <= 1) {
        vec4 c0 = emitters.u[hb + 8u];
        col = c0.rgb;
        keyA = c0.a;
    } else {
        float x = life * float(cc - 1);
        int i = min(cc - 2, int(floor(x)));
        float f = fract(x);
        vec4 c0 = emitters.u[hb + 8u + uint(i)];
        vec4 c1 = emitters.u[hb + 8u + uint(min(cc - 1, i + 1))];
        col = mix(c0.rgb, c1.rgb, f);
        keyA = mix(c0.a, c1.a, f);
    }
    float hexShape = 0.0;
    // colorMode (header 17.x): 4 = Hexcasting pigment wheel. The 8 keyframe
    // slots hold a WHEEL sampled from the caster's pigment at spray time (not
    // a life gradient): the phase is the particle's velocity direction
    // projected on the fixed gradient axis, cubic-eased between adjacent
    // wheel colors exactly like ADPigment.morphBetweenColors. The per-spray
    // time phase is baked into the samples (vanilla freezes each particle's
    // color at its spawn instant, so direction is the only live dimension
    // within one spray). Plus the exact ConjureParticle shrink: quadSize
    // ×= 0.96 per tick = e^(-ln(1/0.96)·20·age).
    float colorMode = emitters.u[hb + 17u].x;
    if (colorMode > 3.5) {
        vec3 v = p1.xyz;
        float vlen = length(v);
        vec3 n = vlen > 1e-5 ? v / vlen : vec3(0.0, 1.0, 0.0);
        // MUST match HexSpecs.GRADIENT_DIR = normalize(0.3, 0.8, 0.5)
        float g = dot(n, normalize(vec3(0.3, 0.8, 0.5))) * 0.5 + 0.5;
        float fIdx = g * 8.0;
        int wbase = min(7, int(floor(fIdx)));
        float tRaw = fract(fIdx);
        float t = tRaw < 0.5 ? 4.0 * tRaw * tRaw * tRaw
                : 1.0 - pow(-2.0 * tRaw + 2.0, 3.0) / 2.0;
        vec4 c0 = emitters.u[hb + 8u + uint(wbase)];
        vec4 c1 = emitters.u[hb + 8u + uint((wbase + 1) & 7)];
        col = mix(c0.rgb, c1.rgb, t);
        keyA = mix(c0.a, c1.a, t);
        hexShape = 1.0;
        size *= exp(-0.816432 * p3.x);
    }
    col *= p2.rgb;

    // per-emitter glow multiplier (header u[hb+6].w) — the additive brightness
    // is allowed to exceed 1.0 (saturates against the user's target while
    // still reading correctly through additive blending).
    float emitterGlow = emitters.u[hb + 6u].w;

    // camera-facing billboard corner (with per-particle roll)
    vec2 corner = quadCorner(gl_VertexID) - 0.5;
    float ang = p1.w;
    vec2 roll = vec2(cos(ang) * corner.x - sin(ang) * corner.y,
                     sin(ang) * corner.x + cos(ang) * corner.y);
    vec3 worldPos = p0.xyz + uCamRight * (roll.x * size) + uCamUp * (roll.y * size);

    // The level stage model-view on NeoForge is the camera rotation only
    // (geometry is camera-relative), so subtract the camera position here.
    vec4 clip = ProjMat * ModelViewMat * vec4(worldPos - uCamPos, 1.0);
    gl_Position = clip;

    vUv = corner + 0.5;
    vDist = length(worldPos - uCamPos);
    // alpha: per-particle intensity × keyframe alpha × lifetime fade × emitter glow
    // (additive allows >1.0 here; brightness is trimmed per-emitter in the headers)
    vAlpha = p2.w * keyA * (1.0 - life) * emitterGlow;
    vColor = col;
    vHex = hexShape;
}
