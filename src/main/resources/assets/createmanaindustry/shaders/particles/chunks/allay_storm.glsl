// ==== CMI shared allay-storm math (pose-free) ====
// Storm-shared layer split out of chunks/allay_pose.glsl so compute passes
// that need ONLY the wave/typhoon math (keygen's carrier sweep, update's
// steering, emit's analytic spawn, wavecontact's contact test) can include
// this chunk without pulling in the AllayModel pose port. Consumed by:
//   - keygen.comp        (carrier sweep: cmiStormWaveClaim)
//   - update.comp        (servo target + wave engagement)
//   - emit.comp          (analytic spawn)
//   - wavecontact.comp   (membership test)
//   - chunks/allay_pose.glsl (top-includes this chunk for cmiHash1)
// Injected verbatim by ParticlePrograms' #pragma cmi_include mechanism
// (recursively, depth-bound 4) — chunks/allay_pose.glsl is the ONLY nested
// include; every other consumer includes exactly one chunk.
//
// The wave uniforms + clock are declared HERE (next to the only functions
// that read them), which dedupes the three per-file declarations these
// replaced; cmiStormWaveClaim is THE carrier/sword predicate — keygen.comp
// (carrier partition) and model.vsh / the shader-pack merged source (tier
// pick) call the SAME function, so a member enters the carrier partition
// exactly when its sword renders. The go-stagger (cmiStormWaveGo) gates
// STEERING only, never the sword — matching the predicate this chunk
// replaces in model.vsh.
//
// Hash discipline (learned the hard way, see the identity chain in
// emit.comp): every hash input is built from NAMED single-mul statements —
// never an inline a*b+c — so driver FMA contraction cannot fork per-member
// constants between GPU vendors. The server's Java re-derivation
// (AllayStormWaves) validates contact reports against the SAME chains.

// Shared simulation uniforms. Consumers that already declared these must NOT
// re-declare them (duplicate declarations are a compile error under the
// text-splice include). Unused in a consumer is fine — the compiler strips
// them and the engine simply never sets those locations.
uniform float uTimeSec;      // shared simulation clock, seconds ((gameTime mod 2^21)/20)
uniform vec4  uWave[4];      // x waveSeed, y fraction, z assembleSec, w diveUntilSec
uniform vec4  uWaveTarget[4]; // xyz live target aim point (feet + 0.5), w waveId (0 = inactive slot)

// NAMED single-mul statements only — see the discipline note above.
float cmiHash1(float p) {
    p = fract(p * 0.1031);
    p *= p + 33.33;
    p *= p + p;
    return fract(p);
}

// ---- storm wave squads (server-launched dive waves) ------------------------
// Membership + launch-stagger rolls. SAME hash discipline as emit.comp's
// identity chain: NAMED single-mul statements only — an inline a*b+c may
// contract into an FMA and fork the selection between GPU vendors and the
// server's Java re-derivation (AllayStormWaves, which validates contact
// reports against the SAME chains). waveSeed is a 24-bit integer-valued
// float (exact); mseed is the member's identity seed in [0,1) from p3.z.
float cmiStormWaveRoll(float mseed, float waveSeed) {
    float s0 = cmiHash1(waveSeed + 13.37);
    float shift = s0 * 89.0;
    float base = mseed * 47.29;
    float q = base + shift;
    return cmiHash1(q);
}

bool cmiStormWaveMember(float mseed, float waveSeed, float fraction) {
    return cmiStormWaveRoll(mseed, waveSeed) < fraction;
}

// Personal launch-stagger roll in [0,1): staggers the squad's pursuit starts
// across the window (a swarm trickling in, not one lump). GPU-only — the
// server never phases individual members, it only brackets the window.
float cmiStormWaveGo(float mseed, float waveSeed) {
    float s0 = cmiHash1(waveSeed + 57.11);
    float shift = s0 * 57.0;
    float base = mseed * 13.77;
    float q = base + shift;
    return cmiHash1(q);
}

