package com.iridium126.createmanaindustry.mixin.basin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import com.iridium126.createmanaindustry.content.kinetics.depositionlid.DepositionLidHelper;

import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.TrapDoorBlock;
import net.minecraft.world.level.block.state.BlockState;

/**
 * Handles the two {@code neighborChanged}-driven trapdoor↔lid conversions that
 * {@code BlockTrapdoorLidMixin} cannot reach (vanilla's {@link TrapDoorBlock}
 * overrides {@code neighborChanged} without calling super, so a
 * {@code BlockBehaviour} injection never fires for trapdoors or the lid):
 * <ul>
 *   <li>a basin placed under an existing framed glass trapdoor → convert to lid;</li>
 *   <li>a basin removed from under the lid → revert to a plain framed glass trapdoor
 *       (dropping the lid's BE and progress).</li>
 * </ul>
 * Injected at {@code RETURN} so the vanilla body's redstone {@code setBlock}
 * (POWERED/OPEN) has already run; the handler reads the post-redstone state via
 * {@code level.getBlockState(pos)} instead of the stale method parameter.
 */
@Mixin(TrapDoorBlock.class)
public class TrapDoorBlockLidMixin {

    @Inject(method = "neighborChanged(Lnet/minecraft/world/level/block/state/BlockState;Lnet/minecraft/world/level/Level;Lnet/minecraft/core/BlockPos;Lnet/minecraft/world/level/block/Block;Lnet/minecraft/core/BlockPos;Z)V",
            at = @At("RETURN"))
    private void createmanaindustry$convertOnNeighbor(BlockState state, Level level, BlockPos pos,
            Block neighborBlock, BlockPos fromPos, boolean isMoving, CallbackInfo ci) {
        if (level.isClientSide)
            return;
        // Only react to the block directly below (the basin appearing or leaving).
        // Inline the below-check to avoid allocating a BlockPos on the hot path.
        if (pos.getX() != fromPos.getX() || pos.getZ() != fromPos.getZ()
                || pos.getY() != fromPos.getY() + 1)
            return;

        BlockState current = level.getBlockState(pos);
        if (DepositionLidHelper.hasBasinBelow(level, pos)) {
            DepositionLidHelper.convertToLid(level, pos, current);
        } else if (DepositionLidHelper.isDepositionLid(current)) {
            DepositionLidHelper.revertToTrapdoor(level, pos, current);
        }
    }
}
