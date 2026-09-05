package com.iridium126.createmanaindustry.client.dimension.iris;

import java.util.function.Supplier;

import com.iridium126.createmanaindustry.CreateManaIndustry;
import net.irisshaders.iris.gl.sampler.GlSampler;
import net.irisshaders.iris.gl.sampler.SamplerHolder;
import net.irisshaders.iris.gl.texture.TextureType;
import net.irisshaders.iris.pipeline.WorldRenderingPipeline;

/**
 * Registers the {@code vxDepthTexOpaque} / {@code vxDepthTexTrans} samplers
 * packs reference in their voxy branches — bound to ALLVR's own terrain FBO
 * depth once the draw-mounting slice creates it (0/unbound before that, which
 * packs already handle as "no LOD depth yet").
 */
public final class AllvrVoxySamplers {

    private AllvrVoxySamplers() {}

    public static void addSamplers(SamplerHolder samplers, WorldRenderingPipeline pipeline) {
        if (!(pipeline instanceof IGetAllvrPipelineData holder)) {
            return;
        }
        var data = holder.allvr$getPipelineData();
        if (data == null) {
            return; // pack ships no voxy adaptation — nothing to expose
        }
        samplers.addDynamicSampler(TextureType.TEXTURE_2D, data.getOpaqueDepthTexture(),
            GlSampler.MIPPED_NEAREST, "vxDepthTexOpaque");
        samplers.addDynamicSampler(TextureType.TEXTURE_2D, data.getTranslucentDepthTexture(),
            GlSampler.MIPPED_NEAREST, "vxDepthTexTrans");
    }
}
