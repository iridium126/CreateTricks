// ALLVR terrain fragment shader — V0 forward pass. Samples the vanilla block
// atlas with per-block tiling (fract over the greedy quad's local uv), the
// MIP-bleed guard via textureGrad against the LINEAR vUvLocal derivatives
// (fract itself is discontinuous — never feed its derivative to the sampler),
// biome tint, vanilla-style directional face shade and a day-factor ambient
// stand-in for the phase-5 light system, plus vanilla fog.
uniform sampler2D uAtlas;
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

out vec4 fragColor;

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
    vec3 color = tex.rgb * vTint.rgb * (vShade * uLight + 0.05);
    float fog = clamp((vDist - uFogStart) / max(uFogEnd - uFogStart, 1e-4), 0.0, 1.0);
    fragColor = vec4(mix(color, uFogColor, fog), 1.0);
}
