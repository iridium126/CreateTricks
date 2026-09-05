package com.iridium126.createmanaindustry.mixin.allvriris.shared;

import com.iridium126.createmanaindustry.client.dimension.iris.AllvrVoxyUniforms;
import com.iridium126.createmanaindustry.config.ClientConfig;
import net.irisshaders.iris.gl.uniform.UniformHolder;
import net.irisshaders.iris.shaderpack.IdMap;
import net.irisshaders.iris.shaderpack.properties.PackDirectives;
import net.irisshaders.iris.uniforms.CommonUniforms;
import net.irisshaders.iris.uniforms.FrameUpdateNotifier;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Registers ALLVR's vx* uniform surface (packs declare these under
 * {@code #ifdef VOXY}) into every pipeline's non-dynamic uniform set. Apply-time
 * gated off while voxy is installed — voxy registers the same names and double
 * registration is undefined territory. Without voxy, registrations on
 * non-allay pipelines are inert: the VOXY define is per-dimension, so pack
 * shaders outside the allay dimension never reference these names.
 */
@Mixin(value = CommonUniforms.class, remap = false)
public abstract class AllvrCommonUniformsMixin {

    @Inject(method = "addNonDynamicUniforms", at = @At("HEAD"), remap = false)
    private static void allvr$injectMatrixUniforms(UniformHolder uniforms, IdMap idMap, PackDirectives directives,
                                                   FrameUpdateNotifier updateNotifier, CallbackInfo ci) {
        if (ClientConfig.allvrIrisIntegration) {
            AllvrVoxyUniforms.addUniforms(uniforms);
        }
    }
}
