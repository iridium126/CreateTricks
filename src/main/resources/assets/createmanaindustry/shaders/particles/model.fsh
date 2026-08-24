// MODEL particle fragment shader, two modes selected by uMode:
//   0 — opaque cutout (head/skin/arms): fullbright texel (vanilla renders the
//       allay with block light 15, AllayRenderer.getBlockLightLevel), alpha
//       discard at 0.5, depth WRITES — self-occlusion and occlusion of/by
//       later passes stay correct.
//   1 — translucent blend (cloak + wings): the texture's alpha<255 texels
//       (cloak is alpha 160) composite with normal alpha blending; depth test
//       stays on but depth is NOT written, so the ghostly surfaces never
//       occlude what is drawn after them. No sorting — within-model ordering
//       comes free from the opaque segment's depth writes.
uniform sampler2D uSprite;
uniform float uFadeDist;
uniform int uMode;

in vec2 vUv;
in vec3 vColor;
in float vDist;

out vec4 fragColor;

void main() {
    vec4 tex = texture(uSprite, vUv);
    float farFade = 1.0 - smoothstep(uFadeDist, uFadeDist + 24.0, vDist);
    if (uMode == 0) {
        if (tex.a * farFade < 0.5)
            discard;
        fragColor = vec4(tex.rgb * vColor, 1.0);
    } else {
        if (tex.a < 0.02)
            discard;
        fragColor = vec4(tex.rgb * vColor, tex.a * farFade);
    }
}
