package com.iridium126.createmanaindustry.mixin.allvriris.shared;

import com.iridium126.createmanaindustry.client.dimension.iris.AllvrVoxySamplers;
import com.iridium126.createmanaindustry.config.ClientConfig;
import net.irisshaders.iris.gl.sampler.SamplerHolder;
import net.irisshaders.iris.pipeline.WorldRenderingPipeline;
import net.irisshaders.iris.samplers.IrisSamplers;
import net.irisshaders.iris.targets.RenderTargets;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.function.Supplier;

/**
 * Hooks ALLVR's depth-texture samplers (vxDepthTexOpaque/vxDepthTexTrans) into
 * iris's per-program sampler resolution, right after iris registers its own
 * render-target samplers. Apply-time gated off while voxy is installed.
 */
@Mixin(value = IrisSamplers.class, remap = false)
public abstract class AllvrIrisSamplersMixin {

    @Inject(method = "addRenderTargetSamplers", at = @At("TAIL"), remap = false)
    private static void allvr$injectDepthSamplers(SamplerHolder samplers, Supplier<?> flipped,
                                                  RenderTargets renderTargets, boolean isFullscreenPass,
                                                  WorldRenderingPipeline pipeline, CallbackInfo ci) {
        if (ClientConfig.allvrIrisIntegration) {
            AllvrVoxySamplers.addSamplers(samplers, pipeline);
        }
    }
}
