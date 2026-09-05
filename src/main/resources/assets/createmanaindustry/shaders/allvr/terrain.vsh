// ALLVR terrain vertex shader — V0 Tier B (doc §7.3/§9.4/§9.5).
// One glMultiDrawElementsIndirect over per-cube commands; no vertex
// attributes. Per command: firstIndex=0, baseVertex=quadStart*4,
// baseInstance=cubeSlot. The shared relative index pattern yields
// gl_VertexID = baseVertex + 4*quadLocal + corner, so:
//   quad index  = (baseVertex>>2) + ((gl_VertexID-baseVertex)>>2)
//   corner      =  (gl_VertexID-baseVertex) & 3   (0,0)/(1,0)/(0,1)/(1,1)
// Camera-relative math (±30M float32 jitter guard, doc §9.5): the cube
// origin is stored ABSOLUTE in cubeInfo (exact ivec3); the subtraction
// origin - uCamInt happens in integer space (exact), the camera's
// sub-block fraction arrives separately as uCamFrac. No float32 world
// coordinate ever exceeds the view distance in magnitude.
uniform mat4 ModelViewMat;
uniform mat4 ProjMat;
uniform ivec3 uCamInt;
uniform vec3 uCamFrac;

layout(std430, binding = BIND_QUADS) readonly buffer QuadBuf {
    uvec2 quads[]; // 8-byte packed quad (doc §7.3)
};
layout(std430, binding = BIND_CUBEINFO) readonly buffer CubeInfoBuf {
    ivec4 cubeInfo[]; // xyz = absolute cube origin
};
layout(binding = STATE_TBO_UNIT) uniform samplerBuffer uStateTable;

out vec2 vUvLocal;   // un-tiled quad-space uv (0..size), tiled in the fsh
flat out vec4 vRect; // atlas sprite rect (u0, v0, du, dv)
flat out vec4 vTint; // rgb tint + renderable flag
flat out vec4 vInset; // half-texel inset (tile space), xy used
flat out float vShade;  // vanilla-style directional face shade
flat out float vDist;   // view distance for fog
// iris-integration outputs (consumed by the patched fsh seam; the unpatched
// program leaves them unused — the compiler strips them)
flat out vec2 vLight;    // baked light nibbles: (block/15, sky/15)
flat out float vCustomId; // iris block material id (state table spare texel z)
flat out uint vFaceVoxy;  // voxy face encoding: axis(x2,y0,z1)<<1 | dir(+=1)

const vec3 AXIS[3] = vec3[3](vec3(1, 0, 0), vec3(0, 1, 0), vec3(0, 0, 1));

#ifdef ALLVR_SHADOW_PASS
// Pack shadow-map distortion (doc 4i 排查⑧): the pack's own shadow.vsh
// compresses shadow clip space radially (higher texel density near the
// player) and rescales depth, and its deferred/composite stages sample with
// the same mapping — our depth-only contribution must land on the same
// texels or the pack never sees our shadows. Mirror of
// mist_volumetric_iris.fsh distort_shadow_space (mode ids identical):
//   0 none | 1 quartic (Photon) | 2 euclidean (Complementary lineage)
//   3 log (Bliss) | 4 Sundial | 5 iterationT
uniform int uShadowDistortionMode;
uniform float uShadowDistortion;
uniform float uShadowDepthScale;
uniform vec4 uShadowLogParams; // mode 3: k, a, b, depth scale

float allvrQuarticLength(vec2 v) {
    vec2 p = v * v; p = p * p;
    return sqrt(sqrt(p.x + p.y));
}

vec3 allvrDistortShadowSpace(vec3 clip) {
    if (uShadowDistortionMode == 0) {
        return vec3(clip.xy, clip.z * uShadowDepthScale);
    }
    if (uShadowDistortionMode == 3) {
        float logFactor = log(length(clip.xy) * uShadowLogParams.z + uShadowLogParams.y)
            * uShadowLogParams.x;
        return vec3(clip.xy / logFactor, clip.z * uShadowLogParams.w);
    }
    if (uShadowDistortionMode == 4) {
        float len = length(clip.xy);
        float curve = log(uShadowDistortion * len + 1.0) / log(uShadowDistortion + 1.0);
        vec2 direction = len > 1e-5 ? clip.xy / len : vec2(0.0);
        return vec3(direction * curve * 0.5 + 0.5, clip.z * uShadowDepthScale);
    }
    if (uShadowDistortionMode == 5) {
        float len = length(clip.xy);
        float factor = len * uShadowDistortion + (1.0 - uShadowDistortion);
        float z = (clip.z - ProjMat[3].z) * uShadowDepthScale;
        return vec3(clip.xy / factor * 0.95, z);
    }
    float l = uShadowDistortionMode == 2 ? length(clip.xy) : allvrQuarticLength(clip.xy);
    float factor = l * uShadowDistortion + (1.0 - uShadowDistortion);
    return vec3(clip.xy / factor, clip.z * uShadowDepthScale);
}
#endif

