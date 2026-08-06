package com.iridium126.createmanaindustry.compat.trickster;

import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.neoforged.neoforge.items.IItemHandler;

/**
 * Item handler for spell constructs: media-chargeable items are absorbed into
 * {@link ConstructMediaStorage} (hoppers/funnels charging the construct);
 * everything else delegates to the wrapped knot handler, preserving the
 * existing knot automation.
 */
public final class ConstructMediaItemHandler implements IItemHandler {

    private final BlockEntity blockEntity;
    private final IItemHandler delegate;

    public ConstructMediaItemHandler(BlockEntity blockEntity, IItemHandler delegate) {
        this.blockEntity = blockEntity;
        this.delegate = delegate;
    }

    @Override
    public int getSlots() {
        return delegate.getSlots();
    }

    @Override
    public ItemStack getStackInSlot(int slot) {
        return delegate.getStackInSlot(slot);
    }

    @Override
    public ItemStack insertItem(int slot, ItemStack stack, boolean simulate) {
        if (stack.isEmpty()) {
            return stack;
        }
        if (ConstructMediaStorage.canInsertMedia(blockEntity, stack)) {
            ItemStack remainder = ConstructMediaStorage.getInsertionRemainder(blockEntity, stack, simulate);
            return remainder.isEmpty() ? ItemStack.EMPTY : remainder;
        }
        return delegate.insertItem(slot, stack, simulate);
    }

    @Override
    public ItemStack extractItem(int slot, int amount, boolean simulate) {
        return delegate.extractItem(slot, amount, simulate);
    }

    @Override
    public int getSlotLimit(int slot) {
        return delegate.getSlotLimit(slot);
    }

    @Override
    public boolean isItemValid(int slot, ItemStack stack) {
        return ConstructMediaStorage.canInsertMedia(blockEntity, stack) || delegate.isItemValid(slot, stack);
    }
}
