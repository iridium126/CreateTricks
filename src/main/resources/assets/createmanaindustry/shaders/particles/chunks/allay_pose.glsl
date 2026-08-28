// ==== CMI shared allay pose math (MODEL particles) ====
// Single source of truth for the vanilla AllayModel.setupAnim port, consumed by:
//   - model.vsh          (self-drawn L0 path)
//   - ParticleVertexInjector (shader-pack merged programs)
// Injected verbatim by ParticlePrograms' #pragma cmi_include mechanism.
// Everything is prefixed cmi so injected pack programs cannot collide.
// Port reference: .refs/neoforge-21.1.227/net/minecraft/client/model/AllayModel.java

// NOTE: plain const declarations, NOT #define -- the shader-pack merger
// splices this chunk through glsl-transformer ASTs, where preprocessor
// directives do not survive (they are lexer-level, not AST nodes).
const float CMI_PI = 3.14159265;
// vanilla spin rhythm (Allay.isSpinning): dancingTicks % 55 < 15 sweeps 4*pi.
// Outside the window vanilla's clamped spinningAnimationTicks accumulator keeps
// DECAYING for one further window length, and the wobble multipliers ride that
// decayed progress -- so the wobble fades back IN over the tail. The root yaw
// itself is zeroed there (resetPose), only the wobble sees the decay.
const float CMI_SPIN_CYCLE_S = 2.75;
const float CMI_SPIN_WINDOW_FRACTION = 15.0 / 55.0;

// baked face-normal axis table (+X,-X,+Y,-Y,+Z,-Z), matching AllayModelGeometry axis ids
const vec3 CMI_FACE_NORMALS[6] = vec3[6](
    vec3(1.0, 0.0, 0.0), vec3(-1.0, 0.0, 0.0),
    vec3(0.0, 1.0, 0.0), vec3(0.0, -1.0, 0.0),
    vec3(0.0, 0.0, 1.0), vec3(0.0, 0.0, -1.0));

float cmiHash1(float p) {
    p = fract(p * 0.1031);
    p *= p + 33.33;
    p *= p + p;
    return fract(p);
}

mat4 cmiRotX(float a) { return mat4(1,0,0,0, 0,cos(a),sin(a),0, 0,-sin(a),cos(a),0, 0,0,0,1); }
mat4 cmiRotY(float a) { return mat4(cos(a),0,-sin(a),0, 0,1,0,0, sin(a),0,cos(a),0, 0,0,0,1); }
mat4 cmiRotZ(float a) { return mat4(cos(a),sin(a),0,0, -sin(a),cos(a),0,0, 0,0,1,0, 0,0,0,1); }

// ModelPart.translateAndRotate: translate(pivot) then rotationZYX = Rx*Ry*Rz
mat4 cmiPart(vec3 pivot, float xr, float yr, float zr) {
    mat4 t = mat4(1.0);
    t[3].xyz = pivot;
    return t * cmiRotX(xr) * cmiRotY(yr) * cmiRotZ(zr);
}

// ---- storm swarm shared helpers ------------------------------------------
// Smooth wandering centre for Allay Storm swarms, shared by update.comp (the
// boids attractor) and both MODEL vertex paths (dance-burst proximity).
// Deterministic in (anchor, seed, time): every consumer in a frame agrees
// without any GPU sync.
vec3 cmiStormCenter(vec3 anchor, float wanderR, float seed, float timeSec) {
    float t = timeSec * 0.05;
    float h1 = cmiHash1(seed * 1.31 + 0.7) * 6.2831853;
    float h2 = cmiHash1(seed * 2.17 + 4.7) * 6.2831853;
    float h3 = cmiHash1(seed * 3.71 + 9.1) * 6.2831853;
    return anchor + wanderR * 0.5 * vec3(
        sin(t * 0.70 + h1) + 0.5 * sin(t * 1.70 + h2),
        0.35 * sin(t * 0.90 + h3),
        cos(t * 0.60 + h2) + 0.5 * cos(t * 1.30 + h1));
}

