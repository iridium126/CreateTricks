// Textured (ALPHA) particle fragment shader: samples the sprite atlas, applies
// the per-particle tint/intensity and a distance fade, outputs normal alpha so
// the sorted back-to-front draw composites translucent overlapping petals.
uniform sampler2D uSprite;
uniform float uFadeDist;

in vec2 vUv;
in vec3 vColor;
in float vAlpha;
in float vDist;

out vec4 fragColor;

void main() {
    vec4 tex = texture(uSprite, vUv);
    if (tex.a < 0.02)
        discard;
    float farFade = 1.0 - smoothstep(uFadeDist, uFadeDist + 24.0, vDist);
    float a = vAlpha * tex.a * farFade;
    vec3 col = vColor * tex.rgb;
    fragColor = vec4(col, a);
}
