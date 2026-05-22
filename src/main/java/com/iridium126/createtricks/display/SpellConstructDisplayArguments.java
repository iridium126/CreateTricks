package com.iridium126.createtricks.display;

import java.util.List;

import org.jetbrains.annotations.Nullable;

import com.iridium126.createtricks.trickster.TricksterReflection;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.world.level.block.entity.BlockEntity;

public final class SpellConstructDisplayArguments {
	public static final int MAX_ARGUMENTS = 8;
	public static final String NBT_KEY = "CreateTricksDisplayArguments";

	private SpellConstructDisplayArguments() {}

	public static void storeArgument(BlockEntity be, int index, List<MutableComponent> text) {
		if (index < 0 || index >= MAX_ARGUMENTS)
			return;

		CompoundTag root = be.getPersistentData().getCompound(NBT_KEY);
		root.putString(Integer.toString(index), componentsToString(text));
		be.getPersistentData().put(NBT_KEY, root);
		be.setChanged();
		TricksterReflection.syncExecutors(be);
	}

	@Nullable
	public static String getArgumentString(BlockEntity be, int index) {
		if (index < 0 || index >= MAX_ARGUMENTS)
			return null;

		CompoundTag root = be.getPersistentData().getCompound(NBT_KEY);
		String key = Integer.toString(index);
		if (!root.contains(key))
			return null;

		return root.getString(key);
	}

	private static String componentsToString(List<MutableComponent> text) {
		if (text == null || text.isEmpty())
			return "";

		StringBuilder builder = new StringBuilder();
		for (int i = 0; i < text.size(); i++) {
			if (i > 0)
				builder.append('\n');
			builder.append(text.get(i).getString());
		}
		return builder.toString();
	}
}
