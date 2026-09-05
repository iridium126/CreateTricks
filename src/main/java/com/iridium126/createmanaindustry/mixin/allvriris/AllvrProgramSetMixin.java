package com.iridium126.createmanaindustry.mixin.allvriris;

import java.util.function.Function;

import com.iridium126.createmanaindustry.client.dimension.iris.AllvrVoxyPatch;
import com.iridium126.createmanaindustry.client.dimension.iris.IGetAllvrPatchData;
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
 * each ProgramSet is built, storing it under ALLVR's own duck interface —
 * deliberately not voxy's {@code IGetVoxyPatchData}, which a co-installed voxy
 * implements (same-name duck methods on one target would be an apply-time hard
 * conflict). Parse failures are contained here and yield a null patch; pack
 * loading is never disturbed.
 * <p>
 * Dimension gating lives one level up ({@code AllvrIrisPipelineMixin} gates on
 * the {@code Iris.createPipeline} threadlocal — verified firing for the allay
 * dimension on iris 1.8.14). The PARSE itself must NOT be dimension-gated:
 * iris 1.8.14 constructs ProgramSets eagerly at pack load (before any
 * pipeline exists), and a custom dimension without a
 * {@code dimension.properties} entry shares the world0 ProgramSet with the
 * overworld — a createPipeline-time gate here silently never fired (G2 smoke,
 * 2026-09-05). The patch is a pack/directory property, so parsing it for every
 * ProgramSet is correct and cheap; only the allay-dimension PIPELINE ever
 * consumes it.
 */
@Mixin(value = ProgramSet.class, remap = false)
public class AllvrProgramSetMixin implements IGetAllvrPatchData {

    @Unique
    private AllvrVoxyPatch allvr$patch;

    @Inject(method = "<init>", at = @At(value = "INVOKE",
        target = "Lnet/irisshaders/iris/shaderpack/programs/ProgramSet;locateDirectives()V"), remap = false)
    private void allvr$parsePatch(AbsolutePackPath directory, Function<AbsolutePackPath, String> sourceProvider,
                                  ShaderProperties shaderProperties, ShaderPack pack, CallbackInfo ci) {
        // deliberately NOT gated on ClientConfig.allvrIrisIntegration: the BASE
        // ProgramSet is constructed at first pack load, before client configs
        // load — a config gate here left the base (which custom dimensions
        // fall back to) with a frozen null patch for the whole session (G2
        // smoke ②). The patch parse is side-effect free; the config gates at
        // the pipeline-data build, which is always re-evaluated per pipeline.
        this.allvr$patch = AllvrVoxyPatch.makePatch(pack, directory, sourceProvider);
    }

    @Override
    public AllvrVoxyPatch allvr$getPatchData() {
        return this.allvr$patch;
    }
}
