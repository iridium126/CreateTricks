package com.iridium126.createmanaindustry.content.recipes;

import at.petrak.hexcasting.api.item.MediaHolderItem;

import com.iridium126.createmanaindustry.CMIFluids;
import com.iridium126.createmanaindustry.CMIItems;
import com.iridium126.createmanaindustry.config.ServerConfig;
import com.iridium126.createmanaindustry.content.fluids.CMIFluidConversions;
import com.iridium126.createmanaindustry.content.items.IncompleteHexItem;
import com.iridium126.createmanaindustry.content.items.IncompleteMediaBatteryItem;

import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.neoforged.neoforge.fluids.FluidStack;

/**
 * Manages the multi-step liquid_media filling process for hex items via
 * Hexcasting's {@code MEDIA} data component.
 * <p>
 * Fresh-crafted hexcasting items are converted to their incomplete counterpart
 * (which tracks accumulated media in
 * {@link at.petrak.hexcasting.common.lib.HexDataComponents#MEDIA}), incomplete
 * items keep accumulating up to the per-item-type maximum, and finished
 * trinkets/artifacts are topped up in place without being downgraded. Finished
 * cyphers (single-use in Hexcasting) are never refilled.
 */
public final class HexItemFillingLogic {

    private HexItemFillingLogic() {}

    /**
     * Resolves the {@link MediaHolderItem} that should receive media for a given
     * input stack.
     *
     * @return the holder to fill (see {@link #fillIncompleteHexItem} for how
     *         fresh / incomplete / finished stacks are treated), or {@code null}
     *         if the stack is not recognised
     */
    private static MediaHolderItem resolve(ItemStack stack) {
        Item item = stack.getItem();
        if (item instanceof MediaHolderItem mhi && isIncompleteItem(item))
            return mhi;

        // hexcasting cypher/trinket/artifact:
        //   - fresh (no stored data) → the incomplete counterpart, so the fill
        //     converts the item into a pipeline intermediate;
        //   - finished (stored hex data present) → the item itself, so the fill
        //     tops up media in place without downgrading it. Even a finished
        //     cypher — which we never refill (see getRequiredFluidAmount) — must
        //     resolve non-null here, or the mixin would fall through to Create's
        //     filling recipe and revert the item.
        ResourceLocation id = BuiltInRegistries.ITEM.getKey(item);
        if (!"hexcasting".equals(id.getNamespace()))
            return null;

        boolean finished = HexItemDataTransfer.hasStoredHexData(stack);
        String path = id.getPath();
        if ("cypher".equals(path) && CMIItems.INCOMPLETE_CYPHER != null)
            return finished ? (MediaHolderItem) item : CMIItems.INCOMPLETE_CYPHER.get();
        if ("trinket".equals(path) && CMIItems.INCOMPLETE_TRINKET != null)
            return finished ? (MediaHolderItem) item : CMIItems.INCOMPLETE_TRINKET.get();
        if ("artifact".equals(path) && CMIItems.INCOMPLETE_ARTIFACT != null)
            return finished ? (MediaHolderItem) item : CMIItems.INCOMPLETE_ARTIFACT.get();

        return null;
    }

    private static boolean isIncompleteItem(Item item) {
        return item instanceof IncompleteHexItem || item instanceof IncompleteMediaBatteryItem;
    }

    // ---- fluid amount ------------------------------------------------------

    /**
     * Returns the mB of liquid_media to consume for the next fill operation,
     * capped at the Spout's per-operation limit.
     *
     * @return the required fluid amount in mB, or -1 if this is not a
     *         recognised item or the item is already at max capacity
     */
    public static int getRequiredFluidAmount(ItemStack stack, FluidStack availableFluid) {
        if (stack.isEmpty() || availableFluid.isEmpty()
                || !availableFluid.getFluid().isSame(CMIFluids.LIQUID_MEDIA.get()))
            return -1;

        MediaHolderItem holder = resolve(stack);
        if (holder == null)
            return -1;

        // Finished cyphers are single-use in Hexcasting (canRecharge = false,
        // they break on depletion) — never refill them through the spout.
        // Trinkets and artifacts are rechargeable and are topped up in place.
        if (HexItemDataTransfer.isFinishedHexItem(stack) && !holder.canRecharge(stack))
            return -1;

        long maxMedia = holder.getMaxMedia(stack);
        long currentMedia = holder.getMedia(stack);
        long remaining = maxMedia - currentMedia;
        if (remaining <= 0)
            return -1;

        // Cap the media added per operation to what one bucket provides
        long maxPerOp = ServerConfig.mediaPerBucket;
        long toAdd = Math.min(remaining, maxPerOp);
        return CMIFluidConversions.mediaToFluidAmount(toAdd);
    }

    // ---- fill operation ----------------------------------------------------

    /**
     * Performs one fill step, adding media to a hex item.
     * <p>
     * A fresh-crafted hexcasting item (no stored data) is first converted to its
     * incomplete counterpart with {@code MEDIA_MAX} set from config; an
     * incomplete intermediate is filled in place; a finished trinket/artifact is
     * topped up in place and stays finished.
     *
     * @param stack the item being filled (not modified in place)
     * @return the result stack, or {@link ItemStack#EMPTY} if invalid
     */
    public static ItemStack fillIncompleteHexItem(ItemStack stack, int fluidAmount) {
        MediaHolderItem holder = resolve(stack);
        if (holder == null)
            return ItemStack.EMPTY;

        long mediaToAdd = CMIFluidConversions.fluidAmountToMedia(fluidAmount);
        if (mediaToAdd <= 0)
            return ItemStack.EMPTY;

        // Finished cyphers are single-use in Hexcasting — never refill them
        // (defensive: the spout already refuses via getRequiredFluidAmount).
        if (HexItemDataTransfer.isFinishedHexItem(stack) && !holder.canRecharge(stack))
            return ItemStack.EMPTY;

        long maxMedia = holder.getMaxMedia(stack);
        long currentMedia = holder.getMedia(stack);
        if (currentMedia >= maxMedia)
            return ItemStack.EMPTY; // already full — consume no fluid

        ItemStack result;
        if (isIncompleteItem(stack.getItem()) || HexItemDataTransfer.hasStoredHexData(stack)) {
            // Incomplete intermediate, or a finished trinket/artifact being
            // topped up in place — copy and add media, keeping the item as-is.
            result = stack.copy();
        } else if (holder instanceof Item item) {
            // Fresh-crafted hexcasting item — create the incomplete counterpart
            // with default max media before adding media.
            result = new ItemStack(item);
            if (holder instanceof IncompleteHexItem hi)
                hi.ensureMaxMedia(result);
            else if (holder instanceof IncompleteMediaBatteryItem bi)
                bi.ensureMaxMedia(result);
        } else {
            return ItemStack.EMPTY;
        }

        long newMedia = Math.min(currentMedia + mediaToAdd, maxMedia);
        holder.setMedia(result, newMedia);

        return result;
    }

    /**
     * Checks whether the given stack is a recognised hex item — fresh-crafted,
     * incomplete, or a finished hexcasting spell item.
     */
    public static boolean isRecognised(ItemStack stack) {
        return resolve(stack) != null;
    }
}
