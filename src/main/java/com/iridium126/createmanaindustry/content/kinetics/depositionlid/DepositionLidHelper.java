package com.iridium126.createmanaindustry.content.kinetics.depositionlid;

import com.iridium126.createmanaindustry.CMIBlocks;
import com.simibubi.create.AllBlocks;
import com.simibubi.create.content.processing.basin.BasinBlockEntity;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.Direction.Axis;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.TrapDoorBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.Half;
import net.minecraft.world.level.block.state.properties.Property;

/**
 * Shared conversion logic for the deposition lid, used by both the
 * {@code BlockBehaviour.onPlace} and {@code TrapDoorBlock.neighborChanged}
 * mixins so the trapdoor↔lid swap (and the property-copy helper) lives in one
 * place.
 */
public final class DepositionLidHelper {

    private DepositionLidHelper() {}

    /** True if {@code state} is the specific framed glass trapdoor (not the metal train trapdoor, not the lid). */
    public static boolean isFramedGlassTrapdoor(BlockState state) {
        return state.is(AllBlocks.FRAMED_GLASS_TRAPDOOR.get());
    }

    /** True if {@code state} is our deposition lid. */
    public static boolean isDepositionLid(BlockState state) {
        return state.getBlock() == CMIBlocks.DEPOSITION_LID.get();
    }

    /** True if the block directly below {@code pos} is a basin. */
    public static boolean hasBasinBelow(Level level, BlockPos pos) {
        return level.getBlockEntity(pos.below()) instanceof BasinBlockEntity;
    }

    /**
     * Swaps a framed-glass trapdoor (bottom half, on a basin) for the deposition
     * lid, preserving every block-state property. No-op unless {@code state} is
     * a bottom-half framed glass trapdoor sitting on a basin — so converting an
     * already-converted lid is harmless.
     */
    public static void convertToLid(Level level, BlockPos pos, BlockState state) {
        if (!isFramedGlassTrapdoor(state))
            return;
        if (state.getValue(TrapDoorBlock.HALF) != Half.BOTTOM)
            return;
        if (!hasBasinBelow(level, pos))
            return;
        level.setBlock(pos, copyProperties(state, CMIBlocks.DEPOSITION_LID.getDefaultState()), 2);
    }

    /**
     * Swaps the deposition lid back to a plain framed glass trapdoor (which drops
     * the lid's BE and its progress). No-op unless {@code lidState} is the lid.
     */
    public static void revertToTrapdoor(Level level, BlockPos pos, BlockState lidState) {
        if (!isDepositionLid(lidState))
            return;
        level.setBlock(pos, copyProperties(lidState, AllBlocks.FRAMED_GLASS_TRAPDOOR.get().defaultBlockState()), 2);
    }

    /**
     * Value-based twin of {@link TrainTrapdoorBlock#isConnected} for the
     * cross-block lid↔trapdoor pair.
     * <p>
     * Create's {@code isConnected} compares the two states in its open-state
     * branch via {@code state.setValue(HALF, TOP) != other.setValue(HALF, TOP)} —
     * a {@link BlockState} <em>reference</em> comparison. That is true only when
     * both states belong to the <b>same</b> block (their {@code setValue} results
     * are the same cached instance); two different blocks (a lid and a trapdoor)
     * always report "not connected" when open, even though the geometry says they
     * should merge. This method mirrors the same rules using value comparisons.
     */
    public static boolean isGlassConnected(BlockState state, BlockState other, Direction dir) {
        if (state.getValue(TrapDoorBlock.OPEN) != other.getValue(TrapDoorBlock.OPEN))
            return false;
        boolean open = state.getValue(TrapDoorBlock.OPEN);
        Half half = state.getValue(TrapDoorBlock.HALF);
        Direction facing = state.getValue(TrapDoorBlock.FACING);

        if (!open && half == other.getValue(TrapDoorBlock.HALF))
            return dir.getAxis() != Axis.Y;
        if (!open && half != other.getValue(TrapDoorBlock.HALF) && dir.getAxis() == Axis.Y)
            return true;

        if (open && facing.getOpposite() == other.getValue(TrapDoorBlock.FACING)
                && dir.getAxis() == facing.getAxis())
            return true;
        if (open) {
            // isConnected's HALF-forced state comparison is reference-based; compare
            // the properties directly instead. For open trapdoors HALF is irrelevant.
            if (facing != other.getValue(TrapDoorBlock.FACING))
                return false;
            return dir.getAxis() != facing.getAxis();
        }
        // closed, differing halves, non-vertical direction → not connected
        return false;
    }

    /**
     * Copies every property shared between {@code source} and {@code target}.
     * The lid and the framed glass trapdoor are both {@link TrapDoorBlock}
     * subclasses with identical property sets, so this preserves FACING, HALF,
     * OPEN, POWERED and WATERLOGGED across the conversion.
     */
    @SuppressWarnings({ "unchecked", "rawtypes" })
    public static BlockState copyProperties(BlockState source, BlockState target) {
        for (Property<?> property : source.getProperties()) {
            if (!target.hasProperty(property))
                continue;
            target = target.setValue((Property) property, source.getValue(property));
        }
        return target;
    }
}
