// Textured billboard particle vertex shader, two modes selected by uMode:
//   0 — ALPHA blended: walks the COMBINED translucent sort array (depth-sorted
//       back-to-front together with the MODEL translucent parts). Its draw
//       command's instanceCount is the COMBINED item total -- keygen writes
//       the same value into BOTH translucent commands' count fields -- so
//       clipping non-sprite items here (payload type != 0) is required, not
//       defensive.
//   1 — OPAQUE cutout: walks the dense orderOpaque permutation, no sorting.
// Per-particle data is fetched from the particle SSBO; texture frame, spin and
// tint are derived from the per-particle seed / emitter header.
uniform mat4 ModelViewMat;
uniform mat4 ProjMat;
uniform vec3 uCamPos;
uniform vec3 uCamRight;
uniform vec3 uCamUp;
uniform float uAtlasCols;
uniform float uAtlasRows;
uniform int uMode; // 0 = blended (sorted walk), 1 = opaque cutout permutation

layout(std430, binding = BIND_POOL_WRITE) readonly buffer ParticleRead { vec4 data[]; } particles; // freshly written pool
layout(std430, binding = BIND_EMITTER) readonly buffer EmitterBuf { vec4 u[]; } emitters;
layout(std430, binding = BIND_SORT_WRITE) readonly buffer SortBuf { uvec2 kv[]; } sort;
layout(std430, binding = BIND_ORDER_OPAQUE) readonly buffer OrderOpaqueBuf { uint order[]; } orderOpaque;

out vec2 vUv;      // atlas-space UV
out vec3 vColor;
out float vAlpha;
out float vDist;

float hash1(float p) {
    p = fract(p * 0.1031);
    p *= p + 33.33;
    p *= p + p;
    return fract(p);
}

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
    // resolve this instance's particle: blended mode walks the combined
    // translucent sort array (payload = particleIndex<<2 | itemType, type 0 =
    // sprite); opaque mode walks the dense unsorted permutation
    uint inst;
    if (uMode == 1) {
        inst = orderOpaque.order[gl_InstanceID];
    } else {
        uvec2 item = sort.kv[gl_InstanceID];
        if ((item.y & 3u) != 0u) { // model-type item — belongs to the other draw
            gl_Position = vec4(0.0, 0.0, 2.0, 1.0);
            return;
        }
        inst = item.y >> 2;
    }
    uint base = inst * 4u;
    vec4 p0 = particles.data[base + 0u];
    vec4 p1 = particles.data[base + 1u];
    vec4 p2 = particles.data[base + 2u];
    vec4 p3 = particles.data[base + 3u];

    float life = clamp(p3.x / max(p3.y, 1e-5), 0.0, 1.0);
    uint eid = floatBitsToUint(p3.w);
    uint hb = eid * VEC4_PER_EMITTER;

    float sizeStart = emitters.u[hb + 5u].z;
    float sizeEnd = emitters.u[hb + 5u].w;
    float sizeEase = emitters.u[hb + 6u].x;
    float size = mix(sizeStart, sizeEnd, pow(life, sizeEase)) * p0.w;

    // color tint over lifetime (headers hb+8..hb+15)
    int cc = max(1, min(8, int(emitters.u[hb + 6u].z)));
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
    col *= p2.rgb;

    // atlas frame: fixed per particle (random pick, like vanilla's 12 sprites)
    float sc = emitters.u[hb + 16u].y;
    uint frame = (sc > 1.0) ? min(uint(sc) - 1u, uint(floor(hash1(p3.z * 7.13) * sc))) : 0u;
    uint fcol = frame % uint(uAtlasCols);
    uint frow = frame / uint(uAtlasCols);
    vec2 frameSize = vec2(1.0) / vec2(uAtlasCols, uAtlasRows);
    vec2 frameOrigin = vec2(uAtlasCols > 0.0 ? float(fcol) : 0.0, uAtlasRows > 0.0 ? float(frow) : 0.0) * frameSize;

    // billboard corner with roll; vanilla cherry spins over life (spin header 7.w)
    vec2 cornerUn = quadCorner(gl_VertexID) - 0.5;
    float roll;
    if (emitters.u[hb + 7u].w > 0.5) {
        float t = p3.x;
        float v0 = (hash1(p3.z * 1.31) > 0.5 ? 30.0 : -30.0); // deg/tick
        float a0 = (hash1(p3.z * 2.47) > 0.5 ? 5.0 : -5.0);   // deg/tick^2
        float v0r = radians(v0 * 20.0);                       // deg/tick -> rad/s (x20)
        float a0r = radians(a0 * 400.0);                      // deg/tick^2 -> rad/s^2 (x20^2)
        roll = p1.w + v0r * t + 0.5 * a0r * t * t;
    } else {
        roll = p1.w;
    }
    vec2 rollv = vec2(cos(roll) * cornerUn.x - sin(roll) * cornerUn.y,
                      sin(roll) * cornerUn.x + cos(roll) * cornerUn.y);
    vec3 worldPos = p0.xyz + uCamRight * (rollv.x * size) + uCamUp * (rollv.y * size);

    vec4 clip = ProjMat * ModelViewMat * vec4(worldPos - uCamPos, 1.0);
    gl_Position = clip;

    vec2 localUv = cornerUn + 0.5;
    vUv = frameOrigin + localUv * frameSize;
    vDist = length(worldPos - uCamPos);
    // constant alpha over life (vanilla petals do not fade); sprite alpha in fsh
    vAlpha = p2.w * keyA;
    vColor = col;
}
