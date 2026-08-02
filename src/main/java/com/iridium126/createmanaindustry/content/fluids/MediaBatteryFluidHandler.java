package com.iridium126.createmanaindustry.content.fluids;

import com.iridium126.createmanaindustry.CMIFluids;
import com.iridium126.createmanaindustry.compat.hexcasting.HexCompat;

import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.material.Fluid;
import net.neoforged.neoforge.fluids.FluidStack;
import net.neoforged.neoforge.fluids.capability.IFluidHandlerItem;

/**
 * Bridges Create's fluid system to Hexcasting's media data-component system
 * for {@code hexcasting:battery} and other {@link
 * at.petrak.hexcasting.api.item.MediaHolderItem} items.
 * <p>
 * Media is stored in item data components ({@code hexcasting:media} /
 * {@code hexcasting:start_media}) — no {@code Level} context is needed.
 * Filling and emptying operate on the same item stack (only data components
 * change), unlike the glass-bottle-to-esoteric-mana flow which swaps the item.
 * <p>
 * Conversion: 1 bucket (1000 mB) of Liquid Media = {@code mediaPerBucket}
 * media units (default 400,000).
 * <p>
 * All Hexcasting API access goes through {@link HexCompat} so this class
 * is safe to load even when Hexcasting is absent.
 */
public class MediaBatteryFluidHandler implements IFluidHandlerItem {

    private ItemStack container;

    public MediaBatteryFluidHandler(ItemStack container) {
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
        long media = HexCompat.getMedia(container);
        int amount = CMIFluidConversions.mediaToFluidAmount(media);
        // Report the source variant, matching buckets / world fluid / recipes:
        // get() returns the flowing variant, which breaks identity-matching
        // consumers (e.g. FluidTank.drain(FluidStack)). The local is needed —
        // getSource() is a generic method and direct use would make the
        // FluidStack(Holder<Fluid>, int) constructor ambiguous.
        Fluid liquidMedia = CMIFluids.LIQUID_MEDIA.getSource();
        return amount > 0
                ? new FluidStack(liquidMedia, amount)
                : FluidStack.EMPTY;
    }

    @Override
    public int getTankCapacity(int tank) {
        if (tank != 0)
            return 0;
        long maxMedia = HexCompat.getMaxMedia(container);
        return maxMedia <= 0 ? 0 : CMIFluidConversions.mediaToFluidAmount(maxMedia);
    }

    @Override
    public boolean isFluidValid(int tank, FluidStack stack) {
        return tank == 0
                && !stack.isEmpty()
                && stack.getFluid().isSame(CMIFluids.LIQUID_MEDIA.get());
    }

    @Override
    public int fill(FluidStack resource, FluidAction action) {
        if (resource.isEmpty())
            return 0;
        if (!isFluidValid(0, resource))
            return 0;
        if (container.getCount() != 1)
            return 0;

        if (!HexCompat.canRecharge(container))
            return 0;

        long currentMedia = HexCompat.getMedia(container);
        long maxMedia = HexCompat.getMaxMedia(container);
        if (maxMedia <= 0 || currentMedia >= maxMedia)
            return 0;

        long mediaSpace = maxMedia - currentMedia;

        // How much fluid could we theoretically accept based on media space?
        int maxFluidToAccept = CMIFluidConversions.mediaToFluidAmount(mediaSpace);

        // Clamp to the actual fluid available
        int fluidToAccept = Math.min(resource.getAmount(), maxFluidToAccept);
        if (fluidToAccept <= 0)
            return 0;

        // Convert accepted fluid to media, clamped to actual space
        long mediaToAdd = Math.min(
                CMIFluidConversions.fluidAmountToMedia(fluidToAccept),
                mediaSpace);

        if (mediaToAdd <= 0)
            return 0;

        if (action.execute()) {
            HexCompat.insertMedia(container, mediaToAdd);
        }

        return fluidToAccept;
    }

    @Override
    public FluidStack drain(FluidStack resource, FluidAction action) {
        if (resource.isEmpty() || container.getCount() != 1)
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
        if (maxDrain <= 0 || container.getCount() != 1)
            return FluidStack.EMPTY;

        if (!HexCompat.canProvideMedia(container))
            return FluidStack.EMPTY;

        long currentMedia = HexCompat.getMedia(container);
        if (currentMedia <= 0)
            return FluidStack.EMPTY;

        // What fluid amount does our current media represent?
        int drainableFluid = CMIFluidConversions.mediaToFluidAmount(currentMedia);
        if (drainableFluid <= 0)
            return FluidStack.EMPTY;

        int fluidToDrain = Math.min(maxDrain, drainableFluid);

        // Convert drained fluid to media units, clamped to actual holdings
        long mediaToWithdraw = Math.min(
                CMIFluidConversions.fluidAmountToMedia(fluidToDrain),
                currentMedia);

        if (mediaToWithdraw <= 0)
            return FluidStack.EMPTY;

        if (action.execute()) {
            HexCompat.withdrawMedia(container, mediaToWithdraw);
        }

        Fluid liquidMedia = CMIFluids.LIQUID_MEDIA.getSource();
        return new FluidStack(liquidMedia, fluidToDrain);
    }
}
