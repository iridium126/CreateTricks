package com.iridium126.createmanaindustry.mixin.allvriris.shared;

import com.google.common.collect.ImmutableSet;
import com.iridium126.createmanaindustry.config.ClientConfig;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import net.irisshaders.iris.shaderpack.properties.PackRenderTargetDirectives;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/**
 * Extends iris's known render-target set with colortex 16..19 so packs can
 * route voxy-adapted translucent content there (Photon's voxy.json requests
 * colortex16). voxy's own hook for this is a raw {@code @Redirect}, which
 * cannot coexist with a second handler on the same call — hence this is a
 * chainable {@code @WrapOperation} AND apply-time gated off while voxy is
 * installed (either direction alone is correct).
 */
@Mixin(value = PackRenderTargetDirectives.class, remap = false)
public abstract class AllvrPackRenderTargetDirectivesMixin {

    @WrapOperation(method = "<clinit>", at = @At(value = "INVOKE",
        target = "Lcom/google/common/collect/ImmutableSet$Builder;build()Lcom/google/common/collect/ImmutableSet;"))
    private static ImmutableSet<Integer> allvr$extendColourTex(ImmutableSet.Builder<Integer> builder,
                                                               Operation<ImmutableSet> original) {
        if (ClientConfig.allvrIrisIntegration) {
            for (int i = 16; i < 20; i++) {
                builder.add(i);
            }
        }
        return original.call(builder);
    }
}
