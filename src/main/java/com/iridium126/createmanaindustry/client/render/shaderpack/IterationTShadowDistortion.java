package com.iridium126.createmanaindustry.client.render.shaderpack;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

import com.iridium126.createmanaindustry.CreateManaIndustry;

import net.irisshaders.iris.shadows.ShadowRenderer;

/**
 * iterationT shadow distortion — the classic linear length compression with a
 * constant bias, plus an extra margin factor:
 * <pre>
 * // Lib/Programs/Gbuffers/Shadow_VS.glsl (Sunlight_Shadow.glsl mirrors it):
 * float dist = length(clip.xy);
 * float distortFactor = (1.0 - SHADOW_MAP_BIAS) + dist * SHADOW_MAP_BIAS;
 * gl_Position.xy *= 0.95 / distortFactor;
 * </pre>
 * Two things set it apart from the Complementary euclidean convention
 * (GLSL mode 2) and keep it on its own mode:
 * <ul>
 *   <li>the bias is a plain slider define (SHADOW_MAP_BIAS, default 0.9),
 *       not derived from the shadow distance;</li>
 *   <li>the distorted coordinates carry a hardcoded 0.95 margin toward the
 *       map centre — reusing mode 2 would land every sample ~5% too far out.</li>
 * </ul>
 * Depth needs special care as well: the pack never uses the projection's z
 * row — its shadow stage rebuilds clip z as viewZ * (-m00 * 0.5). The mist
 * caller's clip z comes from the real ortho z row plus its constant offset,
 * so mode 5 strips that offset and rescales by depthScale = -m00 * 0.5 / m22,
 * resolved from the live shadow projection (with Iris' default near/far
 * planes this simplifies to 64 / shadowDistance — exactly 1/3 at the pack's
 * shipped 192).
 * <p>
 * Detection requires both the exact distortion expression and the exact 0.95
 * margin application; any drift across pack versions fails open.
 */
final class IterationTShadowDistortion implements ShadowDistortionConvention {

    /** The exact linear-in-length distortion expression shared by both stages. */
    private static final Pattern SIGNATURE = Pattern.compile(
            "\\(1\\.0\\s*-\\s*SHADOW_MAP_BIAS\\)\\s*\\+\\s*dist\\s*\\*\\s*SHADOW_MAP_BIAS");
    /** The exact margin application baked into the pack's shadow vertex stage. */
    private static final Pattern MARGIN = Pattern.compile(
            "gl_Position\\.xy\\s*\\*=\\s*0\\.95\\s*/\\s*distortFactor");
    /** The bias slider define in Lib/Settings.glsl. */
    private static final Pattern BIAS_DEFINE = Pattern.compile(
            "#define\\s+SHADOW_MAP_BIAS\\s*([-+]?[0-9]*\\.?[0-9]+)");

    /** Shipped default of the SHADOW_MAP_BIAS slider, used when unparseable. */
    private static final float DEFAULT_BIAS = 0.9F;
    /**
     * Iris' default ortho z scale for the projection-null fallback: m22 of an
     * ortho over the default near 0.05 / far 256 planes.
     */
    private static final float DEFAULT_ORTHO_M22 = -2.0F / (256.0F - 0.05F);

    @Override
    public int glslMode() {
        return 5;
    }

    @Override
    public String displayName() {
        return "linear-margin (iterationT SHADOW_MAP_BIAS)";
    }

    @Override
    public PackShadowParams tryResolve(PackShaderSource pack) {
        boolean signature = false;
        boolean margin = false;
        Float definedBias = null;
        for (String text : pack.candidateTexts()) {
            if (!signature && SIGNATURE.matcher(text).find())
                signature = true;
            if (!margin && MARGIN.matcher(text).find())
                margin = true;
            if (definedBias == null)
                definedBias = floatValue(BIAS_DEFINE, text);
        }
        // Both halves must be present: matching the curve without the margin
        // (or the reverse) would sample consistently wrong texels.
        if (!signature || !margin)
            return null;

        float shippedBias = definedBias != null ? definedBias : DEFAULT_BIAS;
        if (definedBias == null)
            CreateManaIndustry.LOGGER.warn(
                    "iterationT SHADOW_MAP_BIAS not found; assuming shipped default {}", DEFAULT_BIAS);
        // The bias is a user-facing slider; the persisted option file carries
        // non-default overrides just like the Bliss DH-shadowmap gate.
        float bias = (float) ActivePackOptions.doubleValue("SHADOW_MAP_BIAS", shippedBias);
        return PackShadowParams.radial(glslMode(), bias, depthScale());
    }

    /**
     * Ratio between the mist shader's clip z and the pack's rebuilt clip z:
     * (-m00 * 0.5) / m22 of the live shadow projection. Falls back to Iris'
     * default-ortho m22 combined with the current half-plane while the shadow
     * renderer has not produced a projection yet.
     */
    private static float depthScale() {
        try {
            if (ShadowRenderer.PROJECTION != null) {
                float m00 = ShadowRenderer.PROJECTION.m00();
                float m22 = ShadowRenderer.PROJECTION.m22();
                if (m00 > 0.0F && m22 < 0.0F)
                    return (-m00 * 0.5F) / m22;
            }
        } catch (RuntimeException | LinkageError e) {
            // fall through to the default-ortho estimate
        }
        int halfPlane = EuclideanShadowDistortion.shadowDistance();
        return (0.5F / Math.max(halfPlane, 1)) / -DEFAULT_ORTHO_M22;
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
