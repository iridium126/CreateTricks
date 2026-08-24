// MODEL particle fragment shader, two segments selected by the flat vSeg
// varying (fed by the baseInstance-driven mode attribute in the vertex stage,
// so ONE glMultiDrawElementsIndirect renders both):
//   segment 0 -- opaque cutout (head/skin/arms): fullbright texel (vanilla
//       renders the allay with block light 15, AllayRenderer.getBlockLightLevel),
//       alpha discard at 0.5, depth WRITES -- self-occlusion and occlusion
//       of/by later passes stay correct.
//   segment 1 -- translucent blend (cloak + wings): the texture's alpha<255
//       texels (cloak is alpha 160) composite with normal alpha blending;
//       depth test stays on AND depth is written, so the ghost surfaces
//       occlude what is drawn after them (sprites behind a cloak are hidden,
//       the documented tradeoff) while the double-wound shell keeps a single
//       blend per pixel from BOTH sides.
uniform sampler2D uSprite;
uniform float uFadeDist;

in vec2 vUv;
in vec3 vColor;
in float vDist;
flat in float vSeg;

out vec4 fragColor;

void main() {
    vec4 tex = texture(uSprite, vUv);
    float farFade = 1.0 - smoothstep(uFadeDist, uFadeDist + 24.0, vDist);
    if (vSeg < 0.5) {
        if (tex.a * farFade < 0.5)
            discard;
        fragColor = vec4(tex.rgb * vColor, 1.0);
    } else {
        if (tex.a < 0.02)
            discard;
        fragColor = vec4(tex.rgb * vColor, tex.a * farFade);
    }
}
