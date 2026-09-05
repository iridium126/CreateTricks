// ALLVR terrain fragment shader — V0 forward pass. Samples the vanilla block
// atlas with per-block tiling (fract over the greedy quad's local uv), the
// MIP-bleed guard via textureGrad against the LINEAR vUvLocal derivatives
// (fract itself is discontinuous — never feed its derivative to the sampler),
// biome tint, vanilla-style directional face shade and a day-factor ambient
// stand-in for the phase-5 light system, plus vanilla fog.
uniform sampler2D uAtlas;
uniform sampler2D uLightmapTex; // albedo pass only (level-2 coexistence)
uniform float uLight;    // 0.25..1.0 day factor
uniform float uFogStart;
uniform float uFogEnd;
uniform vec3 uFogColor;

in vec2 vUvLocal;
flat in vec4 vRect;   // (u0, v0, du, dv)
flat in vec4 vTint;   // rgb + renderable flag
flat in vec4 vInset;  // half-texel inset (tile space)
flat in float vShade;
flat in float vDist;
// iris-integration inputs (see AllvrShaderCache's patched program header)
flat in vec2 vLight;     // baked light: (block/15, sky/15)
flat in float vCustomId; // iris block material id
flat in uint vFaceVoxy;  // voxy face encoding

#ifdef PATCHED_SHADER
// voxy contract seam: the pack's voxy_opaque.glsl (appended by the patched
// program build) defines voxy_emitFragment, whose own `layout(location=..) out`
// declarations write the pack's gbuffer targets — this shader declares NO
// fragColor output in patched mode (opaqueDrawBuffers decides the mapping).
struct VoxyFragmentParameters {
    vec4 sampledColour;
    vec2 tile;
    vec2 uv;
    uint face;
    uint modelId;
    vec2 lightMap;
    vec4 tinting;
    uint customId;//Same as iris's modelId
};
void voxy_emitFragment(VoxyFragmentParameters parameters);
#else
out vec4 fragColor;
#endif

void main() {
    vec2 tiled = fract(vUvLocal);
    vec2 inset = vInset.xy;
    // clamp (not range-scale) into the sprite: a 1:1 screen pixel lands exactly
    // on the clamp bounds (pure border texels — vanilla-identical), and at other
    // scales border texels stretch by at most half a texel instead of the whole
    // face shrinking to 15×15. All sample points stay half a texel inside the
    // sprite, so bilinear never crosses into the atlas neighbour.
    vec2 atlasUv = vRect.xy + clamp(tiled, inset, 1.0 - inset) * vRect.zw;
    // continuous gradients: vUvLocal is piecewise-linear, fract/clamp are not —
    // feed the unclamped derivative to the sampler (fract's own derivative
    // would poison the mip selection at every tile boundary)
    vec4 tex = textureGrad(uAtlas, atlasUv, dFdx(vUvLocal) * vRect.zw, dFdy(vUvLocal) * vRect.zw);
    if (tex.a < 0.1 || vTint.w < 0.5) {
        discard;
    }
#ifdef PATCHED_SHADER
    // pack-lit path: raw sample + metadata go to the pack's patch; shading,
    // fog and directional attenuation are the pack's job (voxy contract).
    // lightMap = vanilla lightmap UV from the baked nibbles (voxy's
    // getLightmapUv formula: x = block channel, y = sky channel).
    vec2 lm = clamp(vLight * (15.0 / 16.0) + (0.5 / 16.0), vec2(8.0 / 256.0), vec2(248.0 / 256.0));
    voxy_emitFragment(VoxyFragmentParameters(
        tex,                // sampledColour (pack applies tinting.rgb itself)
        floor(vUvLocal),    // tile
        atlasUv,            // uv
        vFaceVoxy,          // face
        0u,                 // modelId (voxy-internal space; packs don't consume)
        lm,                 // lightMap
        vTint,              // tinting
        uint(vCustomId)     // customId
    ));
#elif defined(ALLVR_ALBEDO_PASS)
    // level-2 coexistence (pack declares draw buffers but ships no patch —
    // e.g. Complementary): mimic what the vanilla gbuffer writes so the
    // pack's deferred lighting treats our pixels like vanilla terrain —
    // albedo × tint × directional shade × lightmap, NO fog (the pack fogs
    // from depth in its deferred passes)
    vec2 lm2 = clamp(vLight * (15.0 / 16.0) + (0.5 / 16.0), vec2(8.0 / 256.0), vec2(248.0 / 256.0));
    vec3 albedo = tex.rgb * vTint.rgb * vShade * texture(uLightmapTex, lm2).rgb;
    fragColor = vec4(albedo, 1.0);
#else
    vec3 color = tex.rgb * vTint.rgb * (vShade * uLight + 0.05);
    float fog = clamp((vDist - uFogStart) / max(uFogEnd - uFogStart, 1e-4), 0.0, 1.0);
    fragColor = vec4(mix(color, uFogColor, fog), 1.0);
#endif
}
