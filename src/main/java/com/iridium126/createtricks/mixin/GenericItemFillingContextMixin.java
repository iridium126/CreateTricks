package com.iridium126.createtricks.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import com.iridium126.createtricks.content.fluids.FluidTransferContextSupport;
import com.simibubi.create.content.fluids.transfer.GenericItemFilling;

import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.neoforged.neoforge.fluids.FluidStack;

@Mixin(value = GenericItemFilling.class, remap = false)
public class GenericItemFillingContextMixin {
	@Inject(method = "canItemBeFilled", at = @At("HEAD"))
	private static void createtricks$setLevelForFillCheck(Level world, ItemStack stack,
			CallbackInfoReturnable<Boolean> cir) {
		FluidTransferContextSupport.captureLevel(world);
	}

	@Inject(method = "canItemBeFilled", at = @At("RETURN"))
	private static void createtricks$clearLevelForFillCheck(Level world, ItemStack stack,
			CallbackInfoReturnable<Boolean> cir) {
		FluidTransferContextSupport.clearLevel();
	}

	@Inject(method = "getRequiredAmountForItem", at = @At("HEAD"), cancellable = true)
	private static void createtricks$setLevelForRequiredAmount(Level world, ItemStack stack, FluidStack availableFluid,
			CallbackInfoReturnable<Integer> cir) {
		FluidTransferContextSupport.captureLevel(world);
		int requiredAmount = FluidTransferContextSupport.getSpellInkRequiredAmount(stack, availableFluid);
		if (requiredAmount >= 0)
			cir.setReturnValue(requiredAmount);
	}

	@Inject(method = "getRequiredAmountForItem", at = @At("RETURN"))
	private static void createtricks$clearLevelForRequiredAmount(Level world, ItemStack stack, FluidStack availableFluid,
			CallbackInfoReturnable<Integer> cir) {
		FluidTransferContextSupport.clearLevel();
	}

	@Inject(method = "fillItem", at = @At("HEAD"), cancellable = true)
	private static void createtricks$setLevelForFilling(Level world, int requiredAmount, ItemStack stack,
			FluidStack availableFluid, CallbackInfoReturnable<ItemStack> cir) {
		FluidTransferContextSupport.captureLevel(world);
		if (requiredAmount != FluidTransferContextSupport.getSpellInkRequiredAmount(stack, availableFluid))
			return;

		ItemStack filledItem = FluidTransferContextSupport.fillSpellInk(stack, availableFluid);
		if (!filledItem.isEmpty())
			cir.setReturnValue(filledItem);
	}

	@Inject(method = "fillItem", at = @At("RETURN"))
	private static void createtricks$clearLevelForFilling(Level world, int requiredAmount, ItemStack stack,
			FluidStack availableFluid, CallbackInfoReturnable<ItemStack> cir) {
		FluidTransferContextSupport.clearLevel();
	}
}
