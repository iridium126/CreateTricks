package com.iridium126.createtricks.content.kinetics;

import com.iridium126.createtricks.CreateTricksBlockEntityTypes;
import com.simibubi.create.AllShapes;
import com.simibubi.create.content.kinetics.base.KineticBlock;
import com.simibubi.create.foundation.block.IBE;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.Direction.Axis;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.LevelReader;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.pathfinder.PathComputationType;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.minecraft.world.phys.shapes.VoxelShape;

public class StressManaConverterBlock extends KineticBlock implements IBE<StressManaConverterBlockEntity> {

	public StressManaConverterBlock(Properties properties) {
		super(properties);
	}

	@Override
	public VoxelShape getShape(BlockState state, BlockGetter level, BlockPos pos, CollisionContext context) {
		return AllShapes.MILLSTONE;
	}

	@Override
	public boolean hasShaftTowards(LevelReader level, BlockPos pos, BlockState state, Direction face) {
		return face == Direction.DOWN;
	}

	@Override
	public Axis getRotationAxis(BlockState state) {
		return Axis.Y;
	}

	@Override
	public Class<StressManaConverterBlockEntity> getBlockEntityClass() {
		return StressManaConverterBlockEntity.class;
	}

	@Override
	public BlockEntityType<? extends StressManaConverterBlockEntity> getBlockEntityType() {
		return CreateTricksBlockEntityTypes.STRESS_MANA_CONVERTER.get();
	}

	@Override
	protected boolean isPathfindable(BlockState state, PathComputationType pathComputationType) {
		return false;
	}
}
