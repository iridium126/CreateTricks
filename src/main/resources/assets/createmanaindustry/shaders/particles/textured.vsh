// Textured billboard particle vertex shader, two modes selected by uMode:
//   0 — ALPHA blended: walks the ALPHA PARTITION of the type-partitioned sort
//       array (depth-sorted back-to-front within its type; MODEL items live in
//       the lower partition [0, N_model)). The partition start IS the model
//       segments' exact instanceCount -- keygen increments IDX_CNT_MODELOP
//       once per MODEL item and IDX_CNT_ALPHA once per sprite item, so every
//       command launches exactly its own items and nothing is filtered out.
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
// Vanilla lightmap (Minecraft's LightTexture): combat OPAQUE particles sample
// it with the spawn-time packed light (p2.w = blockLight + 16·skyLight, header
// lightMode 17.z). Bound at texture unit 2 by the engine's draw pass.
uniform sampler2D uLightmap;

layout(std430, binding = BIND_POOL_WRITE) readonly buffer ParticleRead { vec4 data[]; } particles; // freshly written pool
layout(std430, binding = BIND_EMITTER) readonly buffer EmitterBuf { vec4 u[]; } emitters;
layout(std430, binding = BIND_SORT_WRITE) readonly buffer SortBuf { uvec2 kv[]; } sort;
layout(std430, binding = BIND_ORDER_OPAQUE) readonly buffer OrderOpaqueBuf { uint order[]; } orderOpaque;
// bound only while the blended draw runs: supplies the ALPHA partition start
layout(std430, binding = BIND_INDIRECT) readonly buffer IndirectBuf { uint cmd[INDIRECT_UINTS]; } indirect;

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
        // sprites occupy [N_model, N_total); N_model is cmd2's exact count.
        // The type check is a corruption guard only -- on a healthy partition
        // every item in our range is sort-type 1 (sprite).
        uvec2 item = sort.kv[indirect.cmd[IDX_CNT_MODELOP] + gl_InstanceID];
        if ((item.y & 3u) != 1u) { // model-type item would mean upstream corruption
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
    // pow(life, 0) at life=0 is undefined GLSL -- keep the exponent positive
    float sizeEase = max(emitters.u[hb + 6u].x, 0.001);
    float size = mix(sizeStart, sizeEnd, pow(life, sizeEase)) * p0.w;

    // Combat feature flags (reserved header slots; 0 for every legacy preset):
    // 16.z growIn -- vanilla CritParticle.getQuadSize ramps 0→full over the
    // first 1/32 of life; 16.w spriteAnim -- poof's setSpriteFromAge frame
    // walk; 17.x colorMode -- seed-derived CritParticle/MagicProvider gray
    // with per-life reddening; 17.z lightMode -- p2.w carries the spawn-time
    // packed light; 17.w frameBase -- fixed atlas frame (single-frame sprites).
    float growIn = emitters.u[hb + 16u].z;
    float spriteAnim = emitters.u[hb + 16u].w;
    float colorMode = emitters.u[hb + 17u].x;
    float lightMode = emitters.u[hb + 17u].z;
    float frameBase = emitters.u[hb + 17u].w;
    if (growIn > 0.5)
        size *= min(1.0, life * 32.0);

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

    // combat colorMode: CritParticle/MagicProvider seed-gray with per-life
    // reddening (gCol·0.96, bCol·0.9 per tick → e^-0.816/s, e^-2.107/s), or
    // the poof's constant gray — replaces the keyframe colour entirely
    if (colorMode > 0.5) {
        float f0 = mix(0.6, 0.9, hash1(p3.z * 3.71));
        float gCh = f0 * exp(-0.816 * p3.x);
        float bCh = f0 * exp(-2.107 * p3.x);
        if (colorMode > 1.5 && colorMode < 2.5)
            col = vec3(f0 * 0.3, f0 * 0.8 * gCh, bCh); // MagicProvider tint
        else if (colorMode > 2.5)
            col = vec3(mix(0.7, 1.0, hash1(p3.z * 5.13))); // poof constant gray
        else
            col = vec3(f0, gCh, bCh);
        col *= p2.rgb;
    }
    // lightMode: p2.w is blockLight + 16·skyLight — sample the real vanilla
    // lightmap exactly where the particle vertex shader's texelFetch(Sampler2)
    // would; integer-centred UVs make the lightmap's LINEAR filter exact
    if (lightMode > 0.5) {
        float lv = floor(p2.w + 0.5);
        float bl = mod(lv, 16.0);
        float sl = floor(lv / 16.0);
        col *= texture(uLightmap, vec2((bl + 0.5) / 16.0, (sl + 0.5) / 16.0)).rgb;
    }

    // atlas frame: spriteAnim walks the frames over life (vanilla
    // setSpriteFromAge, poof); a fixed frameBase pins single-frame combat
    // sprites; otherwise the legacy seed-random pick (like vanilla's 12 cherry
    // sprites)
    float sc = emitters.u[hb + 16u].y;
    uint frame;
    if (spriteAnim > 0.5) {
        frame = uint(frameBase) + min(uint(sc) - 1u, uint(floor(life * sc)));
    } else if (frameBase > 0.5) {
        frame = uint(frameBase);
    } else {
        frame = (sc > 1.0) ? min(uint(sc) - 1u, uint(floor(hash1(p3.z * 7.13) * sc))) : 0u;
    }
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

    // Vanilla pairs the quad TOP with minV (SingleQuadParticle.renderRotatedQuad:
    // yOffset +1 ↔ v0), and NativeImage row 0 uploads to v=0 — so v is mirrored
    // against the corner y or every sprite stands upside down (cherry blobs
    // hide it; the damage heart exposes it)
    vec2 localUv = vec2(cornerUn.x + 0.5, 0.5 - cornerUn.y);
    vUv = frameOrigin + localUv * frameSize;
    vDist = length(worldPos - uCamPos);
    // constant alpha over life (vanilla petals do not fade); sprite alpha in fsh
    vAlpha = p2.w * keyA;
    vColor = col;
}
