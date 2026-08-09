package com.iridium126.createmanaindustry.compat.ars;

import com.hollingsworth.arsnouveau.api.source.AbstractSourceMachine;
import com.iridium126.createmanaindustry.CMIFluids;
import com.iridium126.createmanaindustry.content.fluids.CMIFluidConversions;

import net.neoforged.neoforge.fluids.FluidStack;
import net.neoforged.neoforge.fluids.capability.IFluidHandler;

/**
 * Bridges Create's fluid system to Ars Nouveau's Source system for
 * {@code SourceJarTile} and {@code CreativeSourceJarTile}.
 * <p>
 * The SourceJar does <b>not</b> store Liquid Source directly.  Instead:
 * <ul>
 *   <li>Filling converts Liquid Source into Ars Nouveau source units
 *       and adds them to the jar's {@link AbstractSourceMachine} storage.</li>
 *   <li>Draining converts source units back into Liquid Source fluid,
 *       removing the equivalent source from the jar.</li>
 * </ul>
 * Conversion uses {@code ServerConfig.sourcePerBucket} at runtime so the ratio
 * updates immediately when the config changes.
 * <p>
 * This class must <b>only</b> be accessed when
 * {@code CreateManaIndustry.ARS_ACTIVE} is {@code true}.  When Ars Nouveau
 * is absent the JVM never loads this class, avoiding
 * {@link NoClassDefFoundError}.
 *
 * @see CMIFluidConversions#sourceToFluidAmount(int)
 * @see CMIFluidConversions#fluidAmountToSource(int)
 */
public class SourceJarFluidHandler implements IFluidHandler {

    private final AbstractSourceMachine jarTile;

    public SourceJarFluidHandler(AbstractSourceMachine jarTile) {
        this.jarTile = jarTile;
    }

    @Override
    public int getTanks() {
        return 1;
    }

    @Override
    public FluidStack getFluidInTank(int tank) {
        if (tank != 0)
            return FluidStack.EMPTY;
        int source = jarTile.getSource();
        int amount = CMIFluidConversions.sourceToFluidAmount(source);
        return amount > 0
                ? new FluidStack(CMIFluids.LIQUID_SOURCE.get(), amount)
                : FluidStack.EMPTY;
    }

    @Override
    public int getTankCapacity(int tank) {
        if (tank != 0)
            return 0;
        return CMIFluidConversions.sourceToFluidAmount(jarTile.getMaxSource());
    }

    @Override
    public boolean isFluidValid(int tank, FluidStack stack) {
        return tank == 0
                && !stack.isEmpty()
                && stack.getFluid().isSame(CMIFluids.LIQUID_SOURCE.get());
    }

    @Override
    public int fill(FluidStack resource, FluidAction action) {
        if (resource.isEmpty() || !isFluidValid(0, resource))
            return 0;
        if (!jarTile.canAcceptSource())
            return 0;

        int sourceToAdd = CMIFluidConversions.fluidAmountToSource(resource.getAmount());
        if (sourceToAdd <= 0)
            return 0;

        int accepted = jarTile.addSource(sourceToAdd, action.simulate());
        return CMIFluidConversions.sourceToFluidAmount(accepted);
    }

    @Override
    public FluidStack drain(FluidStack resource, FluidAction action) {
        if (resource.isEmpty())
            return FluidStack.EMPTY;

        FluidStack contained = getFluidInTank(0);
        if (contained.isEmpty())
            return FluidStack.EMPTY;
        if (!FluidStack.isSameFluidSameComponents(resource, contained))
            return FluidStack.EMPTY;

        return drain(resource.getAmount(), action);
    }

    @Override
    public FluidStack drain(int maxDrain, FluidAction action) {
        if (maxDrain <= 0)
            return FluidStack.EMPTY;
        if (!jarTile.canProvideSource())
            return FluidStack.EMPTY;

        int drainableSource = jarTile.getSource();
        int maxFluidFromSource = CMIFluidConversions.sourceToFluidAmount(drainableSource);
        if (maxFluidFromSource <= 0)
            return FluidStack.EMPTY;

        int fluidToDrain = Math.min(maxDrain, maxFluidFromSource);
        int sourceToRemove = CMIFluidConversions.fluidAmountToSource(fluidToDrain);
        if (sourceToRemove <= 0)
            return FluidStack.EMPTY;

        int removed = jarTile.removeSource(sourceToRemove, action.simulate());
        if (removed <= 0)
            return FluidStack.EMPTY;

        int drainedFluid = CMIFluidConversions.sourceToFluidAmount(removed);
        return drainedFluid > 0
                ? new FluidStack(CMIFluids.LIQUID_SOURCE.get(), drainedFluid)
                : FluidStack.EMPTY;
    }
}
