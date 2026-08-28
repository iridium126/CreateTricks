// MODEL particle vertex shader: instanced vanilla-allay rendering. Each
// instance reads the MODEL PARTITION of the type-partitioned sort array --
// sorted keys place all MODEL items (sort-type 0) at [0, N_model), so both
// sub-draws resolve instances as plain sortedKv[gl_InstanceID] and their
// commands' instanceCount is the exact N_model -- pulls its
// particle record from the pool, and delegates the procedural AllayModel pose
// to the shared chunk (allay_pose.glsl) while vertex-pulling the static
// indexed geometry (binding 12, float stride 7: pos.xyz units / uv normalised /
// partId / normal axis) -- gl_VertexID is the element index.
//
// The mesh renders as TWO sub-draws issued by ONE glMultiDrawElementsIndirect.
// BOTH commands cover the same partition and differ ONLY in their
// element-buffer index range: cmd2 references the opaque parts
// (head/skin/arms, cutout + depth write in fsh), cmd3 the translucent parts
// (cloak + wings, alpha blend WITH depth writes so ghost surfaces occlude
// like tinted glass), depth-sorted together with the ALPHA sprites. Because
// the index ranges are disjoint, the segment id is derived purely from the
// vertex's own partId (>= 4 = translucent) and handed to the fragment stage
// as a flat varying -- no per-draw uniform or attribute is needed.
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
uniform float uTimeSec; // engine-accumulated simulation time (storm wander/bursts)

layout(std430, binding = BIND_POOL_WRITE) readonly buffer ParticleRead { vec4 data[]; } particles; // freshly written pool
layout(std430, binding = BIND_EMITTER) readonly buffer EmitterBuf { vec4 u[]; } emitters;
layout(std430, binding = BIND_SORT_WRITE) readonly buffer SortBuf { uvec2 kv[]; } sortedKv;
layout(std430, binding = BIND_MODELGEO) readonly buffer ModelGeo { float v[]; } geo;

#pragma cmi_include chunks/allay_pose.glsl

out vec2 vUv;
out vec3 vColor;
out float vDist;
flat out float vSeg; // 0 = opaque cutout segment, 1 = translucent blended (from partId)
flat out vec3 vNormalView; // view-space surface normal for vanilla-style diffuse
flat out float vOverlay; // 1.0 = vanilla hurt overlay active (hurt timer or corpse)

