package com.iridium126.createmanaindustry.mixin.allvriris.accessor;

import net.irisshaders.iris.pipeline.IrisRenderingPipeline;
import net.irisshaders.iris.shadows.ShadowRenderTargets;
import net.irisshaders.iris.targets.RenderTargets;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/**
 * Exposes iris's per-pipeline render-target pool so the pack's voxy.json
 * colortex ids can be resolved to GL texture names (main/alt flip aware), and
 * the shadow target pool for the shadow-pass draw.
 */
@Mixin(value = IrisRenderingPipeline.class, remap = false)
public interface AllvrIrisRenderingPipelineAccessor {

    @Accessor
    RenderTargets getRenderTargets();

    @Accessor
    ShadowRenderTargets getShadowRenderTargets();
}
