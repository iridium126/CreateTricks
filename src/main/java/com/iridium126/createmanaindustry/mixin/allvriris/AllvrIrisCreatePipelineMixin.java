package com.iridium126.createmanaindustry.mixin.allvriris;

import net.irisshaders.iris.Iris;
import net.irisshaders.iris.pipeline.WorldRenderingPipeline;
import net.irisshaders.iris.shaderpack.materialmap.NamespacedId;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Captures which dimension an iris pipeline is being created for.
 * {@code Iris.createPipeline} is the single pipeline factory (wired into
 * {@code PipelineManager} as its {@code pipelineFactory}) and runs
 * synchronously on the calling thread, so a threadlocal set at HEAD survives
 * through the whole pipeline construction — including every ProgramSet source
 * read that flows into the Jcpp preprocessing where ALLVR's per-dimension
 * {@code VOXY} define is injected ({@link AllvrShaderPackMixin}).
 */
@Mixin(value = Iris.class, remap = false)
public abstract class AllvrIrisCreatePipelineMixin {

    /** iris-side id of the allay dimension — iris classes stay out of common code. */
    @Unique
    private static final NamespacedId ALLVR_DIM_ID = new NamespacedId("createmanaindustry", "allay_dimension");

    @Unique
    private static final ThreadLocal<NamespacedId> allvr$creatingDimension = new ThreadLocal<>();

    /** True while an iris pipeline is being built FOR the allay dimension. */
    public static boolean allvr$isBuildingAllayPipeline() {
        return ALLVR_DIM_ID.equals(allvr$creatingDimension.get());
    }

    @Inject(method = "createPipeline", at = @At("HEAD"), remap = false)
    private static void allvr$captureDimension(NamespacedId dimensionId,
                                               CallbackInfoReturnable<WorldRenderingPipeline> cir) {
        allvr$creatingDimension.set(dimensionId);
    }

    @Inject(method = "createPipeline", at = @At("RETURN"), remap = false)
    private static void allvr$clearDimension(NamespacedId dimensionId,
                                             CallbackInfoReturnable<WorldRenderingPipeline> cir) {
        allvr$creatingDimension.remove();
    }
}
