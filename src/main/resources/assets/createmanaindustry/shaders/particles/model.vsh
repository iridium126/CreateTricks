// MODEL particle vertex shader: instanced vanilla-allay rendering. Each
// instance reads the MODEL PARTITION of the type-partitioned sort array --
// sorted keys place all MODEL items (sort-type 0) at [0, N_model), so both
// sub-draws resolve instances as plain sortedKv[gl_InstanceID] and their
// commands' instanceCount is the exact N_model -- pulls its
// particle record from the pool, computes the full vanilla AllayModel pose
// procedurally in-shader (FLY / DANCE / SPIN / HOLD, header 17.x), and
// vertex-pulls the static indexed geometry (binding 12, float stride 7:
// pos.xyz units / uv normalised / partId / normal axis) — gl_VertexID is the
// element index.
//
// The mesh renders as TWO sub-draws issued by ONE glMultiDrawElementsIndirect.
// BOTH commands cover the same partition and differ ONLY in their
// element-buffer index range: cmd2 references the opaque parts
// (head/skin/arms, cutout + depth write in fsh), cmd3 the translucent parts
// (cloak + wings, alpha blend WITH depth writes so ghost surfaces occlude
// like tinted glass), depth-sorted together with the ALPHA sprites. Because the index ranges are disjoint, the segment id is derived
// purely from the vertex's own partId (>= 4 = translucent) and handed to the
// fragment stage as a flat varying -- no per-draw uniform or attribute is
// needed. Vertices are never filtered against the wrong segment (each EBO
// range only reaches its own parts), and only the ONE part matrix this vertex
// actually needs is built.
//
// Transform chain replicates vanilla LivingEntityRenderer:
//   world = feet + Ry(pi - yaw) * S(-1,-1,1) * T(0,-1.501,0) * partChain / 16
// where model local -Z is the facing direction; facing yaw comes from the
// horizontal velocity (slow particles fall back to a seed yaw with slow
// drift). The model is fullbright (vanilla Allay uses block light 15); tint
// comes from the emitter colour keyframes.
uniform mat4 ModelViewMat;
uniform mat4 ProjMat;
uniform vec3 uCamPos;
uniform float uFadeDist;

layout(std430, binding = BIND_POOL_WRITE) readonly buffer ParticleRead { vec4 data[]; } particles; // freshly written pool
layout(std430, binding = BIND_EMITTER) readonly buffer EmitterBuf { vec4 u[]; } emitters;
layout(std430, binding = BIND_SORT_WRITE) readonly buffer SortBuf { uvec2 kv[]; } sortedKv;
layout(std430, binding = BIND_MODELGEO) readonly buffer ModelGeo { float v[]; } geo;

out vec2 vUv;
out vec3 vColor;
out float vDist;
flat out float vSeg; // 0 = opaque cutout segment, 1 = translucent blended (from partId)
flat out vec3 vNormalView; // view-space surface normal for vanilla-style diffuse

const float PI = 3.14159265;
// vanilla spin rhythm (Allay.isSpinning): a 15-tick (0.75 s) burst inside a
// 55-tick (2.75 s) cycle sweeps exactly 4*pi, then stays still until the next
// cycle (4*pi mod 2*pi == identity, so the reset reads as 'still upright')
const float SPIN_CYCLE_S = 2.75;
const float SPIN_WINDOW_FRACTION = 15.0 / 55.0;
// baked face-normal axis table (+X,-X,+Y,-Y,+Z,-Z), matching the axis ids in
// AllayModelGeometry
const vec3 FACE_NORMALS[6] = vec3[6](
    vec3(1.0, 0.0, 0.0), vec3(-1.0, 0.0, 0.0),
    vec3(0.0, 1.0, 0.0), vec3(0.0, -1.0, 0.0),
    vec3(0.0, 0.0, 1.0), vec3(0.0, 0.0, -1.0));

float hash1(float p) {
    p = fract(p * 0.1031);
    p *= p + 33.33;
    p *= p + p;
    return fract(p);
}

mat4 rotX(float a) { return mat4(1,0,0,0, 0,cos(a),sin(a),0, 0,-sin(a),cos(a),0, 0,0,0,1); }
mat4 rotY(float a) { return mat4(cos(a),0,-sin(a),0, 0,1,0,0, sin(a),0,cos(a),0, 0,0,0,1); }
mat4 rotZ(float a) { return mat4(cos(a),sin(a),0,0, -sin(a),cos(a),0,0, 0,0,1,0, 0,0,0,1); }
// ModelPart.translateAndRotate: translate(pivot) then rotationZYX = Rx*Ry*Rz
mat4 part(vec3 pivot, float xr, float yr, float zr) {
    mat4 t = mat4(1.0);
    t[3].xyz = pivot;
    return t * rotX(xr) * rotY(yr) * rotZ(zr);
}

