// MODEL particle fragment shader: fullbright cutout. Vanilla renders the
// allay with block light 15 (AllayRenderer.getBlockLightLevel), so the texel
// is drawn unlit; the emitter tint was already applied in the vertex stage.
// Opaque output with alpha discard — this pass writes depth (self-occlusion,
// correct occlusion of and by other particles/terrain), so no blending.
uniform sampler2D uSprite;
uniform float uFadeDist;

in vec2 vUv;
in vec3 vColor;
in float vDist;

out vec4 fragColor;

void main() {
    vec4 tex = texture(uSprite, vUv);
    float farFade = 1.0 - smoothstep(uFadeDist, uFadeDist + 24.0, vDist);
    if (tex.a * farFade < 0.5)
        discard;
    fragColor = vec4(tex.rgb * vColor, 1.0);
}
