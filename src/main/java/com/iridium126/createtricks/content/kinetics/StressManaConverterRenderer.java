package com.iridium126.createtricks.content.kinetics;

import com.iridium126.createtricks.CreateTricksPartialModels;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.simibubi.create.content.kinetics.base.KineticBlockEntityRenderer;

import dev.engine_room.flywheel.api.visualization.VisualizationManager;
import net.createmod.catnip.render.CachedBuffers;
import net.createmod.catnip.render.SuperByteBuffer;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.blockentity.BlockEntityRendererProvider;

public class StressManaConverterRenderer extends KineticBlockEntityRenderer<StressManaConverterBlockEntity> {

	public StressManaConverterRenderer(BlockEntityRendererProvider.Context context) {
		super(context);
	}

	@Override
	protected void renderSafe(StressManaConverterBlockEntity be, float partialTicks, PoseStack ms,
			MultiBufferSource buffer, int light, int overlay) {
		if (VisualizationManager.supportsVisualization(be.getLevel()))
			return;

		VertexConsumer vb = buffer.getBuffer(RenderType.solid());
		SuperByteBuffer inner = CachedBuffers.partial(CreateTricksPartialModels.STRESS_MANA_CONVERTER_INNER,
				be.getBlockState());

		renderRotatingBuffer(be, inner, ms, vb, light);
	}
}
