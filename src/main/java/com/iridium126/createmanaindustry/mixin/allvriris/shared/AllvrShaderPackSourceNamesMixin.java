package com.iridium126.createmanaindustry.mixin.allvriris.shared;

import com.google.common.collect.ImmutableList;
import com.iridium126.createmanaindustry.config.ClientConfig;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import net.irisshaders.iris.shaderpack.include.ShaderPackSourceNames;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/**
 * Registers the voxy adaptation file names with iris's source-name discovery
 * so pack edits to voxy.json/voxy_*.glsl hot-reload through F3+T like any other
 * pack source. Apply-time gated off while voxy is installed (its identical hook
 * already covers the names).
 */
@Mixin(value = ShaderPackSourceNames.class, remap = false)
public abstract class AllvrShaderPackSourceNamesMixin {

    @WrapOperation(method = "findPotentialStarts", at = @At(value = "INVOKE",
        target = "Lcom/google/common/collect/ImmutableList;builder()Lcom/google/common/collect/ImmutableList$Builder;"))
    private static ImmutableList.Builder<String> allvr$addVoxyNames(Operation<ImmutableList.Builder<String>> original) {
        var builder = original.call();
        if (ClientConfig.allvrIrisIntegration) {
            builder.add("voxy.json");
            builder.add("voxy_opaque.glsl");
            builder.add("voxy_translucent.glsl");
        }
        return builder;
    }
}
