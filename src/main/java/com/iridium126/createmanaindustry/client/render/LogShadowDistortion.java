package com.iridium126.createmanaindustry.client.render;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Bliss-style logarithmic shadow distortion ({@code shaders/lib/Shadow_Params.glsl}):
 * <pre>
 * const float k  = 1.8;
 * const float d0 = 0.04 + max(64.0 - shadowDistance, 0.0)/64.0 * 0.26;
 * const float d1 = 0.61;
 * float a = exp(d0);
 * float b = (exp(d1) - a) * 150./128.0;
 * distortFactor = log(length(p.xy) * b + a) * k;   p.xy /= distortFactor;
 * </pre>
 * with {@code shadow.vsh} additionally compressing depth via {@code gl_Position.z /= 6.0}
 * (so the mist shader's z scale is {@code 1/6}). Gated by
 * {@code #define DISTORT_SHADOWMAP} in {@code lib/settings.glsl}, which ships enabled.
 * <p>
 * {@code k} and {@code d1} are plain literals and are parsed from the pack with
 * fallback to the shipped defaults; {@code d0}'s clamp expression is replicated
 * from its literal pieces against the live shadow distance.
 */
final class LogShadowDistortion implements ShadowDistortionConvention {

    /** The exact distortion expression shape in {@code BiasShadowProjection}. */
    private static final Pattern SIGNATURE = Pattern.compile(
            "log\\s*\\(\\s*length\\s*\\([^)]*\\)\\s*\\*\\s*b\\s*\\+\\s*a\\s*\\)\\s*\\*\\s*k");
    /** The distortion gate define in {@code lib/settings.glsl} (must be active). */
    private static final Pattern ENABLED = Pattern.compile(
            "(?m)^\\s*#define\\s+DISTORT_SHADOWMAP\\b");
    private static final Pattern K = Pattern.compile(
            "const\\s+float\\s+k\\s*=\\s*([0-9.eE+-]+)\\s*;");
    private static final Pattern D1 = Pattern.compile(
            "const\\s+float\\s+d1\\s*=\\s*([0-9.eE+-]+)\\s*;");

    /** Shipped defaults, used when the literals cannot be parsed. */
    private static final float DEFAULT_K = 1.8F;
    private static final float DEFAULT_D1 = 0.61F;
    private static final float D0_BASE = 0.04F;
    private static final float D0_THRESHOLD = 64.0F;
    private static final float D0_DIVISOR = 64.0F;
    private static final float D0_SLOPE = 0.26F;
    /** {@code gl_Position.z /= 6.0} in the pack's shadow.vsh. */
    private static final float Z_SCALE = 1.0F / 6.0F;

    @Override
    public int glslMode() {
        return 3;
    }

    @Override
    public String displayName() {
        return "logarithmic (Bliss BiasShadowProjection)";
    }

    @Override
    public PackShadowParams tryResolve(PackShaderSource pack) {
        boolean signature = false;
        boolean enabled = false;
        Float k = null;
        Float d1 = null;
        for (String text : pack.candidateTexts()) {
            if (!signature && SIGNATURE.matcher(text).find())
                signature = true;
            if (!enabled && ENABLED.matcher(text).find())
                enabled = true;
            if (k == null)
                k = floatValue(K, text);
            if (d1 == null)
                d1 = floatValue(D1, text);
        }
        if (!signature || !enabled)
            return null;

        float kVal = k != null ? k : DEFAULT_K;
        float d1Val = d1 != null ? d1 : DEFAULT_D1;
        // d0's clamp only bites when the shadow distance sits below its threshold,
        // which never happens for typical setups — replicate it anyway so short
        // shadow distances match the pack exactly.
        float sd = EuclideanShadowDistortion.shadowDistance();
        float d0 = D0_BASE + Math.max(D0_THRESHOLD - sd, 0.0F) / D0_DIVISOR * D0_SLOPE;
        float a = (float) Math.exp(d0);
        float b = (float) ((Math.exp(d1Val) - a) * 150.0 / 128.0);
        return new PackShadowParams(glslMode(), kVal, Z_SCALE, kVal, a, b);
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
