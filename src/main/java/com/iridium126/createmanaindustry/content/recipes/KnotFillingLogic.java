package com.iridium126.createmanaindustry.content.recipes;

import java.util.Map;

import com.iridium126.createmanaindustry.CMIComponents;
import com.iridium126.createmanaindustry.CMIFluids;
import com.iridium126.createmanaindustry.CMIItems;
import com.iridium126.createmanaindustry.config.Config;
import com.iridium126.createmanaindustry.content.fluids.CMIFluidConversions;
import com.iridium126.createmanaindustry.trickster.TricksterManaAccess;

import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.neoforged.neoforge.fluids.FluidStack;

/**
 * Manages the multi-step liquid-mana filling process for incomplete knot items
 * via the {@code filled_mana} data component.
 * <p>
 * Each incomplete knot stores cumulative Trickster mana already infused
 * ({@link CMIComponents#FILLED_MANA}). When the total reaches the knot's
 * {@code creationCost}, the item is replaced with the final Trickster knot.
 */
public final class KnotFillingLogic {

    private static volatile Map<Item, KnotEntry> knots;

    private static Map<Item, KnotEntry> getKnots() {
        if (knots == null) {
            synchronized (KnotFillingLogic.class) {
                if (knots == null) {
                    knots = Map.of(
                            CMIItems.INCOMPLETE_EMERALD_KNOT.get(), new KnotEntry(knot("emerald_knot"), 512),
                            CMIItems.INCOMPLETE_DIAMOND_KNOT.get(), new KnotEntry(knot("diamond_knot"), 8192),
                            CMIItems.INCOMPLETE_ECHO_KNOT.get(), new KnotEntry(knot("echo_knot"), 65536),
                            CMIItems.INCOMPLETE_ASTRAL_KNOT.get(), new KnotEntry(knot("astral_knot"), 524288),
                            CMIItems.INCOMPLETE_PRISMATIC_KNOT.get(), new KnotEntry(knot("prismatic_knot"), 8192));
                }
            }
        }
        return knots;
    }

    private KnotFillingLogic() {}

    // ---- public: fluid amount ------------------------------------------------

    /**
     * Returns the mB of liquid_mana to consume for the next fill operation on
     * this incomplete knot, capped at the Spout's per-operation limit.
     *
     * @return the required fluid amount in mB, or -1 if this is not an
     *         incomplete knot or the knot is already fully charged
     */
    public static int getRequiredFluidAmount(ItemStack stack, FluidStack availableFluid) {
        if (stack.isEmpty() || availableFluid.isEmpty()
                || !availableFluid.getFluid().isSame(CMIFluids.LIQUID_MANA.get()))
            return -1;

        KnotEntry entry = getKnots().get(stack.getItem());
        if (entry == null)
            return -1;

        float remaining = entry.resolvedCreationCost - getFilledMana(stack);
        if (remaining <= 0)
            return -1;

        float toAdd = Math.min(remaining, Config.manaPerBucket);
        return CMIFluidConversions.manaToFluidAmount(toAdd);
    }

    // ---- public: fill operation ----------------------------------------------

    /**
     * Performs one fill step on an incomplete knot, advancing the
     * {@code filled_mana} component. Returns the incomplete knot with updated
     * progress, or the final knot when the creation cost is reached.
     *
     * @param stack the incomplete knot being filled (not modified in place)
     * @return the result stack, or {@link ItemStack#EMPTY} if invalid
     */
    public static ItemStack fillIncompleteKnot(ItemStack stack) {
        KnotEntry entry = getKnots().get(stack.getItem());
        if (entry == null)
            return ItemStack.EMPTY;

        float currentProgress = getFilledMana(stack);
        float remaining = entry.resolvedCreationCost - currentProgress;
        if (remaining <= 0)
            return ItemStack.EMPTY;

        float toAdd = Math.min(remaining, Config.manaPerBucket);
        float newProgress = currentProgress + toAdd;

        if (newProgress >= entry.resolvedCreationCost) {
            Item knotItem = entry.cachedFinalKnot;
            if (knotItem == Items.AIR)
                return ItemStack.EMPTY;
            return new ItemStack(knotItem);
        }

        ItemStack result = stack.copy();
        result.set(CMIComponents.FILLED_MANA.get(), newProgress);
        return result;
    }

    // ---- public: query --------------------------------------------------------

    /**
     * Returns the Trickster creation cost for the incomplete knot, or 0 if
     * the stack is not a recognised incomplete knot.
     */
    public static float getCreationCost(ItemStack stack) {
        KnotEntry entry = getKnots().get(stack.getItem());
        return entry != null ? entry.resolvedCreationCost : 0f;
    }

    // ---- private helpers -----------------------------------------------------

    private static float getFilledMana(ItemStack stack) {
        Float value = stack.get(CMIComponents.FILLED_MANA.get());
        return value != null ? value : 0f;
    }

    private static ResourceLocation knot(String path) {
        return ResourceLocation.fromNamespaceAndPath("trickster", path);
    }

    /** Immutable entry with eagerly-resolved creation cost and cached final knot item. */
    private static final class KnotEntry {
        final float resolvedCreationCost;
        final Item cachedFinalKnot;

        KnotEntry(ResourceLocation knotId, float fallbackCreationCost) {
            Item knotItem = BuiltInRegistries.ITEM.get(knotId);
            this.cachedFinalKnot = knotItem != Items.AIR ? knotItem : Items.AIR;
            this.resolvedCreationCost = knotItem != Items.AIR
                    ? TricksterManaAccess.getCreationCost(knotItem, fallbackCreationCost)
                    : fallbackCreationCost;
        }
    }
}
