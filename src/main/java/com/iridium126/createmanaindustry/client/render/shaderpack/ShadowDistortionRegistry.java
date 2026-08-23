package com.iridium126.createmanaindustry.client.render.shaderpack;

import java.nio.file.Path;
import java.util.List;

import com.iridium126.createmanaindustry.CreateManaIndustry;

import net.irisshaders.iris.Iris;

/**
 * Registry of recognised shaderpack shadow-map distortion conventions. The mist
 * Tyndall sampling must match the pack's own distortion, so the active pack is
 * scanned and the first matching convention resolves the parameters.
 * <p>
 * To adapt a new pack: implement {@link ShadowDistortionConvention}, add the
 * matching GLSL branch to {@code distort_shadow_space} in
 * {@code mist_volumetric_iris.fsh}, and register the instance in
 * {@link #CONVENTIONS}.
 */
public final class ShadowDistortionRegistry {

    private static final List<ShadowDistortionConvention> CONVENTIONS = List.of(
            new QuarticShadowDistortion(),
            new EuclideanShadowDistortion(),
            new LogShadowDistortion(),
            new SundialShadowDistortion());

    private static String lastSignature = "";
    private static PackShadowParams lastParams = PackShadowParams.NONE;

    private ShadowDistortionRegistry() {}

    /**
     * Resolves the active shaderpack's distortion parameters, cached per pack
     * name + shadow half-plane (the Complementary bias depends on the latter, so
     * the cache refreshes when the shadow distance changes).
     */
    public static PackShadowParams resolveForCurrentPack() {
        String name;
        try {
            name = Iris.getIrisConfig().getShaderPackName().orElse("");
        } catch (RuntimeException | LinkageError e) {
            return PackShadowParams.NONE;
        }
        if (name.isEmpty() || name.equals("(internal)"))
            return PackShadowParams.NONE;

        String signature = name + "/" + EuclideanShadowDistortion.shadowDistance();
        if (signature.equals(lastSignature))
            return lastParams;
        lastSignature = signature;

        PackShadowParams params = PackShadowParams.NONE;
        try {
            Path pack = Iris.getShaderpacksDirectory().resolve(name);
            PackShaderSource source = PackShaderSource.of(pack);
            for (ShadowDistortionConvention c : CONVENTIONS) {
                PackShadowParams p = c.tryResolve(source);
                if (p != null) {
                    params = p;
                    break;
                }
            }
        } catch (RuntimeException | LinkageError e) {
            CreateManaIndustry.LOGGER.warn("Failed to resolve shadow distortion for pack '{}'", name, e);
        }
        lastParams = params;
        CreateManaIndustry.LOGGER.info("Shadow pack '{}' distortion: {} (mode={}, bias={}, depthScale={})",
                name, params == PackShadowParams.NONE ? "none" : "matched",
                params.glslMode(), params.bias(), params.depthScale());
        return params;
    }
}