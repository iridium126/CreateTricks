package com.iridium126.createmanaindustry.mixin.fan;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import com.iridium126.createmanaindustry.content.burner.AllayBurnerBlock;
import com.simibubi.create.content.kinetics.fan.processing.AllFanProcessingTypes;

import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;

/**
 * Makes an {@code AllayBurnerBlock} a fan-blasting catalyst only while it is
 * {@code ALLAYHEATED}.
 * <p>
 * Create's {@code BlastingType.isValidAt} recognises the blaze burner via the
 * {@code create:fan_processing_catalysts/blasting} block tag and then gates on
 * {@code BlazeBurnerBlock.HEAT_LEVEL} — the Allay Burner has its own
 * {@code HEAT_LEVEL} property (NONE/IDLE/ALLAYHEATED), so a tag entry alone
 * would leave it a permanent catalyst. This injection short-circuits the check
 * for the Allay Burner, returning true only while it is actually heating. The
 * burner itself is in {@code create:fan_transparent}, so the air current passes
 * through it at every heat level.
 */
@Mixin(value = AllFanProcessingTypes.BlastingType.class, remap = false)
public abstract class FanBlastingCatalystMixin {

    @Inject(method = "isValidAt", at = @At("HEAD"), cancellable = true)
    private void cmi$allayBurnerBlasting(Level level, BlockPos pos, CallbackInfoReturnable<Boolean> cir) {
        BlockState state = level.getBlockState(pos);
        if (state.getBlock() instanceof AllayBurnerBlock)
            cir.setReturnValue(state.getValue(AllayBurnerBlock.HEAT_LEVEL) == AllayBurnerBlock.HeatLevel.ALLAYHEATED);
    }
}
