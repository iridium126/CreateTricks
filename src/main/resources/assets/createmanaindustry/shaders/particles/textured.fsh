// Textured billboard fragment shader, two modes selected by uMode:
//   0 — ALPHA blended: samples the sprite atlas, applies tint/intensity and a
//       distance fade, outputs normal alpha so the sorted back-to-front draw
//       composites overlapping translucent sprites correctly.
//   1 — OPAQUE cutout: hard discard at 0.5 with an opaque output and depth
//       writes (blend off) — order-independent, like the MODEL opaque segment.
uniform sampler2D uSprite;
uniform float uFadeDist;
uniform int uMode;

in vec2 vUv;
in vec3 vColor;
in float vAlpha;
in float vDist;

out vec4 fragColor;

void main() {
    vec4 tex = texture(uSprite, vUv);
    float farFade = 1.0 - smoothstep(uFadeDist, uFadeDist + 24.0, vDist);
    if (uMode == 1) {
        // vanilla PARTICLE_SHEET_OPAQUE alpha test (particle.fsh: a < 0.1
        // discard, no blend, depth write); 0.1 (not 0.5) keeps the soft
        // edge texels of the crit/heart stars visible exactly like vanilla
        if (tex.a * farFade < 0.1)
            discard;
        fragColor = vec4(vColor * tex.rgb, 1.0);
    } else {
        if (tex.a < 0.02)
            discard;
        float a = vAlpha * tex.a * farFade;
        fragColor = vec4(vColor * tex.rgb, a);
    }
}
