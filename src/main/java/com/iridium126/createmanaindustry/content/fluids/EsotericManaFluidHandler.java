package com.iridium126.createmanaindustry.content.fluids;

import com.iridium126.createmanaindustry.CMIFluids;
import com.iridium126.createmanaindustry.compat.trickster.TricksterManaAccess;

import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.neoforged.neoforge.fluids.FluidStack;
import net.neoforged.neoforge.fluids.capability.IFluidHandler;
import net.neoforged.neoforge.fluids.capability.IFluidHandlerItem;

public class EsotericManaFluidHandler implements IFluidHandlerItem {
    private static final float DEFAULT_ESOTERIC_MANA = 512;

    public static final ResourceLocation ESOTERIC_MANA_ID = ResourceLocation.fromNamespaceAndPath("trickster",
            "esoteric_mana");

    private static volatile Item cachedEsotericManaItem;

    private static Item getEsotericManaItem() {
        Item item = cachedEsotericManaItem;
        if (item == null) {
            item = BuiltInRegistries.ITEM.get(ESOTERIC_MANA_ID);
            cachedEsotericManaItem = item;
        }
        return item;
    }

    public static boolean canFillItem(ItemStack stack, FluidStack availableFluid) {
        return stack.getItem() == Items.GLASS_BOTTLE
                && !availableFluid.isEmpty()
                && availableFluid.getFluid().isSame(CMIFluids.LIQUID_MANA.get());
    }

    public static int getRequiredAmountForFilling(ItemStack stack, FluidStack availableFluid) {
        return canFillItem(stack, availableFluid)
                ? CMIFluidConversions.manaToFluidAmount(DEFAULT_ESOTERIC_MANA)
                : -1;
    }

    public static ItemStack createFilledBottle() {
        Item esotericMana = getEsotericManaItem();
        return esotericMana == Items.AIR ? ItemStack.EMPTY : new ItemStack(esotericMana);
    }

    public static ItemStack fillItem(ItemStack stack, FluidStack availableFluid) {
        int requiredAmount = getRequiredAmountForFilling(stack, availableFluid);
        if (requiredAmount < 0 || availableFluid.getAmount() < requiredAmount)
            return ItemStack.EMPTY;

        ItemStack filledBottle = createFilledBottle();
        if (filledBottle.isEmpty())
            return ItemStack.EMPTY;

        availableFluid.shrink(requiredAmount);
        stack.shrink(1);
        return filledBottle;
    }

    private ItemStack container;

    public EsotericManaFluidHandler(ItemStack container) {
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
        return tank == 0 ? getContainedFluid() : FluidStack.EMPTY;
    }

    @Override
    public int getTankCapacity(int tank) {
        return tank == 0 ? getContainedFluid().getAmount() : 0;
    }

    @Override
    public boolean isFluidValid(int tank, FluidStack stack) {
        return false;
    }

    @Override
    public int fill(FluidStack resource, IFluidHandler.FluidAction action) {
        return 0;
    }

    @Override
    public FluidStack drain(FluidStack resource, IFluidHandler.FluidAction action) {
        FluidStack containedFluid = getContainedFluid();
        if (resource.isEmpty() || containedFluid.isEmpty())
            return FluidStack.EMPTY;
        if (!FluidStack.isSameFluidSameComponents(resource, containedFluid))
            return FluidStack.EMPTY;

        return drain(resource.getAmount(), action);
    }

    @Override
    public FluidStack drain(int maxDrain, IFluidHandler.FluidAction action) {
        if (container.getCount() != 1 || maxDrain <= 0)
            return FluidStack.EMPTY;

        FluidStack containedFluid = getContainedFluid();
        if (containedFluid.isEmpty() || maxDrain < containedFluid.getAmount())
            return FluidStack.EMPTY;

        if (action.execute())
            container = new ItemStack(Items.GLASS_BOTTLE);

        return containedFluid;
    }

    private FluidStack getContainedFluid() {
        if (!isEsotericMana(container))
            return FluidStack.EMPTY;

        float mana = TricksterManaAccess.getMana(container, CMIFluidTransferContext.getLevel());
        if (mana <= 0)
            mana = DEFAULT_ESOTERIC_MANA;

        int amount = CMIFluidConversions.manaToFluidAmount(mana);
        return new FluidStack(CMIFluids.LIQUID_MANA.get(), amount);
    }

    private static boolean isEsotericMana(ItemStack stack) {
        return stack.getItem() == getEsotericManaItem();
    }
}
