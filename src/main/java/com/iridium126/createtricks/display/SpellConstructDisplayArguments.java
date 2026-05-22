package com.iridium126.createtricks.display;

import java.util.List;

import org.jetbrains.annotations.Nullable;

import com.iridium126.createtricks.trickster.TricksterReflection;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.world.level.block.entity.BlockEntity;

public final class SpellConstructDisplayArguments {
	public static final int MAX_ARGUMENTS = 8;
	public static final int MODULAR_EXECUTOR_SLOTS = 4;
	public static final String NBT_KEY = "CreateTricksDisplayArguments";
	private static final String NBT_SLOTS = "slots";
	private SpellConstructDisplayArguments() {}

	public static int decodeModularExecutorSlot(int line) {
		return line / MAX_ARGUMENTS;
	}

	public static int decodeModularArgumentIndex(int line) {
		return line % MAX_ARGUMENTS;
	}

	public static void storeArgument(BlockEntity be, int line, List<MutableComponent> text) {
		storeSpellConstructArgument(be, line, text);
	}

	public static boolean hasStoredArgument(BlockEntity be, int argumentIndex) {
		return hasStoredArgument(be, -1, argumentIndex);
	}

	public static boolean hasStoredArgument(BlockEntity be, int executorSlot, int argumentIndex) {
		if (argumentIndex < 0 || argumentIndex >= MAX_ARGUMENTS)
			return false;

		if (executorSlot < 0)
			return getSpellConstructSlotTag(be).contains(Integer.toString(argumentIndex));

		if (executorSlot >= MODULAR_EXECUTOR_SLOTS)
			return false;

		return getModularSlotTag(be, executorSlot).contains(Integer.toString(argumentIndex));
	}

	@Nullable
	public static String getArgumentString(BlockEntity be, int argumentIndex) {
		return getArgumentString(be, -1, argumentIndex);
	}

	@Nullable
	public static String getArgumentString(BlockEntity be, int executorSlot, int argumentIndex) {
		if (argumentIndex < 0 || argumentIndex >= MAX_ARGUMENTS)
			return null;

		CompoundTag slotTag = executorSlot < 0 ? getSpellConstructSlotTag(be) : getModularSlotTag(be, executorSlot);
		String key = Integer.toString(argumentIndex);
		if (!slotTag.contains(key))
			return null;

		return slotTag.getString(key);
	}

	private static void storeSpellConstructArgument(BlockEntity be, int argumentIndex, List<MutableComponent> text) {
		if (argumentIndex < 0 || argumentIndex >= MAX_ARGUMENTS)
			return;

		CompoundTag slotTag = getSpellConstructSlotTag(be);
		slotTag.putString(Integer.toString(argumentIndex), componentsToString(text));
		be.getPersistentData().put(NBT_KEY, slotTag);
		be.setChanged();
		TricksterReflection.syncExecutors(be);
	}

	public static void storeModularArgument(BlockEntity be, int executorSlot, int argumentIndex, List<MutableComponent> text) {
		if (executorSlot < 0 || executorSlot >= MODULAR_EXECUTOR_SLOTS)
			return;
		if (argumentIndex < 0 || argumentIndex >= MAX_ARGUMENTS)
			return;

		CompoundTag root = be.getPersistentData().getCompound(NBT_KEY);
		CompoundTag slots = root.getCompound(NBT_SLOTS);
		CompoundTag slotTag = slots.getCompound(Integer.toString(executorSlot));
		slotTag.putString(Integer.toString(argumentIndex), componentsToString(text));
		slots.put(Integer.toString(executorSlot), slotTag);
		root.put(NBT_SLOTS, slots);
		be.getPersistentData().put(NBT_KEY, root);
		be.setChanged();
		TricksterReflection.syncExecutors(be);
	}

	private static CompoundTag getSpellConstructSlotTag(BlockEntity be) {
		return be.getPersistentData().getCompound(NBT_KEY);
	}

	private static CompoundTag getModularSlotTag(BlockEntity be, int executorSlot) {
		return be.getPersistentData()
				.getCompound(NBT_KEY)
				.getCompound(NBT_SLOTS)
				.getCompound(Integer.toString(executorSlot));
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
