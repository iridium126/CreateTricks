package com.iridium126.createmanaindustry.compat.trickster;

import dev.enjarai.trickster.block.ChargingArrayBlockEntity;
import dev.enjarai.trickster.block.ModularSpellConstructBlockEntity;
import dev.enjarai.trickster.block.SpellConstructBlockEntity;
import dev.enjarai.trickster.item.KnotItem;

import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.entity.BlockEntity;

/**
 * Utility methods for identifying Trickster knot items and block entities.
 */
public final class TricksterKnotUtils {

    private TricksterKnotUtils() {}

    public static boolean isKnotItem(Item item) {
        return item instanceof KnotItem;
    }

    public static boolean isKnotStack(ItemStack stack) {
        return stack != null && !stack.isEmpty() && stack.getItem() instanceof KnotItem;
    }

    public static boolean isTricksterKnotBlockEntity(BlockEntity be) {
        return be instanceof ChargingArrayBlockEntity
                || be instanceof SpellConstructBlockEntity
                || be instanceof ModularSpellConstructBlockEntity;
    }
}
