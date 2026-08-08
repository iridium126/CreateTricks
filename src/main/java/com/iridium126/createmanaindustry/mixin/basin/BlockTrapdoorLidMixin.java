package com.iridium126.createmanaindustry.mixin.basin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import com.iridium126.createmanaindustry.content.kinetics.depositionlid.DepositionLidBlock;
import com.iridium126.createmanaindustry.content.kinetics.depositionlid.DepositionLidHelper;

import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraft.world.level.block.state.BlockState;

/**
 * Auto-converts a {@code create:framed_glass_trapdoor} into the mod's own
 * {@link DepositionLidBlock}-carrying block the moment it sits, bottom half, on
 * top of a basin — so the lid operator (and with it {@code vapor_deposition}
 * recipes) activates without the player placing a new machine.
 * <p>
 * The {@code onPlace} hook is injected into {@link BlockBehaviour} because that
 * is where it is <b>declared</b> in 1.21.1 — {@code TrainTrapdoorBlock} inherits
 * it without overriding, and Mixin can only inject into the declaring class.
 * It fires on placement and on any property change (e.g. closing the trapdoor),
 * so the common conversion cases are instant. Blocks that override {@code onPlace}
 * skip this mixin entirely, and the {@code is(framed_glass_trapdoor)} guard
 * early-outs everything else.
 * <p>
 * The two {@code neighborChanged}-driven cases (basin placed under an existing
 * trapdoor, and basin removed reverting the lid) cannot be handled here —
 * {@code TrapDoorBlock} overrides {@code neighborChanged} without calling super,
 * so a {@code BlockBehaviour} injection there never fires. Those live in
 * {@code TrapDoorBlockLidMixin}.
 */
@Mixin(BlockBehaviour.class)
public class BlockTrapdoorLidMixin {

    @Inject(method = "onPlace(Lnet/minecraft/world/level/block/state/BlockState;Lnet/minecraft/world/level/Level;Lnet/minecraft/core/BlockPos;Lnet/minecraft/world/level/block/state/BlockState;Z)V",
            at = @At("HEAD"))
    private void createmanaindustry$convertOnPlace(BlockState state, Level level, BlockPos pos,
            BlockState oldState, boolean movedByPiston, CallbackInfo ci) {
        if (level.isClientSide)
            return;
        DepositionLidHelper.convertToLid(level, pos, state);
    }
}
