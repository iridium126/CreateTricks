package com.iridium126.createtricks.content.kinetics;

import com.iridium126.createtricks.CreateTricksBlockEntityTypes;
import com.simibubi.create.content.kinetics.base.DirectionalKineticBlock;
import com.simibubi.create.content.kinetics.simpleRelays.ICogWheel;
import com.simibubi.create.foundation.block.IBE;

import net.createmod.catnip.math.VoxelShaper;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.Direction.Axis;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.LevelReader;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.pathfinder.PathComputationType;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.minecraft.world.phys.shapes.Shapes;
import net.minecraft.world.phys.shapes.VoxelShape;

public class StressManaConverterBlock extends DirectionalKineticBlock implements IBE<StressManaConverterBlockEntity>, ICogWheel {

	private static final VoxelShaper SHAPE = VoxelShaper.forAxis(
			Shapes.or(
					Block.box(0, 0, 0, 16, 6, 16),
					Block.box(2, 6, 2, 14, 15, 14),
					Block.box(3, 15, 3, 13, 16, 13)),
			Axis.Y);

	public StressManaConverterBlock(Properties properties) {
		super(properties);
	}

	@Override
	public VoxelShape getShape(BlockState state, BlockGetter level, BlockPos pos, CollisionContext context) {
		return SHAPE.get(state.getValue(FACING));
	}

	@Override
	public boolean hasShaftTowards(LevelReader level, BlockPos pos, BlockState state, Direction face) {
		return face == state.getValue(FACING).getOpposite();
	}

	@Override
	public Axis getRotationAxis(BlockState state) {
		return state.getValue(FACING).getAxis();
	}

	public static BlockPos getManaOutputPos(BlockState state, BlockPos pos) {
		return pos.relative(state.getValue(FACING));
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