void main() {
    uint baseVertex = gl_BaseVertex;
    uint rel = gl_VertexID - baseVertex;
    uint quadLocal = rel >> 2u;
    uint corner = rel & 3u;

    uvec2 qw = quads[(baseVertex >> 2u) + quadLocal]; // NB: "packed" is a GLSL reserved word
    uint lo = qw.x;
    uint hi = qw.y;

    uint axis = lo & 3u;
    uint dir = (lo >> 2u) & 1u;
    uint sizeU = ((lo >> 3u) & 31u) + 1u;
    uint sizeV = ((lo >> 8u) & 31u) + 1u;
    uint u = (lo >> 13u) & 31u;
    uint v = (lo >> 18u) & 31u;
    uint w = (lo >> 23u) & 31u;
    // id occupies bits 28..43 of the 64-bit word → low 4 bits in .x[28..31],
    // high 12 in .y[0..11]; .y[12..19] carry the mesher-baked light (sky/block)
    // and MUST be masked out — an unmasked `hi << 4` folds them into the
    // stateId and every sunlit face (sky=15) fetched a garbage state entry
    // (renderable flag 0 → discarded: "top faces invisible" regression)
    uint stateId = ((lo >> 28u) & 0xFu) | ((hi & 0xFFFu) << 4u);

    // (u,v) basis per (axis,dir) — the mesher chose them so u×v = the outward
    // face normal:
    //   +x: u=y v=z | -x: u=z v=y | +y: u=z v=x | -y: u=x v=z | +z: u=x v=y | -z: u=y v=x
    uint faceIdx = axis * 2u + dir;
    uint uAxis = faceIdx == 0u ? 1u : faceIdx == 1u ? 2u : faceIdx == 2u ? 2u
               : faceIdx == 3u ? 0u : faceIdx == 4u ? 0u : 1u;
    uint vAxis = faceIdx == 0u ? 2u : faceIdx == 1u ? 1u : faceIdx == 2u ? 0u
               : faceIdx == 3u ? 2u : faceIdx == 4u ? 1u : 0u;

    // per-face material: the table stores 6 faces × (uvRect, tint+flag, inset)
    // per state in AllvrMesher.FACES order (= faceIdx); untinted faces carry a
    // white tint (identity multiply)
    int entry = int(stateId) * STATE_TEXELS + int(faceIdx) * STATE_TEXELS_PER_FACE;
    vRect  = texelFetch(uStateTable, entry + 0);
    vTint  = texelFetch(uStateTable, entry + 1);
    vInset = texelFetch(uStateTable, entry + 2);
    vCustomId = vInset.z;

    vec3 au = AXIS[uAxis];
    vec3 av = AXIS[vAxis];
    vec3 aw = AXIS[axis];
    // corner table is z-order, not perimeter order: the shared index pattern
    // (0,1,2)(2,1,3) reuses the v1–v2 edge, so v1 and v2 must be DIAGONAL
    // corners — a perimeter-order table flips triangle 2's winding (back-face
    // culled) and tiles only half of every quad
    vec2 c = vec2(corner == 1u || corner == 3u ? 1.0 : 0.0,
                  corner >= 2u ? 1.0 : 0.0);
    float plane = float(w) + (dir == 0u ? 1.0 : 0.0);
    vec3 local = au * (float(u) + c.x * float(sizeU))
               + av * (float(v) + c.y * float(sizeV))
               + aw * plane;

    ivec3 origin = cubeInfo[gl_BaseInstance].xyz;
    vec3 relPos = vec3(origin - uCamInt) - uCamFrac + local;

    // Texture coords follow the vanilla FaceInfo/BlockFaceUV table (derived
    // from FaceBakery.makeVertices + BlockFaceUV.getU/getV, uv [0,0,16,16]) —
    // NOT the mesher's winding basis. The 16-offsets vanish under fract, so
    // per face (x,y,z = cube-local block coords):
    //   EAST (-z,-y) | WEST (z,-y) | UP (x,z) | DOWN (x,-z) | SOUTH (x,-y) | NORTH (-x,-y)
    // ±1-per-block slope tiles the sprite once per block, matching vanilla
    // (opposite faces mirrored so textures read correctly from outside).
    vUvLocal = faceIdx == 0u ? vec2(-local.z, -local.y)
             : faceIdx == 1u ? vec2(local.z, -local.y)
             : faceIdx == 2u ? vec2(local.x, local.z)
             : faceIdx == 3u ? vec2(local.x, -local.z)
             : faceIdx == 4u ? vec2(local.x, -local.y)
             : vec2(-local.x, -local.y);

    // face shade (vanilla directional constants): x 0.6, y+ 1.0, y- 0.5, z 0.8
    vec3 normal = aw * (dir == 0u ? 1.0 : -1.0);
    vShade = normal.y > 0.5 ? 1.0 : (normal.y < -0.5 ? 0.5 : (abs(normal.x) > 0.5 ? 0.6 : 0.8));

    // voxy face encoding (the patch's parameter.contract): Photon decodes
    // normal = axis-slot(x=2,y=0,z=1) with sign from bit0 (+ = 1)
    uint voxyAxis = axis == 0u ? 2u : (axis == 1u ? 0u : 1u);
    vFaceVoxy = (voxyAxis << 1u) | (dir == 0u ? 1u : 0u);
    vLight = vec2(float((hi >> 16u) & 0xFu), float((hi >> 12u) & 0xFu)) * (1.0 / 15.0);

    vec4 viewPos = ModelViewMat * vec4(relPos, 1.0);
    vDist = length(viewPos.xyz);

    gl_Position = ProjMat * ModelViewMat * vec4(relPos, 1.0);
#ifdef ALLVR_TAA
    // pack TAA jitter (voxy contract: clip-space offset scaled by w, the
    // function body comes verbatim from the pack's voxy.json taaOffset)
    gl_Position.xy += voxy_taaOffset() * gl_Position.w;
#endif

#ifdef ALLVR_SHADOW_PASS
    gl_Position = vec4(allvrDistortShadowSpace(gl_Position.xyz), gl_Position.w);
#endif
}
