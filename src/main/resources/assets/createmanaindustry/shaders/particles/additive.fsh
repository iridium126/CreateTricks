// Additive particle fragment shader: soft radial falloff + distance fade.
uniform float uFadeDist;
uniform float uGlow;

in vec2 vUv;
in vec3 vColor;
in float vAlpha;
in float vDist;

out vec4 fragColor;

void main() {
    vec2 c = vUv - 0.5;
    float r2 = dot(c, c);
    if (r2 > 0.25)
        discard;
    float radial = smoothstep(0.25, 0.0, r2);
    float alpha = vAlpha * radial * radial;
    float farFade = 1.0 - smoothstep(uFadeDist, uFadeDist + 24.0, vDist);
    alpha *= farFade;
    vec3 col = vColor * (alpha * uGlow);
    fragColor = vec4(col, 1.0);
}
