package com.iridium126.createmanaindustry.mixin.allvr;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import com.iridium126.createmanaindustry.client.dimension.AllvrLightSampler;
import com.iridium126.createmanaindustry.dimension.AllvrDimensions;

import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.BlockAndTintGetter;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.Level;

/**
 * Synthetic entity lighting inside the allay dimension (doc §10.4): the
 * vanilla light engine never computes column light there, so entity renders
 * sample pitch-dark packed coordinates. The 3-arg
 * {@code LevelRenderer.getLightColor} is the real implementation (the 2-arg
 * overload delegates to it), covering the entity render path.
 */
@Mixin(net.minecraft.client.renderer.LevelRenderer.class)
public abstract class AllvrGetLightColorMixin {

    @Inject(method = "getLightColor(Lnet/minecraft/world/level/BlockAndTintGetter;Lnet/minecraft/world/level/block/state/BlockState;Lnet/minecraft/core/BlockPos;)I",
        at = @At("HEAD"), cancellable = true)
    private static void allvr$syntheticLight(BlockAndTintGetter getter, BlockState state, BlockPos pos,
                                             CallbackInfoReturnable<Integer> cir) {
        if (getter instanceof Level level && level.isClientSide
            && level.dimension() == AllvrDimensions.ALLAY_LEVEL && level instanceof ClientLevel clientLevel) {
            cir.setReturnValue(AllvrLightSampler.sample(clientLevel, pos));
        }
    }
}
