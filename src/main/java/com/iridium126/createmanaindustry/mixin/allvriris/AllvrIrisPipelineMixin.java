package com.iridium126.createmanaindustry.mixin.allvriris;

import com.iridium126.createmanaindustry.client.dimension.iris.AllvrIrisPipelineData;
import com.iridium126.createmanaindustry.client.dimension.iris.AllvrVoxyPatch;
import com.iridium126.createmanaindustry.client.dimension.iris.IGetAllvrPatchData;
import com.iridium126.createmanaindustry.client.dimension.iris.IGetAllvrPipelineData;
import com.iridium126.createmanaindustry.mixin.allvriris.accessor.AllvrCustomUniformsAccessor;
import net.irisshaders.iris.gl.buffer.ShaderStorageBufferHolder;
import net.irisshaders.iris.pipeline.IrisRenderingPipeline;
import net.irisshaders.iris.shaderpack.programs.ProgramSet;
import net.irisshaders.iris.uniforms.custom.CustomUniforms;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Resolves ALLVR's pipeline data (draw targets, uniform UBO layout, samplers,
 * SSBOs) against each iris pipeline when it is constructed, mirroring voxy's
 * two ctor injection points (they exist identically in iris 1.7.3). The duck
 * interfaces and fields are ALLVR-named so a co-installed voxy port — which
 * injects at the same points with its own names — applies alongside without
 * conflict; both data objects then coexist and each renderer draws from its own.
 */
@Mixin(value = IrisRenderingPipeline.class, remap = false)
public class AllvrIrisPipelineMixin implements IGetAllvrPatchData, IGetAllvrPipelineData {

    @Shadow
    @Final
    private CustomUniforms customUniforms;

    @Shadow
    private ShaderStorageBufferHolder shaderStorageBufferHolder;

    @Unique
    private AllvrVoxyPatch allvr$patch;

    @Unique
    private AllvrIrisPipelineData allvr$pipeline;

    @Inject(method = "<init>", at = @At(value = "INVOKE",
        target = "Lnet/irisshaders/iris/pipeline/transform/ShaderPrinter;resetPrintState()V"), remap = false)
    private void allvr$capturePatch(ProgramSet programSet, CallbackInfo ci) {
        this.allvr$patch = ((IGetAllvrPatchData) programSet).allvr$getPatchData();
    }

    @Inject(method = "<init>", at = @At(value = "INVOKE",
        target = "Lnet/irisshaders/iris/pipeline/IrisRenderingPipeline;createSetupComputes([Lnet/irisshaders/iris/shaderpack/programs/ComputeSource;Lnet/irisshaders/iris/shaderpack/programs/ProgramSet;Lnet/irisshaders/iris/shaderpack/texture/TextureStage;)[Lnet/irisshaders/iris/gl/program/ComputeProgram;"), remap = false)
    private void allvr$buildPipeline(ProgramSet programSet, CallbackInfo ci) {
        if (this.allvr$patch != null) {
            this.allvr$pipeline = AllvrIrisPipelineData.buildPipeline(
                (IrisRenderingPipeline) (Object) this, this.allvr$patch,
                this.customUniforms, this.shaderStorageBufferHolder);
        }
    }

    @Override
    public AllvrVoxyPatch allvr$getPatchData() {
        return this.allvr$patch;
    }

    @Override
    public AllvrIrisPipelineData allvr$getPipelineData() {
        return this.allvr$pipeline;
    }
}
