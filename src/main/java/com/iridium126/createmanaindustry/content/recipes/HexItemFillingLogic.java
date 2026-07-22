package com.iridium126.createmanaindustry.content.recipes;

import at.petrak.hexcasting.api.item.MediaHolderItem;

import com.iridium126.createmanaindustry.CMIFluids;
import com.iridium126.createmanaindustry.CMIItems;
import com.iridium126.createmanaindustry.config.Config;
import com.iridium126.createmanaindustry.content.fluids.CMIFluidConversions;
import com.iridium126.createmanaindustry.content.items.IncompleteHexItem;
import com.iridium126.createmanaindustry.content.items.IncompleteMediaBatteryItem;

import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.neoforged.neoforge.fluids.FluidStack;

/**
 * Manages the multi-step liquid_media filling process for incomplete hex items
 * via Hexcasting's {@code MEDIA} data component.
 * <p>
 * Each incomplete hex item tracks accumulated media directly in
 * {@link at.petrak.hexcasting.common.lib.HexDataComponents#MEDIA}. Filling adds
 * media up to the per-item-type maximum defined in {@link Config}.
 */
public final class HexItemFillingLogic {

    private HexItemFillingLogic() {}

    /**
     * Resolves the corresponding incomplete item (as a {@link MediaHolderItem})
     * for a given input stack.
     *
     * @return the incomplete item that should receive media, or {@code null} if
     *         the stack is not recognised
     */
    private static MediaHolderItem resolve(ItemStack stack) {
        Item item = stack.getItem();
        if (item instanceof MediaHolderItem mhi && isIncompleteItem(item))
            return mhi;

        // Check if it's a fresh-crafted hexcasting item
        ResourceLocation id = BuiltInRegistries.ITEM.getKey(item);
        if (!"hexcasting".equals(id.getNamespace()))
            return null;

        String path = id.getPath();
        if ("cypher".equals(path) && CMIItems.INCOMPLETE_CYPHER != null)
            return CMIItems.INCOMPLETE_CYPHER.get();
        if ("trinket".equals(path) && CMIItems.INCOMPLETE_TRINKET != null)
            return CMIItems.INCOMPLETE_TRINKET.get();
        if ("artifact".equals(path) && CMIItems.INCOMPLETE_ARTIFACT != null)
            return CMIItems.INCOMPLETE_ARTIFACT.get();

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

        long maxMedia = holder.getMaxMedia(stack);
        long currentMedia = holder.getMedia(stack);
        long remaining = maxMedia - currentMedia;
        if (remaining <= 0)
            return -1;

        // Cap the media added per operation to what one bucket provides
        long maxPerOp = Config.mediaPerBucket;
        long toAdd = Math.min(remaining, maxPerOp);
        return CMIFluidConversions.mediaToFluidAmount(toAdd);
    }

    // ---- fill operation ----------------------------------------------------

    /**
     * Performs one fill step, adding media to the incomplete hex item.
     * <p>
     * If the input is a fresh-crafted item or glass bottle (not yet incomplete),
     * it is converted to the corresponding incomplete item with
     * {@code MEDIA_MAX} set from config before media is added.
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

        ItemStack result;
        if (isIncompleteItem(stack.getItem())) {
            // Already incomplete — copy and add media
            result = stack.copy();
        } else if (holder instanceof Item item) {
            // Fresh item or glass bottle — create incomplete copy with default max media
            result = new ItemStack(item);
            if (holder instanceof IncompleteHexItem hi)
                hi.ensureMaxMedia(result);
            else if (holder instanceof IncompleteMediaBatteryItem bi)
                bi.ensureMaxMedia(result);
        } else {
            return ItemStack.EMPTY;
        }

        long currentMedia = holder.getMedia(result);
        long maxMedia = holder.getMaxMedia(result);
        long newMedia = Math.min(currentMedia + mediaToAdd, maxMedia);
        holder.setMedia(result, newMedia);

        return result;
    }

    /**
     * Checks whether the given stack is a recognised hex item
     * (fresh-crafted or incomplete).
     */
    public static boolean isRecognised(ItemStack stack) {
        return resolve(stack) != null;
    }
}