// THE carrier/sword predicate: the first live wave slot whose hash claims
// this member, or -1. Consumed by keygen.comp (which instance enters the
// held-item carrier partition) and by model.vsh / the shader-pack merged
// source (which tier renders) — one function, so the two decisions cannot
// diverge. Corpse and storm-gating stay at the call sites (keygen tests
// p3.y and the header storm slot itself; the render paths gate identically).
int cmiStormWaveClaim(float mseed) {
    for (int k = 0; k < 4; k++) {
        if (uWaveTarget[k].w < 0.5)
            continue; // slot inactive (waveId 0)
        if (uTimeSec < uWave[k].z || uTimeSec > uWave[k].w)
            continue; // outside the wave window
        if (!cmiStormWaveMember(mseed, uWave[k].x, uWave[k].y))
            continue;
        return k;
    }
    return -1;
}

// ---- dive-wave carried item (held sword) ------------------------------------
// The carried-item ramp of a wave-squad member under predicate (a): a hash
// member carries the sword for the WHOLE wave window, ramping the vanilla
// 5-tick arm raise in from the wave's assemble moment and back out so it
// reaches zero exactly at the deadline (C0 at both edges — a member never
// visibly snaps; aborts drop the uniforms, which is the rare hard cut).
// Pure function of the wave schedule — no state, identical on every client.
float cmiStormCarryRamp(float timeSec, float assembleSec, float diveUntilSec) {
    float up = clamp((timeSec - assembleSec) / 0.25, 0.0, 1.0);
    float down = clamp((diveUntilSec - timeSec) / 0.25, 0.0, 1.0);
    return min(up, down);
}

