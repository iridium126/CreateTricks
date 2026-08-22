package com.iridium126.createmanaindustry.mixin.iris;

import com.google.common.collect.ImmutableSet;

import net.irisshaders.iris.pipeline.IrisRenderingPipeline;
import net.irisshaders.iris.targets.RenderTargets;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/**
 * Exposes iris's render targets and the after-translucent flip set so the mist
 * renderer can locate a colour buffer's GL texture without a draw hook.
 * Gated on iris by {@code CMIMixinPlugin} (the {@code .iris.} package).
 */
@Mixin(value = IrisRenderingPipeline.class, remap = false)
public interface IrisRenderingPipelineTargetsAccessor {

    // Method names carry a cmi$ prefix — IrisRenderingPipeline already declares
    // getFlippedAfterTranslucent()/getRenderTargets(), which would collide.

    @Accessor("flippedAfterTranslucent")
    ImmutableSet<Integer> cmi$getFlippedAfterTranslucent();

    @Accessor("renderTargets")
    RenderTargets cmi$getRenderTargets();
}