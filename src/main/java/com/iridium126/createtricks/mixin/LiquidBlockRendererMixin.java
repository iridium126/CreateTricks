package com.iridium126.createtricks.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import com.iridium126.createtricks.CreateTricksFluids;

import net.minecraft.client.renderer.block.LiquidBlockRenderer;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.BlockAndTintGetter;
import net.minecraft.world.level.material.FluidState;

@Mixin(LiquidBlockRenderer.class)
public class LiquidBlockRendererMixin {
	private static final int FULL_BRIGHT_LIGHT = 0x00F000F0;

	@Inject(method = "getLightColor", at = @At("HEAD"), cancellable = true)
	private void createtricks$makeLiquidManaFullBright(BlockAndTintGetter level, BlockPos pos,
			CallbackInfoReturnable<Integer> cir) {
		if (isLiquidMana(level.getFluidState(pos)) || isLiquidMana(level.getFluidState(pos.below())))
			cir.setReturnValue(FULL_BRIGHT_LIGHT);
	}

	private static boolean isLiquidMana(FluidState fluidState) {
		return fluidState.getType().isSame(CreateTricksFluids.LIQUID_MANA.get())
				|| fluidState.getType().isSame(CreateTricksFluids.LIQUID_MANA.getSource());
	}
}
