package com.iridium126.createtricks.content.fluids;

import com.iridium126.createtricks.CreateTricksFluids;
import com.iridium126.createtricks.trickster.TricksterReflection;

import org.jetbrains.annotations.Nullable;

import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.neoforged.neoforge.fluids.FluidStack;
import net.neoforged.neoforge.fluids.capability.IFluidHandler;
import net.neoforged.neoforge.fluids.capability.IFluidHandlerItem;

public class TricksterKnotFluidHandler implements IFluidHandlerItem {
	private ItemStack container;

	public TricksterKnotFluidHandler(ItemStack container) {
		this.container = container;
	}

	@Override
	public ItemStack getContainer() {
		return container;
	}

	@Override
	public int getTanks() {
		return 1;
	}

	@Override
	public FluidStack getFluidInTank(int tank) {
		if (tank != 0)
			return FluidStack.EMPTY;
		int amount = getDrainableAmount();
		if (amount <= 0)
			return FluidStack.EMPTY;
		return new FluidStack(CreateTricksFluids.LIQUID_MANA.get(), amount);
	}

	@Override
	public int getTankCapacity(int tank) {
		return tank == 0 ? getCapacityAmount() : 0;
	}

	@Override
	public boolean isFluidValid(int tank, FluidStack stack) {
		return tank == 0 && stack.getFluid().isSame(CreateTricksFluids.LIQUID_MANA.get());
	}

	@Override
	public int fill(FluidStack resource, IFluidHandler.FluidAction action) {
		if (resource.isEmpty() || !resource.getFluid().isSame(CreateTricksFluids.LIQUID_MANA.get()))
			return 0;
		return transferFluid(resource.getAmount(), action, true);
	}

	@Override
	public FluidStack drain(FluidStack resource, IFluidHandler.FluidAction action) {
		FluidStack containedFluid = getFluidInTank(0);
		if (resource.isEmpty() || containedFluid.isEmpty())
			return FluidStack.EMPTY;
		if (!FluidStack.isSameFluidSameComponents(resource, containedFluid))
			return FluidStack.EMPTY;
		return drain(resource.getAmount(), action);
	}

	@Override
	public FluidStack drain(int maxDrain, IFluidHandler.FluidAction action) {
		int drainedAmount = transferFluid(maxDrain, action, false);
		return drainedAmount > 0 ? new FluidStack(CreateTricksFluids.LIQUID_MANA.get(), drainedAmount) : FluidStack.EMPTY;
	}

	private int transferFluid(int requestedFluidAmount, IFluidHandler.FluidAction action, boolean filling) {
		if (!canTransferFluid(requestedFluidAmount, filling))
			return 0;

		int transferableFluidAmount = getTransferableFluidAmount(filling);
		if (transferableFluidAmount <= 0)
			return 0;

		int fluidAmount = Math.min(requestedFluidAmount, transferableFluidAmount);
		if (!action.execute())
			return fluidAmount;

		Level level = CreateTricksFluidTransferContext.getLevel();
		if (level == null)
			return 0;

		float transferredMana = transferMana(level, fluidAmount, filling);
		return transferredMana > 0 ? toFluidAmount(transferredMana, fluidAmount) : 0;
	}

	private boolean canTransferFluid(int requestedFluidAmount, boolean filling) {
		return requestedFluidAmount > 0
				&& container.getCount() == 1
				&& isKnotContainer()
				&& (!filling || !hasInfiniteMana());
	}

	private int getTransferableFluidAmount(boolean filling) {
		return filling ? getFillableAmount() : getDrainableAmount();
	}

	private float transferMana(Level level, int fluidAmount, boolean filling) {
		float manaAmount = CreateTricksFluidConversions.fluidAmountToMana(fluidAmount);
		if (manaAmount <= 0)
			return 0;

		return filling
				? TricksterReflection.refillMana(container, level, manaAmount)
				: TricksterReflection.drainMana(container, level, manaAmount);
	}

	private int getDrainableAmount() {
		if (!isKnotContainer())
			return 0;
		if (hasInfiniteMana())
			return 1000;

		Level level = getContextLevel();
		if (level == null)
			return 0;

		return getPositiveManaFluidAmount(TricksterReflection.getMana(container, level));
	}

	private int getFillableAmount() {
		if (!isKnotContainer() || hasInfiniteMana())
			return 0;

		Level level = getContextLevel();
		if (level == null)
			return 0;

		float currentMana = TricksterReflection.getMana(container, level);
		float maxMana = TricksterReflection.getMaxMana(container, level);
		return getPositiveManaFluidAmount(Math.max(0, maxMana - currentMana));
	}

	private int getCapacityAmount() {
		if (!isKnotContainer())
			return 0;
		if (hasInfiniteMana())
			return 1000;

		Level level = getContextLevel();
		if (level == null)
			return 0;

		return getPositiveManaFluidAmount(TricksterReflection.getMaxMana(container, level));
	}

	private boolean isKnotContainer() {
		return TricksterReflection.isKnotStack(container);
	}

	private boolean hasInfiniteMana() {
		return TricksterReflection.hasInfiniteMana(container);
	}

	@Nullable
	private Level getContextLevel() {
		return CreateTricksFluidTransferContext.getLevel();
	}

	private int getPositiveManaFluidAmount(float manaAmount) {
		return manaAmount > 0 ? CreateTricksFluidConversions.manaToFluidAmount(manaAmount) : 0;
	}

	private static int toFluidAmount(float manaAmount, int maxAmount) {
		return CreateTricksFluidConversions.manaToFluidAmount(manaAmount, maxAmount);
	}
}
