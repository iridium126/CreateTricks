// ALLVR terrain vertex shader — V0 Tier B (doc §7.3/§9.4/§9.5).
// One glMultiDrawElementsIndirect over per-cube commands; no vertex
// attributes. Per command: firstIndex=0, baseVertex=quadStart*4,
// baseInstance=cubeSlot. The shared relative index pattern yields
// gl_VertexID = baseVertex + 4*quadLocal + corner, so:
//   quad index  = (baseVertex>>2) + ((gl_VertexID-baseVertex)>>2)
//   corner      =  (gl_VertexID-baseVertex) & 3   (0,0)/(1,0)/(1,1)/(0,1)
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

const vec3 AXIS[3] = vec3[3](vec3(1, 0, 0), vec3(0, 1, 0), vec3(0, 0, 1));

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
    uint stateId = ((lo >> 28u) & 0xFFFFu) | (hi << 4u); // 16-bit id across the 36-bit boundary
    // NOTE: id occupies bits 28..43 of the 64-bit word → low 4 bits in .x,
    // high 12 in .y low bits.

    vec4 rect = texelFetch(uStateTable, int(stateId) * 3 + 0);
    vTint = texelFetch(uStateTable, int(stateId) * 3 + 1);
    vInset = texelFetch(uStateTable, int(stateId) * 3 + 2);
    vRect = rect;

    // (u,v) basis per (axis,dir) — the mesher chose them so u×v = the outward
    // face normal:
    //   +x: u=y v=z | -x: u=z v=y | +y: u=z v=x | -y: u=x v=z | +z: u=x v=y | -z: u=y v=x
    uint faceIdx = axis * 2u + dir;
    uint uAxis = faceIdx == 0u ? 1u : faceIdx == 1u ? 2u : faceIdx == 2u ? 2u
               : faceIdx == 3u ? 0u : faceIdx == 4u ? 0u : 1u;
    uint vAxis = faceIdx == 0u ? 2u : faceIdx == 1u ? 1u : faceIdx == 2u ? 0u
               : faceIdx == 3u ? 2u : faceIdx == 4u ? 1u : 0u;

    vec3 au = AXIS[uAxis];
    vec3 av = AXIS[vAxis];
    vec3 aw = AXIS[axis];
    vec2 c = vec2(corner == 1u || corner == 2u ? 1.0 : 0.0,
                  corner >= 2u ? 1.0 : 0.0);
    float plane = float(w) + (dir == 0u ? 1.0 : 0.0);
    vec3 local = au * (float(u) + c.x * float(sizeU))
               + av * (float(v) + c.y * float(sizeV))
               + aw * plane;

    ivec3 origin = cubeInfo[gl_BaseInstance].xyz;
    vec3 relPos = vec3(origin - uCamInt) - uCamFrac + local;

    gl_Position = ProjMat * ModelViewMat * vec4(relPos, 1.0);

    vUvLocal = vec2(float(u) + c.x * float(sizeU), float(v) + c.y * float(sizeV));

    // face shade (vanilla directional constants): x 0.6, y+ 1.0, y- 0.5, z 0.8
    vec3 normal = aw * (dir == 0u ? 1.0 : -1.0);
    vShade = normal.y > 0.5 ? 1.0 : (normal.y < -0.5 ? 0.5 : (abs(normal.x) > 0.5 ? 0.6 : 0.8));

    vec4 viewPos = ModelViewMat * vec4(relPos, 1.0);
    vDist = length(viewPos.xyz);
}
