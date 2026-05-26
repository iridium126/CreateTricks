package com.iridium126.createtricks.content.kinetics;

import com.iridium126.createtricks.CreateTricksPartialModels;
import com.simibubi.create.content.kinetics.base.KineticBlockEntityRenderer;

import net.createmod.catnip.render.CachedBuffers;
import net.createmod.catnip.render.SuperByteBuffer;
import net.minecraft.client.renderer.blockentity.BlockEntityRendererProvider;
import net.minecraft.world.level.block.state.BlockState;

public class StressManaConverterRenderer extends KineticBlockEntityRenderer<StressManaConverterBlockEntity> {

	public StressManaConverterRenderer(BlockEntityRendererProvider.Context context) {
		super(context);
	}

	@Override
	protected SuperByteBuffer getRotatedModel(StressManaConverterBlockEntity be, BlockState state) {
		return CachedBuffers.partialFacing(CreateTricksPartialModels.STRESS_MANA_CONVERTER_INNER, state);
	}
}