void main() {
    // Type check kept purely as a corruption guard: the partition guarantees
    // every item here is sort-type 0 (MODEL), so this branch never fires on a
    // healthy frame -- unlike the pre-partition scheme, foreign-type items
    // are never even launched into these draws.
    uvec2 item = sortedKv.kv[gl_InstanceID];
    if ((item.y & 3u) != 0u) { // sprite-type item would mean upstream corruption
        gl_Position = vec4(0.0, 0.0, 2.0, 1.0); // beyond far plane -> clipped
        return;
    }
    uint inst = item.y >> 2;
    uint base = inst * 4u;

    // pull the vertex's part id — the segment id and per-part matrix
    // branching both key off it. The two sub-draws' element ranges are
    // disjoint (cmd2 only reaches parts 0-3, cmd3 only parts 4-6), so this
    // also IS the segment selector.
    uint vb = uint(gl_VertexID) * MODEL_VERTEX_FLOATS;
    int pid = int(geo.v[vb + 5u]);
    int normalCode = int(geo.v[vb + 6u]);
    vSeg = pid >= 4 ? 1.0 : 0.0;
    vec4 p0 = particles.data[base + 0u];
    vec4 p1 = particles.data[base + 1u];
    vec4 p2 = particles.data[base + 2u];
    vec4 p3 = particles.data[base + 3u];

    // per-INSTANCE fade early-out (p0 is shared by every vertex of the
    // instance, so whole triangles drop out consistently)
    if (distance(p0.xyz, uCamPos) > uFadeDist + 24.0) {
        gl_Position = vec4(0.0, 0.0, 2.0, 1.0);
        return;
    }

    float life = clamp(p3.x / max(p3.y, 1e-5), 0.0, 1.0);
    uint eid = floatBitsToUint(p3.w);
    uint hb = eid * VEC4_PER_EMITTER;

    float sizeStart = emitters.u[hb + 5u].z;
    float sizeEnd = emitters.u[hb + 5u].w;
    float sizeEase = emitters.u[hb + 6u].x;
    float size = mix(sizeStart, sizeEnd, pow(life, sizeEase)) * p0.w;
    // total body height = 2*size blocks; vanilla body is ~10 units (0.625 b)
    float scale = (2.0 * size) / 0.625;

    // ---- animation inputs (procedural stand-ins for the entity values) ----
    // per-particle phase offset (seconds) so swarms don't animate in lockstep;
    // limbSwingAmount ~ horizontal speed, head pitch ~ vertical speed.
    float animAge = p3.x + hash1(p3.z * 1.7) * 7.0;
    float ticks = animAge * 20.0;
    vec3 vel = p1.xyz;
    float hSpeed = length(vel.xz);
    float lsa = min(hSpeed / 1.5, 1.0);
    float limbSwing = 0.0;
    float yaw = hSpeed > 0.05
        ? atan(-vel.x, vel.z)
        : (hash1(p3.z * 3.7) * 2.0 * PI + p3.x * 0.3);
    float headPitch = clamp(vel.y * 40.0, -45.0, 45.0);

    // ---- vanilla AllayModel.setupAnim, ported ----
    int anim = int(emitters.u[hb + 17u].x);
    float f = ticks * (20.0 * PI / 180.0) + limbSwing;
    float f1 = cos(f) * PI * 0.15 + lsa;
    float f3 = ticks * 9.0 * (PI / 180.0);
    float f4 = min(lsa / 0.3, 1.0);
    float f5 = 1.0 - f4;
    float f6 = (anim == 3) ? smoothstep(0.0, 0.25, p3.x) : 0.0; // HOLD ramp (vanilla /5 ticks)
    bool dance = (anim == 1 || anim == 2);
    bool spin = (anim == 2);

    float rootYBob = cos(f3) * 0.25 * f5;
    float rootYRot = 0.0, rootZRot = 0.0;
    // vanilla leaves head.xRot at its reset value while dancing; only the
    // non-dance branch feeds headPitch into it
    float headYRot = 0.0, headZRot = 0.0, headXRot = 0.0;
    if (dance) {
        float f7 = ticks * 8.0 * (PI / 180.0) + lsa;
        // burst rhythm: linear 0..1 sweep across the 15-tick window, still for
        // the rest of the 55-tick cycle (vanilla resets spinning progress)
        float cyclePhase = fract(p3.x / SPIN_CYCLE_S);
        float f9 = (spin && cyclePhase < SPIN_WINDOW_FRACTION)
                ? cyclePhase / SPIN_WINDOW_FRACTION : 0.0;
        rootYRot = spin ? (PI * 4.0) * f9 : 0.0;
        rootZRot = cos(f7) * 16.0 * (PI / 180.0) * (1.0 - f9);
        headYRot = cos(f7) * 30.0 * (PI / 180.0) * (1.0 - f9);
        headZRot = cos(f7) * 14.0 * (PI / 180.0) * (1.0 - f9);
    } else {
        headXRot = headPitch * (PI / 180.0);
    }
    float wingXR = 0.43633232 * (1.0 - f4);
    float bodyXR = f4 * (PI / 4.0);
    float f12 = f6 * mix(-PI / 3.0, -1.134464, f4); // Mth.lerp(f4, a, b)
    float f13 = f5 * (1.0 - f6);
    float f14 = 0.43633232 - cos(f3 + PI * 1.5) * PI * 0.075 * f13;

    // ---- part chain: build ONLY the matrix this vertex's part needs ----
    // (root: translate(0, 23.5+bob, 0) * Ry * Rz; pivots from createBodyLayer)
    mat4 t = mat4(1.0);
    t[3].xyz = vec3(0.0, 23.5 + rootYBob, 0.0);
    mat4 rootM = t * rotY(rootYRot) * rotZ(rootZRot);
    mat4 M;
    if (pid == 0) {
        M = rootM * part(vec3(0.0, -3.99, 0.0), headXRot, headYRot, headZRot);
    } else {
        mat4 bodyM = rootM * part(vec3(0.0, -4.0, 0.0), bodyXR, 0.0, 0.0);
        if (pid == 1 || pid == 6) {
            M = bodyM; // skin and the translucent cloak share the body transform
        } else if (pid == 2) {
            M = bodyM * part(vec3(-1.75, 0.5, 0.0), f12, 0.27925268 * f6, f14);
        } else if (pid == 3) {
            M = bodyM * part(vec3(1.75, 0.5, 0.0), f12, -0.27925268 * f6, -f14);
        } else if (pid == 4) {
            M = bodyM * part(vec3(-0.5, 0.0, 0.6), wingXR, -PI / 4.0 + f1, 0.0);
        } else {
            M = bodyM * part(vec3(0.5, 0.0, 0.6), wingXR, PI / 4.0 - f1, 0.0);
        }
    }

    // ---- vertex transform to world ----
    vec3 local = vec3(geo.v[vb], geo.v[vb + 1u], geo.v[vb + 2u]);
    vUv = vec2(geo.v[vb + 3u], geo.v[vb + 4u]);
    vec3 pm = (M * vec4(local, 1.0)).xyz / 16.0;   // vanilla per-part /16
    // vanilla chain after the parts: T(0,-1.501,0) then S(-1,-1,1) then
    // Ry(180deg - yaw); combined here with the particle's world scale
    vec3 flipped = vec3(-pm.x, 1.501 - pm.y, pm.z) * scale;
    float ry = PI - yaw;
    vec3 world = p0.xyz + vec3(cos(ry) * flipped.x + sin(ry) * flipped.z,
                               flipped.y,
                               -sin(ry) * flipped.x + cos(ry) * flipped.z);

    // same transform chain applied to the DIRECTION (no translation, no
    // scaling): part rotation -> S(-1,-1,1) -> Ry(pi - yaw) -> camera view
    vec3 nPart = mat3(M) * FACE_NORMALS[normalCode];
    vec3 nFlipped = vec3(-nPart.x, -nPart.y, nPart.z);
    vec3 nWorld = vec3(cos(ry) * nFlipped.x + sin(ry) * nFlipped.z,
                       nFlipped.y,
                       -sin(ry) * nFlipped.x + cos(ry) * nFlipped.z);
    vNormalView = normalize(mat3(ModelViewMat) * nWorld);

    gl_Position = ProjMat * ModelViewMat * vec4(world - uCamPos, 1.0);
    vDist = length(world - uCamPos);

    // colour keyframes as the fullbright tint (default presets stay white)
    int cc = max(1, min(8, int(emitters.u[hb + 6u].z)));
    vec3 col;
    if (cc <= 1) {
        col = emitters.u[hb + 8u].rgb;
    } else {
        float x = life * float(cc - 1);
        int i = min(cc - 2, int(floor(x)));
        vec3 c0 = emitters.u[hb + 8u + uint(i)].rgb;
        vec3 c1 = emitters.u[hb + 8u + uint(min(cc - 1, i + 1))].rgb;
        col = mix(c0, c1, fract(x));
    }
    vColor = col * p2.rgb;
}
