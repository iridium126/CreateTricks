package com.iridium126.createtricks.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import com.iridium126.createtricks.content.fluids.FluidTransferContextSupport;
import com.simibubi.create.content.fluids.transfer.GenericItemEmptying;

import net.createmod.catnip.data.Pair;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.neoforged.neoforge.fluids.FluidStack;

@Mixin(value = GenericItemEmptying.class, remap = false)
public class GenericItemEmptyingContextMixin {
	@Inject(method = "canItemBeEmptied", at = @At("HEAD"))
	private static void createtricks$setLevelForEmptyCheck(Level world, ItemStack stack,
			CallbackInfoReturnable<Boolean> cir) {
		FluidTransferContextSupport.captureLevel(world);
	}

	@Inject(method = "canItemBeEmptied", at = @At("RETURN"))
	private static void createtricks$clearLevelForEmptyCheck(Level world, ItemStack stack,
			CallbackInfoReturnable<Boolean> cir) {
		FluidTransferContextSupport.clearLevel();
	}

	@Inject(method = "emptyItem", at = @At("HEAD"))
	private static void createtricks$setLevelForEmptying(Level level, ItemStack stack, boolean simulate,
			CallbackInfoReturnable<Pair<FluidStack, ItemStack>> cir) {
		FluidTransferContextSupport.captureLevel(level);
	}

	@Inject(method = "emptyItem", at = @At("RETURN"))
	private static void createtricks$clearLevelForEmptying(Level level, ItemStack stack, boolean simulate,
			CallbackInfoReturnable<Pair<FluidStack, ItemStack>> cir) {
		FluidTransferContextSupport.clearLevel();
	}
}
