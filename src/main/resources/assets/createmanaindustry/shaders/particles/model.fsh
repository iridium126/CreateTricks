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
flat in vec3 vNormalView;

out vec4 fragColor;

// vanilla entity diffuse (minecraft_mix_light): two fixed VIEW-SPACE
// directional lights — Lighting.DIFFUSE_LIGHT_0/1 (setupLevel), normalized.
// The dirs MIRROR in z (+/-0.7): together they cover front AND back faces
// (~0.97 each). A wrong z sign here darkens all camera-away faces to the
// 0.4 ambient floor ('overall darker, higher contrast' symptom).
const vec3 LIGHT0_DIR = vec3(0.16169, 0.80845, -0.56594);
const vec3 LIGHT1_DIR = vec3(-0.16169, 0.80845, 0.56594);
// near-white base standing in for the lightmap at block light 15; calibration
// port for parity tuning (docs/allay-particle-vanilla-alignment.md step 1.2)
const float BASE_BRIGHTNESS = 0.97;

void main() {
    vec4 tex = texture(uSprite, vUv);
    float farFade = 1.0 - smoothstep(uFadeDist, uFadeDist + 24.0, vDist);
    // double-wound shells expose either winding; orient the normal against the
    // real facing so both sides of wings/cloak shade identically
    vec3 n = normalize(vNormalView);
    if (!gl_FrontFacing)
        n = -n;
    float diffuse = clamp(0.4 + max(dot(LIGHT0_DIR, n), 0.0)
                              + max(dot(LIGHT1_DIR, n), 0.0), 0.0, 1.0);
    vec3 shaded = tex.rgb * vColor * (BASE_BRIGHTNESS * diffuse);
    if (vSeg < 0.5) {
        if (tex.a * farFade < 0.5)
            discard;
        fragColor = vec4(shaded, 1.0);
    } else {
        if (tex.a < 0.02)
            discard;
        fragColor = vec4(shaded, tex.a * farFade);
    }
}
