package com.iridium126.createmanaindustry.client.dimension.iris;

import net.irisshaders.iris.pipeline.IrisRenderingPipeline;

/**
 * Duck interface injected onto iris's {@link IrisRenderingPipeline} by
 * {@code AllvrIrisPipelineMixin} — ALLVR's resolved pipeline data (draw
 * targets, uniform layout, samplers) for the pack this pipeline was built for.
 * Separately named from voxy's {@code IGetIrisVoxyPipelineData} for the same
 * coexistence reason as {@link IGetAllvrPatchData}.
 */
public interface IGetAllvrPipelineData {

    AllvrIrisPipelineData allvr$getPipelineData();
}