void main() {
    // Type check kept purely as a corruption guard: the partition guarantees
    // every item here is sort-type 0 (MODEL), so this branch never fires on a
    // healthy frame.
    uvec2 item = sortedKv.kv[gl_InstanceID];
    if ((item.y & 3u) != 0u) { // sprite-type item would mean upstream corruption
        gl_Position = vec4(0.0, 0.0, 2.0, 1.0); // beyond far plane -> clipped
        return;
    }
    uint inst = item.y >> 2;
    uint base = inst * 4u;

    // pull the vertex's part id -- the segment id and per-part matrix
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
    // pow(life, 0) at life=0 is undefined GLSL -- keep the exponent positive
    float sizeEase = max(emitters.u[hb + 6u].x, 0.001);
    // STORM members carry their identity (memberIdx+1) in p0.w, not a size
    // multiplier -- the header's storm slot 18.x selects the constant 1.0.
    float pmul = emitters.u[hb + 18u].x > 0.5 ? 1.0 : p0.w;
    float size = mix(sizeStart, sizeEnd, pow(life, sizeEase)) * pmul;
    // above-feet body height = 2*size blocks exactly: MODEL_ABOVE_FEET is the
    // rest-pose model's above-feet height in blocks, derived from the bake
    // (AllayModelGeometry) -- the old hardcoded 0.625 was ~5.5% too tall.
    float scale = (2.0 * size) / MODEL_ABOVE_FEET;

    int anim = int(emitters.u[hb + 17u].x);
    // Storm members: slow individuals near the wandering centre periodically
    // run full vanilla dance cycles instead of plain flight.
    vec4 stormA = emitters.u[hb + 18u];
    if (stormA.x > 0.5) {
        vec3 anchor = emitters.u[hb + 19u].xyz;
        vec3 G = cmiStormCenter(anchor, stormA.z, p3.z, uTimeSec);
        anim = cmiStormAnimOverride(int(stormA.x), anim, length(p1.xyz),
                distance(p0.xyz, G), stormA.y, p3.z, uTimeSec);
    }
    // HP-death corpse: the animation id rides the per-EMITTER header, so a
    // single death needs this per-particle signal -- update.comp flips the HP
    // slot negative and counts it down over the vanilla 20-tick death window.
    // The roll timer is time-since-death while age keeps driving the idle
    // sway: the animation stays continuous, exactly like vanilla corpses.
    // hp <= 0 (not < 0): the kill frame itself already counts as dead, matching
    // update.comp's corpse predicate and vanilla's deathTime > 0 overlay.
    bool corpse = p3.y <= 0.0;
    if (corpse)
        anim = 3;
    float sinceDeath = corpse ? -p3.y : 0.0;
    float yaw;
    mat4 M = cmiAllayPartTransform(p3.x, p3.z, p1.xyz, anim, pid, yaw);

    // ---- vertex transform to world ----
    vec3 local = vec3(geo.v[vb], geo.v[vb + 1u], geo.v[vb + 2u]);
    vUv = vec2(geo.v[vb + 3u], geo.v[vb + 4u]);
    vec3 pm = (M * vec4(local, 1.0)).xyz / 16.0;   // vanilla per-part /16
    // vanilla chain after the parts: T(0,-1.501,0) then S(-1,-1,1), then the
    // death roll (setupRotations' Rz, INNER to the facing yaw -- PoseStack
    // mulPose post-multiplies, so the roll consumes the already-flipped,
    // feet-relative vector and the corpse tips onto its OWN side), then
    // Ry(180deg - yaw); combined here with the particle's world scale
    vec3 flipped = vec3(-pm.x, 1.501 - pm.y, pm.z);
    if (anim == 3)
        flipped = cmiDeathRoll(flipped, sinceDeath);
    flipped *= scale;
    float ry = CMI_PI - yaw;
    vec3 world = p0.xyz + vec3(cos(ry) * flipped.x + sin(ry) * flipped.z,
                               flipped.y,
                               -sin(ry) * flipped.x + cos(ry) * flipped.z);

    // same transform chain applied to the DIRECTION (no translation, no
    // scaling): part rotation -> S(-1,-1,1) -> [death roll] -> Ry(pi - yaw)
    vec3 nPart = mat3(M) * CMI_FACE_NORMALS[normalCode];
    vec3 nFlipped = vec3(-nPart.x, -nPart.y, nPart.z);
    if (anim == 3)
        nFlipped = cmiDeathRoll(nFlipped, sinceDeath);
    vec3 nWorld = vec3(cos(ry) * nFlipped.x + sin(ry) * nFlipped.z,
                       nFlipped.y,
                       -sin(ry) * nFlipped.x + cos(ry) * nFlipped.z);
    vNormalView = normalize(mat3(ModelViewMat) * nWorld);

    gl_Position = ProjMat * ModelViewMat * vec4(world - uCamPos, 1.0);
    vDist = length(world - uCamPos);

    // colour keyframes as the fullbright tint (default presets stay white)
    vec4 kfr[8];
    for (int i = 0; i < 8; i++) kfr[i] = emitters.u[hb + 8u + uint(i)];
    int cc = int(emitters.u[hb + 6u].z);
    vColor = cmiKeyframeColor(kfr, cc, life).rgb * p2.rgb;
    // vanilla hurt overlay flag (model.fsh applies the exact post-diffuse mix):
    // active while the hurt timer runs (p2.w, set to 1 per hit, decays over
    // 0.5 s = vanilla hurtTime 10 ticks) or the corpse countdown runs (hp <= 0
    // = deathTime > 0). The overlay is CONSTANT strength for the whole window
    // and cuts off -- no fade; the keyframe tint above carries no red any more.
    vOverlay = (p2.w > 0.0 || corpse) ? 1.0 : 0.0;
}
