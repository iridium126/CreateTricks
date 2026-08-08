package com.iridium126.createmanaindustry.content.kinetics.depositionlid;

import com.iridium126.createmanaindustry.CMIBlockEntityTypes;
import com.simibubi.create.AllBlocks;
import com.simibubi.create.content.decoration.TrainTrapdoorBlock;
import com.simibubi.create.content.decoration.slidingDoor.SlidingDoorBlock;
import com.simibubi.create.foundation.block.IBE;

import net.minecraft.core.BlockPos;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.LevelReader;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockState;

/**
 * The deposition lid — a {@link TrainTrapdoorBlock} that is visually and
 * behaviourally identical to {@code create:framed_glass_trapdoor}, but carries a
 * {@link DepositionLidBlockEntity} so a sealed basin can run
 * {@code vapor_deposition} recipes.
 * <p>
 * The block has no item and is never placed directly: {@code TrainTrapdoorBlockMixin}
 * swaps a {@code create:framed_glass_trapdoor} for this block the moment it is
 * placed (or closed) on top of a basin, and swaps it back when the basin is
 * removed. The loot table and pick-block both resolve to the framed glass
 * trapdoor, so the conversion is invisible to the player.
 */
public class DepositionLidBlock extends TrainTrapdoorBlock implements IBE<DepositionLidBlockEntity> {

    public DepositionLidBlock(Properties properties) {
        super(SlidingDoorBlock.GLASS_SET_TYPE.get(), properties);
    }

    /** Creative pick-block returns a framed glass trapdoor (this block has no item). */
    @Override
    public ItemStack getCloneItemStack(LevelReader level, BlockPos pos, BlockState state) {
        return new ItemStack(AllBlocks.FRAMED_GLASS_TRAPDOOR.get());
    }

    @Override
    public Class<DepositionLidBlockEntity> getBlockEntityClass() {
        return DepositionLidBlockEntity.class;
    }

    @Override
    public BlockEntityType<? extends DepositionLidBlockEntity> getBlockEntityType() {
        return CMIBlockEntityTypes.DEPOSITION_LID.get();
    }
}