// ---- typhoon home-point ----------------------------------------------------
// Shared servo-target / analytic-spawn function for the typhoon shape rework.
// Consumed by update.comp (the servo target + its velocity) and emit.comp (the
// analytic spawn, whose state IS the servo's equilibrium — late joiners start
// settled). Pure function of (memberSeed, radius, omegaNow, rotPhase,
// convPhase, convRate, maxSpeed, center): synced params + the CPU-side
// growth-law phase integrals only, so every client derives the same targets by
// construction; local separation / repulsion / collision drift is healed by
// the soft correction layer. The center is the SERVER-CHASED anchor — there is
// no per-member wander on this path.
//
// PHASE DISCIPLINE (the clump-bug contract): the rotation and conveyor phases
// arrive as CPU-integrated scalars (rotPhase / convPhase / convRate — see
// AllayStormRuntime). The shader must NEVER multiply an accumulated clock
// (timeSec) by a radius-dependent rate: with a growing radius that product
// re-derives the whole pattern phase every frame, amplified by the world
// clock into target teleports and a full-storm collapse onto the center.
// The geometry below scales PURELY proportionally with the radius (no
// absolute floors) — that is what keeps omegaNow * reach == 0.85 * cap at
// every storm size and the phase integrals branch-free.
//
// Shape contract (design review):
//   - 6 trailing log-spiral arms, total winding pi across [rEye, reach],
//     chirality = -sign(omegaNow): the outer ends lag the spin, like real
//     rainbands. Each member's own reach tops out past R (uniform per-member
//     fringe of 0.15R) — density decays ~linearly beyond R and the
//     edge-turnaround dwell pile spreads into a soft rim.
//   - Members ride a closed SECONDARY-CIRCULATION loop per arm: eyewall ascent
//     -> outflow deck -> outer-edge sink -> low-level inflow -> repeat. r(s)
//     is a raised cosine (zero radial speed at BOTH turnarounds) and the aloft
//     fraction w(s) = 0.5 + 0.5*sin (peaks mid-ascent). The eyewall density
//     pile-up is the dwell time at the r turnaround — no explicit radial bias
//     needed (the outer edge gains a softer rim the same way).
//   - VERTICAL BAND FILL: the circulation line is only the column's CENTER —
//     every member adds a static uniform offset (v_i - 0.5) * H with
//     H = clamp(0.4R, 3, 8), so the ensemble fills the storm's volume instead
//     of riding the circulation surface (the old shell was hollow inside).
//   - Funnel: the outflow deck stands clamp(0.2R, 2, 6) above the flat deck at
//     the eyewall, tapering linearly to the outer edge.
//   - Static per-member micro jitter (<= 0.4 blocks) keeps the swarm organic.
void cmiTyphoonHome(float ms, float radiusR, float omegaNow, float rotPhase, float convPhase,
        float convRate, float maxSpeed, vec3 center, out vec3 pos, out vec3 vel) {
    // geometry scales PURELY proportionally with the radius (no absolute
    // floors — see the phase-discipline note above)
    float rEye = radiusR * 0.10;

    // arm index (0..5) + gaussian angular jitter, sigma = 0.2 * arm spacing
    // (2pi/6 = 1.04719755; sigma = 0.2094 rad — arm gaps drop to ~4% of the
    // arm-core density)
    float hArm = ms * 5.7;
    float armAngle = floor(cmiHash1(hArm) * 6.0) * 1.04719755;
    float hG = ms * 9.1;
    float gu1 = max(cmiHash1(hG), 1e-6);
    float gu2 = cmiHash1(hG + 17.17);
    float jitter = sqrt(-2.0 * log(gu1)) * cos(6.2831853 * gu2) * 0.2094;

    // per-member conveyor rate (mean radial speed 0.15..0.35 b/s) + fringe
    float hRt = ms * 13.9;
    float rate = 0.15 + cmiHash1(hRt) * 0.2;
    float hFr = ms * 23.1;
    float fringeH = cmiHash1(hFr);
    // span = reach - rEye = R * (0.9 + 0.15*fringeH) — the loop's radial extent
    float aI = 0.9 + 0.15 * fringeH;
    float span = radiusR * aI;
    float reach = radiusR * (1.0 + 0.15 * fringeH);
    // conveyor phase: the CPU integrates rate/(2*span(R(t))) over the frozen
    // growth law into the SHARED scalar convPhase; this member scales it by
    // rate/aI. d(convPhase_i)/dt = rate/(2*span) — the designed radial speed
    // at EVERY storm size, with no accumulated-clock term anywhere.
    float convP = convPhase * (rate / aI);
    float hPh = ms * 11.3;
    float sc = 6.2831853 * fract(cmiHash1(hPh) + convP);

    // the (r, y) circulation loop: raised-cosine radius, sinusoidal aloft
    float cyc = (1.0 - cos(sc)) * 0.5;      // 0 at the eyewall .. 1 at the edge
    float r = rEye + span * cyc;
    float aloft = 0.5 + 0.5 * sin(sc);
    // funnel height at r — (r - rEye) / (reach - rEye) IS cyc, no division needed
    float funnel = clamp(radiusR * 0.2, 2.0, 6.0) * (1.0 - cyc);
    // vertical band fill: the circulation line is the column's CENTER, every
    // member adds a static uniform offset across the band half-width H — the
    // ensemble fills the volume (the old circulation-only shell was hollow)
    float hV = ms * 29.3;
    float bandH = clamp(radiusR * 0.4, 3.0, 8.0);
    float y = -1.2 + (funnel + 1.2) * aloft + (cmiHash1(hV) - 0.5) * bandH;

    // trailing spiral arm + rigid co-rotation (the pattern phase is the
    // CPU-side growth-law integral; the arm shape is scale-invariant, so
    // `spiral` is a pure member constant)
    float spiral = 3.14159265 / log(reach / rEye);
    float theta = armAngle + jitter + rotPhase
            - sign(omegaNow) * spiral * log(r / rEye);
    float ct = cos(theta);
    float st = sin(theta);

    // static organic micro jitter (<= 0.4 blocks, unit direction from hashes)
    float hJ = ms * 17.3;
    float ja = cmiHash1(hJ) * 6.2831853;
    float hJc = hJ + 3.1;
    float jc = cmiHash1(hJc) * 2.0 - 1.0;
    float jr = sqrt(max(0.0, 1.0 - jc * jc));
    float hJm = ms * 19.3;
    vec3 joff = vec3(cos(ja) * jr, jc, sin(ja) * jr) * (0.4 * cmiHash1(hJm));

    pos = center + vec3(ct * r, y, st * r) + joff;

    // target velocity: orbital + circulation slide (radial + vertical) + the
    // arm-parallel slide implied by theta(r) — capped at the member speed cap.
    // convRate = d(convPhase)/dt carries the current conveyor rate; the aI
    // factors cancel against span, leaving drdt = R*pi*sin*rate*convRate.
    float drdt = radiusR * 3.14159265 * sin(sc) * rate * convRate;
    float dydt = (funnel + 1.2) * 3.14159265 * cos(sc) * (rate / aI) * convRate;
    float tang = omegaNow * r - sign(omegaNow) * spiral * drdt;
    vel = vec3(-st * tang + ct * drdt, dydt, ct * tang + st * drdt);
    float spd = length(vel);
    if (spd > maxSpeed)
        vel = vel * (maxSpeed / spd);
}
