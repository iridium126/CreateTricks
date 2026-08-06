package com.iridium126.createmanaindustry.compat.hexcasting.circle;

import net.minecraft.world.item.ItemStack;

/**
 * Implemented by HexCasting {@code BlockEntitySlate} (via
 * {@code BlockEntitySlateKnotMixin}): gives each slate a single "knot" slot
 * that circle spells draw mana from. Intentionally free of Trickster imports
 * so the mixin stays usable under the hexcasting-only gate.
 */
public interface SlateKnotHolder {

    ItemStack createmanaindustry$getKnot();

    void createmanaindustry$setKnot(ItemStack stack);

    ItemStack createmanaindustry$removeKnot();
}
