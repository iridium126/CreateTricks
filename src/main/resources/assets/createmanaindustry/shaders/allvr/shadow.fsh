// ALLVR exact shadow depth (doc 4i 排查⑪). The vertex stage rasterizes each
// greedy quad DILATED in the pack's distorted clip space so the coverage is a
// guaranteed superset of the quad's true (curved) image; this fragment stage
// restores exactness per texel:
//   texel → distorted clip → inverse distortion (undistorted clip)
//         → ortho ray through the shadow view → ray-plane hit on the quad
//         → exact (u,v) bounds test (discard the dilation fringe)
//         → exact distorted depth → gl_FragDepth.
// Every texel the pack samples then holds the analytically correct depth of
// the nearest surface along its light ray — better than the per-block
// approximation vanilla terrain casts with, at unchanged quad/vertex counts.
// Ortho shadow projections only (the perspective legacy path keeps the old
// terrain.fsh program — see AllvrShaderCache).
uniform mat4 ProjMat;
uniform float uShadowHalfPlane; // shadow ortho half-plane in blocks (1/|m00|)
uniform vec2 uShadowMapSize;    // shadow map resolution
uniform int uShadowDistortionMode;
uniform float uShadowDistortion;
uniform float uShadowDepthScale;
uniform vec4 uShadowLogParams; // mode 3: k, a, b, depth scale

flat in vec3 vOriginView; // quad (u,v)=(0,0) corner on the plane, view space
flat in vec3 vUAxisView;
flat in vec3 vVAxisView;
flat in vec2 vQuadSize;   // (sizeU, sizeV)

float allvrQuarticLength(vec2 v) {
    vec2 p = v * v; p = p * p;
    return sqrt(sqrt(p.x + p.y));
}

// Inverse of the vertex stage's xy distortion (distorted clip → undistorted
// clip). Every convention preserves the radial direction, so only the length
// needs inverting — in the metric the forward divided by.
vec2 allvrUndistortShadowXY(vec2 d) {
    if (uShadowDistortionMode == 0) {
        return d;
    }
    if (uShadowDistortionMode == 1) {
        // quartic metric (Photon): the forward divides by the QUARTIC length,
        // which is homogeneous — with Lq = Lq(d) the scale factor solves
        // factor = (1-b)/(1 - b·Lq) and c = d·factor. Solving with the
        // EUCLIDEAN length instead misplaced every off-axis texel's ray by up
        // to the quartic/euclidean ratio (2^{1/4} ≈ 1.19 on the diagonals —
        // tens of blocks at the shadow rim): the residual-artifact source.
        // Lq(d) < 1/b over the forward image, so the denominator is positive
        // for any texel the pack can produce; the clamp only catches the
        // beyond-infinity corners, which then fail the |c| <= 1 test.
        float lq = allvrQuarticLength(d);
        float factor = (1.0 - uShadowDistortion) / max(1.0 - uShadowDistortion * lq, 1e-4);
        return d * factor;
    }
    if (uShadowDistortionMode == 3) {
        // forward: d = c / (k·log(b·|c| + a)) → |c| = ld·k·log(b·|c| + a);
        // fixed-point iteration (contractive for the Bliss parameter range)
        float ld = length(d);
        if (ld < 1e-6) {
            return vec2(0.0);
        }
        float l = max(ld * uShadowLogParams.x * log(uShadowLogParams.y), 1e-4);
        for (int i = 0; i < 6; i++) {
            l = ld * uShadowLogParams.x * log(l * uShadowLogParams.z + uShadowLogParams.y);
        }
        return (d / ld) * max(l, 0.0);
    }
    if (uShadowDistortionMode == 4) {
        // forward: d = dir·curve·0.5 + 0.5, curve = log(b·len + 1)/log(b + 1)
        vec2 v = 2.0 * d - 1.0;
        float curve = length(v);
        if (curve < 1e-6) {
            return vec2(0.0);
        }
        float b = max(uShadowDistortion, 1e-4);
        float len = (exp(curve * log(b + 1.0)) - 1.0) / b;
        return (v / curve) * len;
    }
    // modes 2 (euclidean) and 5 (iterationT): d = k·c/(bias·|c| + 1-bias),
    // k = 0.95 for mode 5 — radial closed form on the EUCLIDEAN length
    float k = uShadowDistortionMode == 5 ? 0.95 : 1.0;
    float ld = length(d) / k;
    if (ld < 1e-6) {
        return vec2(0.0);
    }
    float l = ld * (1.0 - uShadowDistortion) / max(1.0 - ld * uShadowDistortion, 1e-4);
    return (d / length(d)) * l;
}

void main() {
    vec2 d = gl_FragCoord.xy / uShadowMapSize * 2.0 - 1.0;
    if (uShadowDistortionMode == 4 && (d.x < 0.5 || d.y < 0.5)) {
        // Sundial renders the opaque shadow tile in clip [0.5,1]² — dilated
        // fringe must not spill into the neighbouring atlas tiles
        discard;
    }
    vec2 c = allvrUndistortShadowXY(d);
    if (abs(c.x) > 1.0 || abs(c.y) > 1.0) {
        discard; // dilation fringe beyond the shadow box
    }
    vec2 viewXY = c * uShadowHalfPlane;

    // ortho ray along view z; intersect the quad's plane through vOriginView
    // (the plane normal is cross(uAxis, vAxis) — orientation is irrelevant for
    // the plane equation, only n.z ≈ 0 (edge-on to the light) has no hit)
    vec3 n = cross(vUAxisView, vVAxisView);
    if (abs(n.z) < 1e-6) {
        discard;
    }
    float z = (dot(n, vOriginView) - n.x * viewXY.x - n.y * viewXY.y) / n.z;
    vec3 hit = vec3(viewXY, z);
    // exact (u,v) of the hit in the quad's own basis — rejects the dilation
    // fringe; adjacent coplanar quads share edges so no seams can open
    vec2 uv = vec2(dot(hit - vOriginView, vUAxisView), dot(hit - vOriginView, vVAxisView));
    if (uv.x < 0.0 || uv.y < 0.0 || uv.x > vQuadSize.x || uv.y > vQuadSize.y) {
        discard;
    }

    // exact distorted depth — mirrors the vertex stage's z path per mode
    float clipZ = ProjMat[2].z * z + ProjMat[3].z;
    float zd;
    if (uShadowDistortionMode == 3) {
        zd = clipZ * uShadowLogParams.w;
    } else if (uShadowDistortionMode == 5) {
        zd = (clipZ - ProjMat[3].z) * uShadowDepthScale;
    } else {
        zd = clipZ * uShadowDepthScale;
    }
    gl_FragDepth = clamp(zd * 0.5 + 0.5, 0.0, 1.0);
}
