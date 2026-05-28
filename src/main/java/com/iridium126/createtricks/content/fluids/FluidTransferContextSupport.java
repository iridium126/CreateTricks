package com.iridium126.createtricks.content.fluids;

import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.neoforged.neoforge.fluids.FluidStack;

public final class FluidTransferContextSupport {
	private FluidTransferContextSupport() {}

	public static void captureLevel(Level level) {
		CreateTricksFluidTransferContext.setLevel(level);
	}

	public static void clearLevel() {
		CreateTricksFluidTransferContext.clear();
	}

	public static int getSpellInkRequiredAmount(ItemStack stack, FluidStack availableFluid) {
		return SpellInkFluidHandler.getRequiredAmountForFilling(stack, availableFluid);
	}

	public static ItemStack fillSpellInk(ItemStack stack, FluidStack availableFluid) {
		return SpellInkFluidHandler.fillItem(stack, availableFluid);
	}
}
