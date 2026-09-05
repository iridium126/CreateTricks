package com.iridium126.createmanaindustry.mixin.allvriris;

import com.google.common.collect.ImmutableList;
import com.iridium126.createmanaindustry.client.dimension.iris.AllvrIrisPipelineCapture;
import com.iridium126.createmanaindustry.client.dimension.iris.AllvrVoxyPatch;
import com.iridium126.createmanaindustry.config.ClientConfig;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import net.irisshaders.iris.helpers.StringPair;
import net.irisshaders.iris.shaderpack.ShaderPack;
import net.irisshaders.iris.shaderpack.preprocessor.JcppProcessor;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/**
 * Per-dimension injection of the {@code VOXY} define (grilling decision 3, ALLVR
 * variant): iris 1.7.3 preprocesses every pack source read through
 * {@code ShaderPack}'s sourceProvider lambda (the single
 * {@code glslPreprocessSource} call site), whose environment-define list is
 * pack-global — so the pack-global StandardMacros hook voxy uses cannot express
 * "allay dimension only". Wrapping the one call site and prepending the define
 * pair while the allay pipeline is being built achieves it: allay-dimension
 * programs see {@code #define VOXY 2} (byte-identical to voxy's value), every
 * other dimension's programs never do — zero exposure, no neutralization
 * needed. The call site lives in the compiler-generated sourceProvider lambda
 * {@code lambda$new$8} (iris 1.8.14-beta.1, verified the single
 * {@code glslPreprocessSource} invocation in the class): injector selectors do
 * not match synthetic lambdas by wildcard, so the exact name is pinned and will
 * shift if iris recompiles ShaderPack. Applied only while the voxy mod is
 * absent (apply-time gate); with voxy installed its own pack-global define
 * governs, exactly as users already have it.
 */
@Mixin(value = ShaderPack.class, remap = false)
public abstract class AllvrShaderPackMixin {

    @WrapOperation(method = "lambda$new$8", at = @At(value = "INVOKE",
        target = "Lnet/irisshaders/iris/shaderpack/preprocessor/JcppProcessor;glslPreprocessSource(Ljava/lang/String;Ljava/lang/Iterable;)Ljava/lang/String;"))
    private static String allvr$injectVoxyDefine(String source, Iterable<StringPair> defines, Operation<String> original) {
        if (ClientConfig.allvrIrisIntegration && AllvrIrisPipelineCapture.isBuildingAllayPipeline()) {
            return original.call(source, ImmutableList.<StringPair>builder()
                .add(new StringPair("VOXY", String.valueOf(AllvrVoxyPatch.SHADER_DEFINE_VERSION)))
                .addAll(defines)
                .build());
        }
        return original.call(source, defines);
    }
}
