package com.iridium126.createmanaindustry.client.render;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

import net.irisshaders.iris.gui.option.IrisVideoSettings;
import net.irisshaders.iris.shadows.ShadowRenderer;

/**
 * Complementary-style euclidean shadow distortion:
 * {@code distortFactor = length(clip.xy) * shadowMapBias + (1 - shadowMapBias)},
 * with {@code shadowMapBias = 1 - 25.6/shadowDistance}. Detected by the
 * {@code shadowMapBias} define in the pack's shaders; depth scale is a
 * hardcoded {@code 0.2}.
 */
final class EuclideanShadowDistortion implements ShadowDistortionConvention {

    private static final Pattern BIAS = Pattern.compile(
            "(?:#define|const float)\\s+shadowMapBias\\s*[:=]?\\s*([^;]+)");
    private static final Pattern BIAS_EXPR = Pattern.compile(
            "([-+]?[0-9]*\\.?[0-9]+)\\s*[-+]\\s*([-+]?[0-9]*\\.?[0-9]+)\\s*/\\s*shadowDistance");

    @Override
    public int glslMode() {
        return 2;
    }

    @Override
    public String displayName() {
        return "euclidean (Complementary shadowMapBias)";
    }

    @Override
    public PackShadowParams tryResolve(PackShaderSource pack) {
        for (String text : pack.candidateTexts()) {
            String expr = valueOf(BIAS, text);
            if (expr == null)
                continue;
            Float bias = evaluateBias(expr);
            return new PackShadowParams(glslMode(), bias != null ? bias : 1.0F, 0.2F);
        }
        return null;
    }

    /**
     * Evaluates {@code shadowMapBias = N - M / shadowDistance}. The
     * {@code shadowDistance} is the shadow camera half-plane in blocks, which the
     * ortho projection exposes directly ({@code m00 = 1/halfPlane}); falls back
     * to a typical 128 when the projection isn't available yet.
     */
    private static Float evaluateBias(String expr) {
        Matcher m = BIAS_EXPR.matcher(expr);
        if (m.find()) {
            try {
                float a = Float.parseFloat(m.group(1));
                float b = Float.parseFloat(m.group(2));
                float bias = a - b / shadowDistance();
                return Math.max(0.5F, Math.min(0.95F, bias));
            } catch (NumberFormatException e) {
                // fall through to literal
            }
        }
        try {
            return Float.parseFloat(expr);
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /** Current shadow camera half-plane in blocks, or a typical 128 fallback. */
    static int shadowDistance() {
        try {
            if (ShadowRenderer.PROJECTION != null) {
                float m00 = ShadowRenderer.PROJECTION.m00();
                if (m00 > 0.0F) {
                    int halfPlane = Math.round(1.0F / m00);
                    if (halfPlane >= 64)
                        return halfPlane;
                }
            }
            int v = IrisVideoSettings.getOverriddenShadowDistance(IrisVideoSettings.shadowDistance);
            if (v >= 64)
                return v;
        } catch (RuntimeException | LinkageError e) {
            // fall through
        }
        return 128;
    }

    private static String valueOf(Pattern p, String text) {
        Matcher m = p.matcher(text);
        return m.find() ? m.group(1) : null;
    }
}
