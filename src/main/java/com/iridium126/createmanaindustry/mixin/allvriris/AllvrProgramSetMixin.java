package com.iridium126.createmanaindustry.mixin.allvriris;

import java.util.function.Function;

import com.iridium126.createmanaindustry.client.dimension.iris.AllvrIrisPipelineCapture;
import com.iridium126.createmanaindustry.client.dimension.iris.AllvrVoxyPatch;
import com.iridium126.createmanaindustry.client.dimension.iris.IGetAllvrPatchData;
import com.iridium126.createmanaindustry.config.ClientConfig;
import net.irisshaders.iris.shaderpack.ShaderPack;
import net.irisshaders.iris.shaderpack.include.AbsolutePackPath;
import net.irisshaders.iris.shaderpack.programs.ProgramSet;
import net.irisshaders.iris.shaderpack.properties.ShaderProperties;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Parses the pack's voxy adaptation ({@code voxy.json} + aux patch files) when
 * each per-dimension ProgramSet is built, storing it under ALLVR's own duck
 * interface — deliberately not voxy's {@code IGetVoxyPatchData}, which a
 * co-installed voxy implements (same-name duck methods on one target would be
 * an apply-time hard conflict). Parse failures are contained here and yield a
 * null patch; pack loading is never disturbed.
 * <p>
 * The parse is dimension-gated to the allay dimension's pipeline build: other
 * dimensions' ProgramSets must never see the patch (and their pipelines must
 * not publish draw targets into {@code AllvrIrisDataHolder}).
 */
@Mixin(value = ProgramSet.class, remap = false)
public class AllvrProgramSetMixin implements IGetAllvrPatchData {

    @Unique
    private AllvrVoxyPatch allvr$patch;

    @Inject(method = "<init>", at = @At(value = "INVOKE",
        target = "Lnet/irisshaders/iris/shaderpack/programs/ProgramSet;locateDirectives()V"), remap = false)
    private void allvr$parsePatch(AbsolutePackPath directory, Function<AbsolutePackPath, String> sourceProvider,
                                  ShaderProperties shaderProperties, ShaderPack pack, CallbackInfo ci) {
        if (ClientConfig.allvrIrisIntegration && AllvrIrisPipelineCapture.isBuildingAllayPipeline()) {
            this.allvr$patch = AllvrVoxyPatch.makePatch(pack, directory, sourceProvider);
        }
    }

    @Override
    public AllvrVoxyPatch allvr$getPatchData() {
        return this.allvr$patch;
    }
}
