package com.iridium126.createmanaindustry.mixin.allvriris;

import com.iridium126.createmanaindustry.client.dimension.iris.AllvrIrisPipelineCapture;
import net.irisshaders.iris.Iris;
import net.irisshaders.iris.pipeline.WorldRenderingPipeline;
import net.irisshaders.iris.shaderpack.materialmap.NamespacedId;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Captures which dimension an iris pipeline is being created for.
 * {@code Iris.createPipeline} is the single pipeline factory (wired into
 * {@code PipelineManager} as its {@code pipelineFactory}) and runs
 * synchronously on the calling thread, so a threadlocal set at HEAD survives
 * through the whole pipeline construction — {@code AllvrIrisPipelineMixin}
 * gates the allay-dimension data build on it. Pack-load-time work (ProgramSet
 * construction, source preprocessing) happens BEFORE any pipeline exists on
 * iris 1.8.14 and must not gate on this state. The state itself lives in
 * {@link AllvrIrisPipelineCapture}: mixin classes must keep every non-injector
 * member private, and state merged into the target would be unreachable from
 * the other mixins.
 */
@Mixin(value = Iris.class, remap = false)
public abstract class AllvrIrisCreatePipelineMixin {

    @Inject(method = "createPipeline", at = @At("HEAD"), remap = false)
    private static void allvr$captureDimension(NamespacedId dimensionId,
                                               CallbackInfoReturnable<WorldRenderingPipeline> cir) {
        AllvrIrisPipelineCapture.begin(dimensionId.getNamespace() + ":" + dimensionId.getName());
    }

    @Inject(method = "createPipeline", at = @At("RETURN"), remap = false)
    private static void allvr$clearDimension(NamespacedId dimensionId,
                                             CallbackInfoReturnable<WorldRenderingPipeline> cir) {
        AllvrIrisPipelineCapture.end();
    }
}