// Procedural dance-burst arbitration for storm members, per motion mode.
//   ball (mode 1): slow individuals near the wandering centre
//   vortex (mode 2): inner-band members regardless of speed -- tangential
//     velocity is constant on the orbit, so a speed gate would never fire;
//     dancers keep orbiting (the pose change does not touch the motion)
// Everyone else keeps flying. Pure function of its arguments -- no state.
int cmiStormAnimOverride(int mode, int baseAnim, float speed, float distCenter,
        float ballRadius, float seed, float timeSec) {
    if (baseAnim != 0)
        return baseAnim;
    bool stage = (mode == 2)
        ? distCenter < ballRadius * 0.55
        : (speed <= 1.5 && distCenter <= ballRadius * 0.6);
    if (!stage)
        return 0;
    // ~11 s dancing out of each 36 s cycle, phase-staggered per particle
    float cyc = fract(timeSec / 36.0 + cmiHash1(seed * 7.3));
    return cyc < 0.3 ? 1 : 0;
}

// Emitter colour keyframe interpolation over normalized life (headers hb+8..hb+15).
// frames[] must be filled by the caller from its own emitter-header source.
vec4 cmiKeyframeColor(vec4 frames[8], int cc, float life) {
    cc = max(1, min(8, cc));
    if (cc <= 1) return frames[0];
    float x = life * float(cc - 1);
    int i = min(cc - 2, int(floor(x)));
    float f = fract(x);
    vec4 c0 = frames[i];
    vec4 c1 = frames[min(cc - 1, i + 1)];
    return mix(c0, c1, f);
}

// ---- vanilla death roll (DEATH anim) -------------------------------------
// LivingEntityRenderer.setupRotations pushes Ry(180 - yaw) THEN Rz(theta), and
// PoseStack.mulPose POST-multiplies -- the composed chain is T * Ry * Rz * S,
// so the roll is INNER to the facing yaw and consumes the ALREADY-FLIPPED
// (S(-1,-1,1)), feet-relative vector. The rotation axis is the yawed model Z
// (the body's front-back axis): the corpse tips onto its OWN side, direction
// following the facing. Callers MUST apply cmiDeathRoll to the flipped pre-yaw
// offset (and the same-frame normal) -- rolling a world-space offset instead
// (the old implementation) tips every corpse toward world -X regardless of
// facing, a PoseStack multiply-order misread.
//   theta = sqrt(min(((deathTime + partialTick - 1) / 20) * 1.6, 1)) * 90 deg

/** Death-roll angle in radians for {@code sinceDeathSec} seconds since death. */
float cmiDeathRollAngle(float sinceDeathSec) {
    float u = clamp((sinceDeathSec * 20.0 - 1.0) / 20.0, 0.0, 1.0);
    return sqrt(min(u * 1.6, 1.0)) * (CMI_PI / 2.0);
}

/**
 * Rolls a vector about the Z axis by the death angle (standard CCW rotation,
 * matching vanilla Axis.ZP.rotationDegrees in the right-handed post-flip
 * frame). Pass the FLIPPED PRE-YAW offset from the feet origin (and the
 * normal in the same frame) -- not a world-space offset.
 */
vec3 cmiDeathRoll(vec3 v, float sinceDeathSec) {
    float a = cmiDeathRollAngle(sinceDeathSec);
    float c = cos(a);
    float s = sin(a);
    return vec3(c * v.x - s * v.y, s * v.x + c * v.y, v.z);
}

