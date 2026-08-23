package com.iridium126.createmanaindustry.client.render.shaderpack;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

import com.iridium126.createmanaindustry.CreateManaIndustry;

/**
 * Sundial Lite (GeForceLegend) shadow distortion — a logarithmic length
 * compression whose result is written into a tiled shadow atlas:
 * <pre>
 * // shadow.vsh (clip space):
 * float f = log(S / clipLengthInv + 1) / log(S + 1);   // S = distortionStrength
 * gl_Position.xy *= clipLengthInv * f * 0.5;
 * gl_Position.xy += 0.5 + tileOffset;                  // GPU viewport halves this
 * gl_Position.z *= 0.2;
 * // libs/Shadow.glsl sampling side (screen space), opaque tile:
 * shadowCoord.xy = dir * f * 0.25 + 0.75;
 * </pre>
 * The vertex path adds its {@code 0.5} in <em>clip</em> space, which the
 * viewport transform halves — so both sides agree on the opaque tile centre
 * {@code (0.75, 0.5)}: {@code screen.xy = dir*f*0.25 + 0.75}. Depth stores
 * {@code ndcZ*0.1 + 0.5}, i.e. the familiar clip-space scale of 0.2. In the
 * mist shader's clip-space convention ({@code distort_shadow_space} followed
 * by the caller's {@code *0.5 + 0.5}) that is {@code dir*f*0.5 + 0.5} with
 * {@code ShadowDepthScale = 0.2} (GLSL mode 4).
 * <p>
 * {@code S = exp(SHADOW_DISTORTION_STRENGTH) - 1}; the shipped default is
 * {@code 4.0}, so S is about 53.6. Detection requires the pack's distinctive
 * distortion expression plus the strength define — the latter lives in
 * {@code settings/GlobalSettings.glsl}, which {@link PackShaderSource}
 * surfaces through its settings-directory scan.
 */
final class SundialShadowDistortion implements ShadowDistortionConvention {

    /** The exact distortion expression shape shared by shadow.vsh and libs/Shadow.glsl. */
    private static final Pattern SIGNATURE = Pattern.compile(
            "log\\s*\\(\\s*distortionStrength\\s*/\\s*clipLengthInv\\s*\\+\\s*1\\.0\\s*\\)"
                    + "\\s*/\\s*log\\s*\\(\\s*distortionStrength\\s*\\+\\s*1\\.0\\s*\\)");
    private static final Pattern STRENGTH = Pattern.compile(
            "#define\\s+SHADOW_DISTORTION_STRENGTH\\s*([-+]?[0-9]*\\.?[0-9]+)");

    /** Shipped default of SHADOW_DISTORTION_STRENGTH, used when unparseable. */
    private static final float DEFAULT_STRENGTH = 4.0F;
    /** The pack's unconditional {@code gl_Position.z *= 0.2} in shadow.vsh. */
    private static final float Z_SCALE = 0.2F;

    @Override
    public int glslMode() {
        return 4;
    }

    @Override
    public String displayName() {
        return "log-tile (Sundial SHADOW_DISTORTION_STRENGTH)";
    }

    @Override
    public PackShadowParams tryResolve(PackShaderSource pack) {
        boolean signature = false;
        Float strength = null;
        for (String text : pack.candidateTexts()) {
            if (!signature && SIGNATURE.matcher(text).find())
                signature = true;
            if (strength == null)
                strength = floatValue(STRENGTH, text);
        }
        if (!signature)
            return null;

        float defined = strength != null ? strength : DEFAULT_STRENGTH;
        if (strength == null)
            CreateManaIndustry.LOGGER.warn(
                    "Sundial SHADOW_DISTORTION_STRENGTH not found; assuming shipped default {}",
                    DEFAULT_STRENGTH);
        float s = (float) (Math.exp(defined) - 1.0);
        return PackShadowParams.radial(glslMode(), s, Z_SCALE);
    }

    private static Float floatValue(Pattern pattern, String text) {
        Matcher m = pattern.matcher(text);
        if (!m.find())
            return null;
        try {
            return Float.parseFloat(m.group(1));
        } catch (NumberFormatException e) {
            return null;
        }
    }
}
