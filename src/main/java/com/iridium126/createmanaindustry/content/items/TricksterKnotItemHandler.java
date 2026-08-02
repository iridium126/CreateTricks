package com.iridium126.createmanaindustry.content.items;

import com.iridium126.createmanaindustry.compat.trickster.TricksterKnotUtils;

import net.minecraft.world.Container;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.neoforged.neoforge.items.IItemHandler;

public class TricksterKnotItemHandler implements IItemHandler {
    public enum Mode {
        ALL_SLOTS,
        FIRST_SLOT
    }

    private final Container container;
    private final Mode mode;

    public static IItemHandler create(BlockEntity blockEntity, Mode mode) {
        return blockEntity instanceof Container container ? new TricksterKnotItemHandler(container, mode) : null;
    }

    private TricksterKnotItemHandler(Container container, Mode mode) {
        this.container = container;
        this.mode = mode;
    }

    @Override
    public int getSlots() {
        return switch (mode) {
            case ALL_SLOTS -> container.getContainerSize();
            case FIRST_SLOT -> container.getContainerSize() > 0 ? 1 : 0;
        };
    }

    @Override
    public ItemStack getStackInSlot(int slot) {
        if (!isSlotInRange(slot))
            return ItemStack.EMPTY;

        ItemStack stack = container.getItem(slot);
        return TricksterKnotUtils.isKnotStack(stack) ? stack : ItemStack.EMPTY;
    }

    @Override
    public ItemStack insertItem(int slot, ItemStack stack, boolean simulate) {
        if (!isSlotInRange(slot) || stack.isEmpty() || !TricksterKnotUtils.isKnotStack(stack))
            return stack;

        if (!container.getItem(slot).isEmpty())
            return stack;

        int inserted = Math.min(stack.getCount(), Math.min(getSlotLimit(slot), stack.getMaxStackSize()));
        if (inserted <= 0)
            return stack;

        if (!simulate) {
            ItemStack copy = stack.copy();
            copy.setCount(inserted);
            container.setItem(slot, copy);
        }

        if (inserted == stack.getCount())
            return ItemStack.EMPTY;

        ItemStack remainder = stack.copy();
        remainder.shrink(inserted);
        return remainder;
    }

    @Override
    public ItemStack extractItem(int slot, int amount, boolean simulate) {
        if (!isSlotInRange(slot) || amount <= 0)
            return ItemStack.EMPTY;

        ItemStack stored = container.getItem(slot);
        if (!TricksterKnotUtils.isKnotStack(stored))
            return ItemStack.EMPTY;

        int extracted = Math.min(amount, stored.getCount());
        ItemStack result = stored.copy();
        result.setCount(extracted);

        if (!simulate) {
            ItemStack remaining = stored.copy();
            remaining.shrink(extracted);
            container.setItem(slot, remaining);
        }
        return result;
    }

    @Override
    public int getSlotLimit(int slot) {
        return isSlotInRange(slot) ? 64 : 0;
    }

    @Override
    public boolean isItemValid(int slot, ItemStack stack) {
        return isSlotInRange(slot) && TricksterKnotUtils.isKnotStack(stack);
    }

    private boolean isSlotInRange(int slot) {
        return slot >= 0 && slot < getSlots();
    }
}
