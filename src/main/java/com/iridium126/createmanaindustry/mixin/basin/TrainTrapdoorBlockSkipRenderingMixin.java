package com.iridium126.createmanaindustry.mixin.basin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import com.iridium126.createmanaindustry.content.kinetics.depositionlid.DepositionLidHelper;
import com.simibubi.create.content.decoration.TrainTrapdoorBlock;

import net.minecraft.core.Direction;
import net.minecraft.world.level.block.state.BlockState;

/**
 * Makes the framed glass trapdoor and the deposition lid cull their shared thin
 * side faces, exactly like two adjacent framed glass trapdoors do. Create's
 * {@code TrainTrapdoorBlock.skipRendering} only skips the face when
 * {@code state.is(this) == other.is(this)} — true for two trapdoors, but false
 * for a trapdoor↔lid pair (different blocks), so their touching side faces stay
 * visible. This mixin treats the lid and the framed glass trapdoor as the same
 * material and lets {@link DepositionLidHelper#isGlassConnected} decide,
 * mirroring the same-block path.
 * <p>
 * The metal {@code create:train_trapdoor} is unaffected (it is neither the
 * framed glass trapdoor nor the lid).
 * <p>
 * Client-only: {@code skipRendering} is only consumed by {@code Block.shouldRenderFace}
 * (face culling), whose sole callers are client renderers — the method is never
 * invoked on a dedicated server.
 */
@Mixin(value = TrainTrapdoorBlock.class, remap = false)
public class TrainTrapdoorBlockSkipRenderingMixin {

    @Inject(method = "skipRendering(Lnet/minecraft/world/level/block/state/BlockState;Lnet/minecraft/world/level/block/state/BlockState;Lnet/minecraft/core/Direction;)Z",
            at = @At("HEAD"), cancellable = true)
    private void createmanaindustry$skipSharedGlassFaces(BlockState state, BlockState other, Direction direction,
            CallbackInfoReturnable<Boolean> cir) {
        boolean selfIsGlass = DepositionLidHelper.isFramedGlassTrapdoor(state)
                || DepositionLidHelper.isDepositionLid(state);
        boolean otherIsGlass = DepositionLidHelper.isFramedGlassTrapdoor(other)
                || DepositionLidHelper.isDepositionLid(other);
        if (selfIsGlass && otherIsGlass)
            cir.setReturnValue(DepositionLidHelper.isGlassConnected(state, other, direction));
    }
}
