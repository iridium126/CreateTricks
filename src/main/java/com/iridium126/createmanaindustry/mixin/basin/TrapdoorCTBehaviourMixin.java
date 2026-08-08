package com.iridium126.createmanaindustry.mixin.basin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import com.iridium126.createmanaindustry.content.kinetics.depositionlid.DepositionLidHelper;
import com.simibubi.create.content.decoration.TrapdoorCTBehaviour;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.BlockAndTintGetter;
import net.minecraft.world.level.block.state.BlockState;

/**
 * Makes Create's {@link TrapdoorCTBehaviour} treat the deposition lid as a
 * connected neighbour, so a {@code create:framed_glass_trapdoor} adjacent to a
 * lid renders seam-free on its own side too. Without this, only the lid's side
 * would merge (via {@code DepositionLidCTBehaviour}) and the trapdoor's face
 * would still show its isolated frame border.
 * <p>
 * Client-only: the behaviour is registered via
 * {@code CreateRegistrate.connectedTextures}, which runs under
 * {@code executeOnClientOnly}, so {@link TrapdoorCTBehaviour} never loads on a
 * dedicated server.
 */
@Mixin(value = TrapdoorCTBehaviour.class, remap = false)
public class TrapdoorCTBehaviourMixin {

    @Inject(method = "connectsTo(Lnet/minecraft/world/level/block/state/BlockState;Lnet/minecraft/world/level/block/state/BlockState;Lnet/minecraft/world/level/BlockAndTintGetter;Lnet/minecraft/core/BlockPos;Lnet/minecraft/core/BlockPos;Lnet/minecraft/core/Direction;Lnet/minecraft/core/Direction;Lnet/minecraft/core/Direction;)Z",
            at = @At("HEAD"), cancellable = true)
    private void createmanaindustry$acceptLid(BlockState state, BlockState other, BlockAndTintGetter reader,
            BlockPos pos, BlockPos otherPos, Direction face, Direction primaryOffset, Direction secondaryOffset,
            CallbackInfoReturnable<Boolean> cir) {
        if (DepositionLidHelper.isDepositionLid(other)) {
            cir.setReturnValue(DepositionLidHelper.isGlassConnected(state, other,
                    primaryOffset == null ? secondaryOffset : primaryOffset));
        }
    }
}
