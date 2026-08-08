package com.iridium126.createmanaindustry.content.kinetics.depositionlid;

import com.simibubi.create.content.decoration.TrainTrapdoorBlock;
import com.simibubi.create.content.decoration.TrapdoorCTBehaviour;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.BlockAndTintGetter;
import net.minecraft.world.level.block.state.BlockState;

/**
 * Connected-texture behaviour for the deposition lid. Mirrors Create's
 * {@link TrapdoorCTBehaviour} (same {@code FRAMED_GLASS} sprite shift) but
 * relaxes the block-identity check so the lid's glass merges with both other
 * lids and {@code create:framed_glass_trapdoor}s — the two halves of the same
 * converted block. The metal {@code create:train_trapdoor} stays unconnected.
 * <p>
 * Uses {@link DepositionLidHelper#isGlassConnected} for the cross-block pair —
 * {@link TrainTrapdoorBlock#isConnected} compares the two states by reference in
 * its open-state branch, which fails across two different blocks.
 */
public class DepositionLidCTBehaviour extends TrapdoorCTBehaviour {

    @Override
    public boolean connectsTo(BlockState state, BlockState other, BlockAndTintGetter reader, BlockPos pos,
            BlockPos otherPos, Direction face, Direction primaryOffset, Direction secondaryOffset) {
        if (!DepositionLidHelper.isDepositionLid(other) && !DepositionLidHelper.isFramedGlassTrapdoor(other))
            return false;
        return DepositionLidHelper.isGlassConnected(state, other,
                primaryOffset == null ? secondaryOffset : primaryOffset);
    }
}
