package com.iridium126.createmanaindustry.client.render.shaderpack;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Photon-style quartic shadow distortion:
 * {@code distortFactor = quartic_length(clip.xy) * SHADOW_DISTORTION + (1 - SHADOW_DISTORTION)}.
 * Detected by the {@code SHADOW_DISTORTION} define in the pack's settings;
 * depth scale comes from {@code SHADOW_DEPTH_SCALE} (default {@code 0.2}).
 */
final class QuarticShadowDistortion implements ShadowDistortionConvention {

    private static final Pattern DISTORTION = Pattern.compile(
            "(?:#define|const float)\\s+SHADOW_DISTORTION\\s*[:=]?\\s*([-+]?[0-9]*\\.?[0-9]+)");
    private static final Pattern DEPTH = Pattern.compile(
            "(?:#define|const float)\\s+SHADOW_DEPTH_SCALE\\s*[:=]?\\s*([-+]?[0-9]*\\.?[0-9]+)");

    @Override
    public int glslMode() {
        return 1;
    }

    @Override
    public String displayName() {
        return "quartic (Photon SHADOW_DISTORTION)";
    }

    @Override
    public PackShadowParams tryResolve(PackShaderSource pack) {
        for (String text : pack.candidateTexts()) {
            Float bias = valueOf(DISTORTION, text);
            if (bias == null)
                continue;
            Float depth = valueOf(DEPTH, text);
            return PackShadowParams.radial(glslMode(), bias, depth != null ? depth : 0.2F);
        }
        return null;
    }

    private static Float valueOf(Pattern p, String text) {
        Matcher m = p.matcher(text);
        if (m.find()) {
            try {
                return Float.parseFloat(m.group(1));
            } catch (NumberFormatException e) {
                return null;
            }
        }
        return null;
    }
}