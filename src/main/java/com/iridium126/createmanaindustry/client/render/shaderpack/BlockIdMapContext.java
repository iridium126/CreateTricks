package com.iridium126.createmanaindustry.client.render.shaderpack;

import it.unimi.dsi.fastutil.ints.Int2ObjectLinkedOpenHashMap;
import it.unimi.dsi.fastutil.objects.Object2IntMap;
import net.irisshaders.iris.shaderpack.materialmap.BlockEntry;
import net.irisshaders.iris.shaderpack.materialmap.NamespacedId;

import java.util.List;

/**
 * Mutable view of Iris' parsed ID maps handed to adapters by the IdMap mixin.
 * <p>
 * The item map is replaceable rather than directly editable because Iris
 * stores it wrapped in an unmodifiable view ({@code Object2IntMaps.unmodifiable});
 * adapters build a replacement and assign it back through {@link #setItemIds}.
 */
public final class BlockIdMapContext {

	private final Int2ObjectLinkedOpenHashMap<List<BlockEntry>> blockProperties;
	private Object2IntMap<NamespacedId> itemIds;

	public BlockIdMapContext(Int2ObjectLinkedOpenHashMap<List<BlockEntry>> blockProperties,
			Object2IntMap<NamespacedId> itemIds) {
		this.blockProperties = blockProperties;
		this.itemIds = itemIds;
	}

	public Int2ObjectLinkedOpenHashMap<List<BlockEntry>> blockProperties() {
		return blockProperties;
	}

	public Object2IntMap<NamespacedId> itemIds() {
		return itemIds;
	}

	public void setItemIds(Object2IntMap<NamespacedId> replacement) {
		this.itemIds = replacement;
	}
}