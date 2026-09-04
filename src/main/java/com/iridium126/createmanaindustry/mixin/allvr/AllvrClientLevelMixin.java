package com.iridium126.createmanaindustry.mixin.allvr;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import com.iridium126.createmanaindustry.client.dimension.AllvrClientCubeCache;
import com.iridium126.createmanaindustry.dimension.AllvrDimensions;
import com.iridium126.createmanaindustry.dimension.cube.AllvrCubeMap;

import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.material.FluidState;

/**
 * Client-side counterpart of {@link AllvrLevelMixin} (this one lives in the
 * mixin config's client section, so a dedicated server never loads it):
 * inside the allay dimension, ClientLevel block reads are served from the
 * streamed cube cache ({@link AllvrClientCubeCache}, misses = void air, the
 * client never generates). This powers client-side collision — a player
 * teleported onto an island stands on it instead of falling through the
 * void — plus entity physics, block outlines and ray tracing.
 */
@Mixin(Level.class)
public abstract class AllvrClientLevelMixin {

    private static boolean allvr$isAllayClient(Level self) {
        return self.isClientSide && self.dimension() == AllvrDimensions.ALLAY_LEVEL;
    }

    @Inject(method = "getBlockState", at = @At("HEAD"), cancellable = true)
    private void allvr$clientGetBlockState(BlockPos pos, CallbackInfoReturnable<BlockState> cir) {
        Level self = (Level) (Object) this;
        if (allvr$isAllayClient(self)) {
            cir.setReturnValue(AllvrClientCubeCache.getBlockState(pos));
        }
    }

    @Inject(method = "getFluidState", at = @At("HEAD"), cancellable = true)
    private void allvr$clientGetFluidState(BlockPos pos, CallbackInfoReturnable<FluidState> cir) {
        Level self = (Level) (Object) this;
        if (allvr$isAllayClient(self)) {
            cir.setReturnValue(AllvrClientCubeCache.getFluidState(pos));
        }
    }

    @Inject(method = "getBlockEntity", at = @At("HEAD"), cancellable = true)
    private void allvr$clientGetBlockEntity(BlockPos pos, CallbackInfoReturnable<BlockEntity> cir) {
        Level self = (Level) (Object) this;
        if (allvr$isAllayClient(self)) {
            cir.setReturnValue(AllvrClientCubeCache.getBlockEntity(pos));
        }
    }

    /**
     * Targets the 4-arg real implementation — every client write funnels
     * through it ({@code ClientLevel#setBlock} calls {@code super.setBlock} in
     * both its predicting and non-predicting branches; destroy/place
     * prediction and server confirmation packets alike). Without this,
     * prediction writes reach the empty-shell column chunk and
     * {@code LevelChunk#setBlockState} indexes its section array out of
     * bounds (the vanilla body's only guards sit behind the widened
     * {@code isOutsideBuildHeight}).
     */
    @Inject(method = "setBlock(Lnet/minecraft/core/BlockPos;Lnet/minecraft/world/level/block/state/BlockState;II)Z",
        at = @At("HEAD"), cancellable = true)
    private void allvr$clientSetBlock(BlockPos pos, BlockState state, int flags, int recursionLeft,
                                      CallbackInfoReturnable<Boolean> cir) {
        Level self = (Level) (Object) this;
        if (allvr$isAllayClient(self)) {
            cir.setReturnValue(AllvrClientCubeCache.setBlock(pos, state, flags, recursionLeft));
        }
    }
}
