// Shared HiZ occlusion test (doc §9.2), included by the traversal and
// revalidate kernels. Conservative by construction: the pyramid stores MAX
// window-space depth (farthest occluder per texel), the mip is chosen so the
// node's dilated screen rect covers ≤ 1 texel (coarser = larger max = safe),
// nodes touching/behind the near plane pass, and the depth comparison bias is
// scaled by the node's nearest view distance (1.5 blocks of view-space slack —
// depth precision collapses with distance, a constant epsilon would flicker).
// Degrades safely: sky = depth 1.0 → nothing occluded against sky regions.
uniform sampler2D uHiz;      // unit HIZ_UNIT (bound by the renderer when enabled)
uniform mat4 uModelView;
uniform mat4 uProj;
uniform vec2 uViewport;      // main target pixel size
uniform int uHizTopLevel;    // highest populated mip
uniform float uDepthBiasScale; // 1.5 * near * far / (far - near)

bool allvrHizOccluded(ivec3 relOrigin, ivec3 extent) {
    float minZ = 1e30;
    float minW = 1e30;
    vec2 ndcMin = vec2(1e30);
    vec2 ndcMax = vec2(-1e30);
    for (int i = 0; i < 8; i++) {
        ivec3 corner = relOrigin + extent * ivec3(i & 1, (i >> 1) & 1, (i >> 2) & 1);
        vec4 vp = uModelView * vec4(vec3(corner), 1.0);
        vec4 clip = uProj * vp;
        if (clip.w <= 0.0) {
            return false; // touches/behind the near plane — conservative pass
        }
        vec3 ndc = clip.xyz / clip.w;
        minZ = min(minZ, ndc.z);
        minW = min(minW, clip.w);
        ndcMin = min(ndcMin, ndc.xy);
        ndcMax = max(ndcMax, ndc.xy);
    }
    // screen rect (pixels) + 2 px conservative dilation
    vec2 pxMin = (ndcMin * 0.5 + 0.5) * uViewport - 2.0;
    vec2 pxMax = (ndcMax * 0.5 + 0.5) * uViewport + 2.0;
    vec2 dPx = max(pxMax - pxMin, vec2(1.0));
    // ceil → the rect covers ≤ 1 texel at the sampled mip (conservative)
    float level = min(ceil(log2(max(dPx.x, dPx.y))), float(uHizTopLevel));
    vec2 uv = ((ndcMin + ndcMax) * 0.5) * 0.5 + 0.5;
    float pyramidZ = textureLod(uHiz, uv, level).r;
    float eps = uDepthBiasScale / max(minW * minW, 1e-8) + 1e-7;
    return minZ > pyramidZ + eps;
}
