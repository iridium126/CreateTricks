package com.iridium126.createtricks.content.kinetics;

import com.simibubi.create.foundation.data.SpecialBlockStateGen;
import com.tterrag.registrate.providers.DataGenContext;
import com.tterrag.registrate.providers.RegistrateBlockstateProvider;

import net.minecraft.core.Direction;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.neoforged.neoforge.client.model.generators.ModelFile;

public class StressManaConverterGenerator extends SpecialBlockStateGen {

	@Override
	protected int getXRotation(BlockState state) {
		return state.getValue(StressManaConverterBlock.FACING) == Direction.DOWN ? 180 : 0;
	}

	@Override
	protected int getYRotation(BlockState state) {
		return state.getValue(StressManaConverterBlock.FACING)
			.getAxis()
			.isVertical() ? 0 : horizontalAngle(state.getValue(StressManaConverterBlock.FACING));
	}

	@Override
	public <T extends Block> ModelFile getModel(DataGenContext<Block, T> ctx, RegistrateBlockstateProvider prov,
		BlockState state) {
		return state.getValue(StressManaConverterBlock.FACING)
			.getAxis()
			.isVertical() ? prov.models().getExistingFile(prov.modLoc("block/stress_mana_converter/block_vertical"))
				: prov.models().getExistingFile(prov.modLoc("block/stress_mana_converter/block"));
	}

}
