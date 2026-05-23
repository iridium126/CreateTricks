package com.iridium126.createtricks.content.kinetics;

import com.simibubi.create.content.kinetics.base.KineticBlockEntityRenderer;

import net.minecraft.client.renderer.blockentity.BlockEntityRendererProvider;
import net.minecraft.core.Direction.Axis;
import net.minecraft.world.level.block.state.BlockState;

public class StressManaConverterRenderer extends KineticBlockEntityRenderer<StressManaConverterBlockEntity> {

	public StressManaConverterRenderer(BlockEntityRendererProvider.Context context) {
		super(context);
	}

	@Override
	protected BlockState getRenderedBlockState(StressManaConverterBlockEntity be) {
		return shaft(Axis.Y);
	}
}
