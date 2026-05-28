package com.iridium126.createtricks.content.fluids;

import com.iridium126.createtricks.Config;

public final class CreateTricksFluidConversions {
	private CreateTricksFluidConversions() {}

	public static int manaToFluidAmount(float manaAmount) {
		if (manaAmount <= 0)
			return 0;

		double amount = Math.ceil(manaAmount * 1000.0 / Config.manaPerBucket);
		return (int) Math.min(Integer.MAX_VALUE, Math.max(1, amount));
	}

	public static int manaToFluidAmount(float manaAmount, int maxAmount) {
		if (maxAmount <= 0)
			return 0;
		return Math.min(manaToFluidAmount(manaAmount), maxAmount);
	}

	public static float fluidAmountToMana(int fluidAmount) {
		if (fluidAmount <= 0)
			return 0;
		return fluidAmount * Config.manaPerBucket / 1000f;
	}
}
