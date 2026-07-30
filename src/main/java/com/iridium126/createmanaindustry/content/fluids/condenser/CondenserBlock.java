package com.iridium126.createmanaindustry.content.fluids.condenser;

import com.iridium126.createmanaindustry.CMIBlockEntityTypes;
import com.mojang.serialization.MapCodec;
import com.simibubi.create.content.equipment.wrench.IWrenchable;
import com.simibubi.create.foundation.block.IBE;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Rotation;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.StateDefinition;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.block.state.properties.EnumProperty;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.minecraft.world.phys.shapes.VoxelShape;

import java.util.Map;

public class CondenserBlock extends Block implements IBE<CondenserBlockEntity>, IWrenchable {

    public static final EnumProperty<Direction.Axis> AXIS = BlockStateProperties.AXIS;
    public static final MapCodec<CondenserBlock> CODEC = simpleCodec(CondenserBlock::new);

    private static final VoxelShape SHAPE_Y = Block.box(3, 0, 3, 13, 16, 13);
    private static final VoxelShape SHAPE_X = Block.box(0, 3, 3, 16, 13, 13);
    private static final VoxelShape SHAPE_Z = Block.box(3, 3, 0, 13, 13, 16);
    private static final Map<Direction.Axis, VoxelShape> SHAPES = Map.of(
            Direction.Axis.Y, SHAPE_Y,
            Direction.Axis.X, SHAPE_X,
            Direction.Axis.Z, SHAPE_Z);

    public CondenserBlock(Properties properties) {
        super(properties);
        this.registerDefaultState(this.stateDefinition.any().setValue(AXIS, Direction.Axis.Y));
    }

    @Override
    public VoxelShape getShape(BlockState state, BlockGetter level, BlockPos pos, CollisionContext context) {
        return SHAPES.getOrDefault(state.getValue(AXIS), SHAPE_Y);
    }

    @Override
    protected MapCodec<? extends Block> codec() {
        return CODEC;
    }

    @Override
    protected void createBlockStateDefinition(StateDefinition.Builder<Block, BlockState> builder) {
        builder.add(AXIS);
    }

    @Override
    public BlockState getStateForPlacement(BlockPlaceContext context) {
        return this.defaultBlockState().setValue(AXIS, context.getClickedFace().getAxis());
    }

    @Override
    protected BlockState rotate(BlockState state, Rotation rot) {
        return switch (rot) {
            case CLOCKWISE_90, COUNTERCLOCKWISE_90 -> switch (state.getValue(AXIS)) {
                case X -> state.setValue(AXIS, Direction.Axis.Z);
                case Z -> state.setValue(AXIS, Direction.Axis.X);
                default -> state;
            };
            default -> state;
        };
    }

    @Override
    public Class<CondenserBlockEntity> getBlockEntityClass() {
        return CondenserBlockEntity.class;
    }

    @Override
    public BlockEntityType<? extends CondenserBlockEntity> getBlockEntityType() {
        return CMIBlockEntityTypes.CONDENSER.get();
    }
}
