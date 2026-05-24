package com.iridium126.createtricks.content.kinetics;

import com.iridium126.createtricks.CreateTricksBlockEntitys;
import com.simibubi.create.content.kinetics.base.RotatedPillarKineticBlock;
import com.simibubi.create.foundation.block.IBE;

import net.createmod.catnip.math.VoxelShaper;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.Direction.Axis;
import net.minecraft.core.Direction.AxisDirection;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.LevelReader;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.pathfinder.PathComputationType;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.minecraft.world.phys.shapes.Shapes;
import net.minecraft.world.phys.shapes.VoxelShape;

public class StressManaConverterBlock extends RotatedPillarKineticBlock implements IBE<StressManaConverterBlockEntity> {

	private static final VoxelShaper SHAPE = VoxelShaper.forAxis(
			Shapes.or(
					Block.box(0, 0, 0, 16, 6, 16),
					Block.box(2, 12, 2, 14, 15, 14),
					Block.box(3, 15, 3, 13, 16, 13)),
			Axis.Y);

	public StressManaConverterBlock(Properties properties) {
		super(properties);
	}

	@Override
	public VoxelShape getShape(BlockState state, BlockGetter level, BlockPos pos, CollisionContext context) {
		return SHAPE.get(state.getValue(AXIS));
	}

	@Override
	public boolean hasShaftTowards(LevelReader level, BlockPos pos, BlockState state, Direction face) {
		return face.getAxis() == getRotationAxis(state);
	}

	@Override
	public Axis getRotationAxis(BlockState state) {
		return state.getValue(AXIS);
	}

	public static Direction getStressInputFace(BlockState state) {
		return Direction.get(AxisDirection.NEGATIVE, state.getValue(AXIS));
	}

	public static Direction getManaOutputFace(BlockState state) {
		return Direction.get(AxisDirection.POSITIVE, state.getValue(AXIS));
	}

	public static BlockPos getManaOutputPos(BlockState state, BlockPos pos) {
		return pos.relative(getManaOutputFace(state));
	}

	@Override
	public Class<StressManaConverterBlockEntity> getBlockEntityClass() {
		return StressManaConverterBlockEntity.class;
	}

	@Override
	public BlockEntityType<? extends StressManaConverterBlockEntity> getBlockEntityType() {
		return CreateTricksBlockEntitys.STRESS_MANA_CONVERTER.get();
	}

	@Override
	protected boolean isPathfindable(BlockState state, PathComputationType pathComputationType) {
		return false;
	}
}