// Full vanilla AllayModel.setupAnim port. Returns the MODEL-SPACE part matrix
// (units of 1/16 block, before the /16 · S(-1,-1,1) · Ry(pi-yaw) chain).
//   ageSec  particle age in seconds        seed  per-particle random seed
//   vel     particle velocity (blocks/s)   anim  0 FLY 1 DANCE 2 HOLD 3 DEATH
//   pid     part id (see AllayModelGeometry)
//   yaw     out: body facing yaw (radians)
mat4 cmiAllayPartTransform(float ageSec, float seed, vec3 vel, int anim, int pid, out float yaw) {
    // ---- animation inputs (procedural stand-ins for the entity values) ----
    float animAge = ageSec + cmiHash1(seed * 1.7) * 7.0;
    float ticks = animAge * 20.0;
    float hSpeed = length(vel.xz);
    float lsa = min(hSpeed / 1.5, 1.0);
    float limbSwing = 0.0;
    yaw = hSpeed > 0.05
        ? atan(-vel.x, vel.z)
        : (cmiHash1(seed * 3.7) * 2.0 * CMI_PI + ageSec * 0.3);
    float headPitch = clamp(vel.y * 40.0, -45.0, 45.0);

    float f = ticks * (20.0 * CMI_PI / 180.0) + limbSwing;
    float f1 = cos(f) * CMI_PI * 0.15 + lsa;
    float f3 = ticks * 9.0 * (CMI_PI / 180.0);
    float f4 = min(lsa / 0.3, 1.0);
    float f5 = 1.0 - f4;
    // HOLD ramp -- vanilla getHoldingItemAnimationProgress lerps its tick
    // counter LINEARLY over five ticks (lerp(...)/5); mirror that linear shape
    // (the symmetric release needs a hold-end event particles never observe)
    float f6 = (anim == 2) ? clamp(ageSec / 0.25, 0.0, 1.0) : 0.0;
    bool dance = (anim == 1); // full vanilla jukebox dance incl. the spin rhythm

    float rootYBob = cos(f3) * 0.25 * f5;
    float rootYRot = 0.0, rootZRot = 0.0;
    // vanilla leaves head.xRot at its reset value while dancing; only the
    // non-dance branch feeds headPitch into it
    float headYRot = 0.0, headZRot = 0.0, headXRot = 0.0;
    if (dance) {
        float f7 = ticks * 8.0 * (CMI_PI / 180.0) + lsa;
        // burst rhythm: f9 follows vanilla's CLAMPED ACCUMULATOR
        // (spinningAnimationTicks): it rises across the 15-tick window, then
        // decays for one further window length before resting at zero. The
        // root YAW is driven only inside the window (resetPose zeroes it in
        // the tail); the wobble below stays suppressed through the whole tail.
        float cyclePhase = fract(ageSec / CMI_SPIN_CYCLE_S);
        float w = CMI_SPIN_WINDOW_FRACTION;
        float f9;
        if (cyclePhase < w) {
            f9 = cyclePhase / w;
        } else if (cyclePhase < 2.0 * w) {
            f9 = 1.0 - (cyclePhase - w) / w;
        } else {
            f9 = 0.0;
        }
        rootYRot = (cyclePhase < w) ? (CMI_PI * 4.0) * f9 : 0.0;
        rootZRot = cos(f7) * 16.0 * (CMI_PI / 180.0) * (1.0 - f9);
        headYRot = cos(f7) * 30.0 * (CMI_PI / 180.0) * (1.0 - f9);
        headZRot = cos(f7) * 14.0 * (CMI_PI / 180.0) * (1.0 - f9);
    } else {
        headXRot = headPitch * (CMI_PI / 180.0);
    }
    float wingXR = 0.43633232 * (1.0 - f4);
    float bodyXR = f4 * (CMI_PI / 4.0);
    float f12 = f6 * mix(-CMI_PI / 3.0, -1.134464, f4); // Mth.lerp(f4, a, b)
    float f13 = f5 * (1.0 - f6);
    float f14 = 0.43633232 - cos(f3 + CMI_PI * 1.5) * CMI_PI * 0.075 * f13;

    // ---- part chain: build ONLY the matrix this vertex's part needs ----
    // (root: translate(0, 23.5+bob, 0) * Ry * Rz; pivots from createBodyLayer)
    mat4 t = mat4(1.0);
    t[3].xyz = vec3(0.0, 23.5 + rootYBob, 0.0);
    mat4 rootM = t * cmiRotY(rootYRot) * cmiRotZ(rootZRot);
    if (pid == 0) {
        return rootM * cmiPart(vec3(0.0, -3.99, 0.0), headXRot, headYRot, headZRot);
    }
    mat4 bodyM = rootM * cmiPart(vec3(0.0, -4.0, 0.0), bodyXR, 0.0, 0.0);
    if (pid == 1 || pid == 6) {
        return bodyM; // skin and the translucent cloak share the body transform
    } else if (pid == 2) {
        return bodyM * cmiPart(vec3(-1.75, 0.5, 0.0), f12, 0.27925268 * f6, f14);
    } else if (pid == 3) {
        return bodyM * cmiPart(vec3(1.75, 0.5, 0.0), f12, -0.27925268 * f6, -f14);
    } else if (pid == 4) {
        return bodyM * cmiPart(vec3(-0.5, 0.0, 0.6), wingXR, -CMI_PI / 4.0 + f1, 0.0);
    }
    return bodyM * cmiPart(vec3(0.5, 0.0, 0.6), wingXR, CMI_PI / 4.0 - f1, 0.0);
}
