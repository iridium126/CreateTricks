package com.iridium126.createmanaindustry.content.burner;

import com.iridium126.createmanaindustry.CMIFluids;

import net.neoforged.neoforge.fluids.FluidStack;
import net.neoforged.neoforge.fluids.capability.IFluidHandler;

/**
 * Filters the Allay Burner's exposed fluid capability: pipes/buckets may only
 * {@code fill} Liquid Media, and extraction is refused — the burner's fuel
 * tank is strictly input-only. Internal consumption bypasses this wrapper
 * (the block entity drains via {@code SmartFluidTankBehaviour.getPrimaryHandler()}).
 */
public class AllayBurnerFluidHandler implements IFluidHandler {

    private final IFluidHandler delegate;

    public AllayBurnerFluidHandler(IFluidHandler delegate) {
        this.delegate = delegate;
    }

    @Override
    public int getTanks() {
        return delegate.getTanks();
    }

    @Override
    public FluidStack getFluidInTank(int tank) {
        return delegate.getFluidInTank(tank);
    }

    @Override
    public int getTankCapacity(int tank) {
        return delegate.getTankCapacity(tank);
    }

    @Override
    public boolean isFluidValid(int tank, FluidStack stack) {
        return isLiquidMedia(stack) && delegate.isFluidValid(tank, stack);
    }

    @Override
    public int fill(FluidStack resource, FluidAction action) {
        if (!isLiquidMedia(resource))
            return 0;
        return delegate.fill(resource, action);
    }

    @Override
    public FluidStack drain(FluidStack resource, FluidAction action) {
        return FluidStack.EMPTY;
    }

    @Override
    public FluidStack drain(int maxDrain, FluidAction action) {
        return FluidStack.EMPTY;
    }

    private static boolean isLiquidMedia(FluidStack stack) {
        return stack.getFluid().isSame(CMIFluids.LIQUID_MEDIA.get());
    }
}
