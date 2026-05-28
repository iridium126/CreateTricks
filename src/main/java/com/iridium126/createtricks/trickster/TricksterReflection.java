package com.iridium126.createtricks.trickster;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import org.jetbrains.annotations.Nullable;

import com.iridium126.createtricks.CreateTricks;
import com.iridium126.createtricks.display.SpellConstructDisplayArguments;

import net.minecraft.core.BlockPos;
import net.minecraft.core.component.DataComponentType;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.Container;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;

public final class TricksterReflection {
	static volatile boolean displayInitialized;
	static volatile boolean displayAvailable;
	static volatile boolean chargeInitialized;
	static volatile boolean chargeAvailable;

	static Class<?> spellConstructBlockEntityClass;
	static Class<?> modularSpellConstructBlockEntityClass;
	static Class<?> chargingArrayBlockEntityClass;
	static Class<?> defaultSpellExecutorClass;
	static Class<?> knotItemClass;
	static Class<?> infiniteManaPoolClass;

	static Constructor<?> stringFragmentCtor;
	static Object voidFragmentInstance;

	static Field spellConstructExecutorField;
	static Field modularExecutorsField;
	static Method spellExecutorGetDeepestStateMethod;
	static Method executionStateGetArgumentsMethod;
	static Field blockSpellSourceBlockEntityField;

	static Object manaComponentType;
	static Method manaComponentPoolMethod;
	static Method manaComponentWithMethod;
	static Method manaPoolMakeCloneMethod;
	static Method manaPoolGetManaMethod;
	static Method manaPoolGetMaxManaMethod;
	static Method mutableManaPoolRefillMethod;
	static Method itemStackGetComponentMethod;
	static Method itemStackSetComponentMethod;

	private record ManaAccess(Object component, Object pool) {
	}

	private TricksterReflection() {
	}

	public static boolean isAvailable() {
		return ensureDisplayInit() || ensureChargeInit();
	}

	static synchronized boolean ensureDisplayInit() {
		if (displayInitialized)
			return displayAvailable;
		displayInitialized = true;
		try {
			Class<?> stringFragmentClass = Class.forName("dev.enjarai.trickster.spell.fragment.StringFragment");
			stringFragmentCtor = stringFragmentClass.getConstructor(String.class);

			Class<?> voidFragmentClass = Class.forName("dev.enjarai.trickster.spell.fragment.VoidFragment");
			voidFragmentInstance = voidFragmentClass.getField("INSTANCE").get(null);

			spellConstructBlockEntityClass = Class.forName("dev.enjarai.trickster.block.SpellConstructBlockEntity");
			modularSpellConstructBlockEntityClass = Class
					.forName("dev.enjarai.trickster.block.ModularSpellConstructBlockEntity");
			defaultSpellExecutorClass = Class
					.forName("dev.enjarai.trickster.spell.execution.executor.DefaultSpellExecutor");

			spellConstructExecutorField = spellConstructBlockEntityClass.getField("executor");
			modularExecutorsField = modularSpellConstructBlockEntityClass.getField("executors");

			Class<?> spellExecutorClass = Class.forName("dev.enjarai.trickster.spell.SpellExecutor");
			spellExecutorGetDeepestStateMethod = spellExecutorClass.getMethod("getDeepestState");

			Class<?> executionStateClass = Class.forName("dev.enjarai.trickster.spell.execution.ExecutionState");
			executionStateGetArgumentsMethod = executionStateClass.getMethod("getArguments");

			Class<?> blockSpellSourceClass = Class
					.forName("dev.enjarai.trickster.spell.execution.source.BlockSpellSource");
			blockSpellSourceBlockEntityField = blockSpellSourceClass.getField("blockEntity");

			displayAvailable = true;
		} catch (Throwable t) {
			CreateTricks.LOGGER.warn("Trickster display integration unavailable", t);
			displayAvailable = false;
		}
		return displayAvailable;
	}

	static synchronized boolean ensureChargeInit() {
		if (chargeInitialized)
			return chargeAvailable;
		chargeInitialized = true;
		try {
			knotItemClass = Class.forName("dev.enjarai.trickster.item.KnotItem");
			chargingArrayBlockEntityClass = Class.forName("dev.enjarai.trickster.block.ChargingArrayBlockEntity");
			if (spellConstructBlockEntityClass == null)
				spellConstructBlockEntityClass = Class.forName("dev.enjarai.trickster.block.SpellConstructBlockEntity");
			if (modularSpellConstructBlockEntityClass == null)
				modularSpellConstructBlockEntityClass = Class
						.forName("dev.enjarai.trickster.block.ModularSpellConstructBlockEntity");

			Class<?> modComponentsClass = Class.forName("dev.enjarai.trickster.item.component.ModComponents");
			Class<?> manaComponentClass = Class.forName("dev.enjarai.trickster.item.component.ManaComponent");
			Class<?> manaPoolClass = Class.forName("dev.enjarai.trickster.spell.mana.ManaPool");
			Class<?> mutableManaPoolClass = Class.forName("dev.enjarai.trickster.spell.mana.MutableManaPool");
			infiniteManaPoolClass = Class.forName("dev.enjarai.trickster.spell.mana.InfiniteManaPool");

			manaComponentType = modComponentsClass.getField("MANA").get(null);
			manaComponentPoolMethod = manaComponentClass.getMethod("pool");
			manaComponentWithMethod = manaComponentClass.getMethod("with", manaPoolClass);
			manaPoolMakeCloneMethod = manaPoolClass.getMethod("makeClone", Level.class);
			manaPoolGetManaMethod = manaPoolClass.getMethod("get", Level.class);
			manaPoolGetMaxManaMethod = manaPoolClass.getMethod("getMax", Level.class);
			mutableManaPoolRefillMethod = mutableManaPoolClass.getMethod("refill", float.class, Level.class);

			itemStackGetComponentMethod = ItemStack.class.getMethod("get", DataComponentType.class);
			itemStackSetComponentMethod = ItemStack.class.getMethod("set", DataComponentType.class, Object.class);

			chargeAvailable = true;
		} catch (Throwable t) {
			CreateTricks.LOGGER.warn("Trickster charge integration unavailable", t);
			chargeAvailable = false;
		}
		return chargeAvailable;
	}

	public static float getMana(ItemStack stack) {
		return getMana(stack, null);
	}

	public static float getMana(ItemStack stack, @Nullable Level level) {
		return readManaValue(stack, level, manaPoolGetManaMethod);
	}

	public static float getMaxMana(ItemStack stack, @Nullable Level level) {
		return readManaValue(stack, level, manaPoolGetMaxManaMethod);
	}

	public static boolean isKnotItem(Item item) {
		return ensureChargeInit() && item != null && knotItemClass.isInstance(item);
	}

	public static boolean isKnotStack(ItemStack stack) {
		return stack != null && !stack.isEmpty() && isKnotItem(stack.getItem());
	}

	public static boolean hasInfiniteMana(ItemStack stack) {
		if (!ensureChargeInit() || stack == null || stack.isEmpty())
			return false;

		try {
			ManaAccess access = getManaAccess(stack);
			return access != null && isInfiniteManaPool(access.pool());
		} catch (ReflectiveOperationException e) {
			return false;
		}
	}

	public static float drainMana(ItemStack stack, Level level, float manaAmount) {
		if (!ensureChargeInit() || stack == null || stack.isEmpty() || level == null || manaAmount <= 0)
			return 0;

		try {
			ManaAccess access = getManaAccess(stack);
			if (access == null)
				return 0;
			if (isInfiniteManaPool(access.pool()))
				return manaAmount;
			return Math.max(0, -mutateMana(stack, level, access, -manaAmount));
		} catch (ReflectiveOperationException e) {
			return 0;
		}
	}

	public static float refillMana(ItemStack stack, Level level, float manaAmount) {
		if (!ensureChargeInit() || stack == null || stack.isEmpty() || level == null || manaAmount <= 0)
			return 0;

		try {
			ManaAccess access = getManaAccess(stack);
			if (access == null || isInfiniteManaPool(access.pool()))
				return 0;
			return Math.max(0, mutateMana(stack, level, access, manaAmount));
		} catch (ReflectiveOperationException e) {
			return 0;
		}
	}

	private static float readManaValue(ItemStack stack, @Nullable Level level, Method getterMethod) {
		if (!ensureChargeInit() || stack == null || stack.isEmpty() || level == null)
			return 0;

		try {
			ManaAccess access = getManaAccess(stack);
			if (access == null)
				return 0;
			return (float) getterMethod.invoke(access.pool(), level);
		} catch (ReflectiveOperationException e) {
			return 0;
		}
	}

	private static boolean isInfiniteManaPool(Object pool) {
		return infiniteManaPoolClass != null && infiniteManaPoolClass.isInstance(pool);
	}

	@Nullable
	private static ManaAccess getManaAccess(ItemStack stack) throws ReflectiveOperationException {
		Object component = itemStackGetComponentMethod.invoke(stack, manaComponentType);
		if (component == null)
			return null;

		Object pool = manaComponentPoolMethod.invoke(component);
		if (pool == null)
			return null;

		return new ManaAccess(component, pool);
	}

	private static float mutateMana(ItemStack stack, Level level, ManaAccess access, float manaDelta)
			throws ReflectiveOperationException {
		float before = (float) manaPoolGetManaMethod.invoke(access.pool(), level);
		Object mutablePool = manaPoolMakeCloneMethod.invoke(access.pool(), level);
		mutableManaPoolRefillMethod.invoke(mutablePool, manaDelta, level);
		float after = (float) manaPoolGetManaMethod.invoke(mutablePool, level);
		float changed = after - before;
		if (changed == 0)
			return 0;

		Object updatedComponent = manaComponentWithMethod.invoke(access.component(), mutablePool);
		itemStackSetComponentMethod.invoke(stack, manaComponentType, updatedComponent);
		return changed;
	}

	public static void syncExecutors(BlockEntity be) {
		if (!ensureDisplayInit())
			return;

		try {
			if (spellConstructBlockEntityClass.isInstance(be)) {
				Object executor = spellConstructExecutorField.get(be);
				syncExecutor(executor, be, -1);
			} else if (modularSpellConstructBlockEntityClass.isInstance(be)) {
				@SuppressWarnings("unchecked")
				List<Optional<Object>> executors = (List<Optional<Object>>) modularExecutorsField.get(be);
				for (int slot = 0; slot < executors.size()
						&& slot < SpellConstructDisplayArguments.MODULAR_EXECUTOR_SLOTS; slot++) {
					Optional<Object> optional = executors.get(slot);
					int executorSlot = slot;
					optional.ifPresent(executor -> syncExecutor(executor, be, executorSlot));
				}
			}
		} catch (ReflectiveOperationException e) {
			CreateTricks.LOGGER.error("Failed to sync spell construct display arguments", e);
		}
	}

	public static List<?> buildExecutorArguments(BlockEntity be) {
		if (!ensureDisplayInit())
			return List.of();

		return mergeDisplayArguments(be, -1, List.of());
	}

	public static boolean chargeKnotsAt(ServerLevel level, BlockPos targetPos, float manaAmount) {
		if (!ensureChargeInit() || manaAmount <= 0)
			return false;

		BlockEntity target = level.getBlockEntity(targetPos);
		if (target == null)
			return false;

		return chargeKnotsInBlockEntity(level, target, manaAmount);
	}

	private static boolean chargeKnotsInBlockEntity(ServerLevel level, BlockEntity blockEntity, float manaAmount) {
		try {
			if (spellConstructBlockEntityClass.isInstance(blockEntity)) {
				if (!(blockEntity instanceof Container container))
					return false;
				if (chargeKnotStack(level, container.getItem(0), manaAmount)) {
					markDirtyAndUpdateClients(blockEntity);
					return true;
				}
				return false;
			}

			if (modularSpellConstructBlockEntityClass.isInstance(blockEntity)) {
				if (!(blockEntity instanceof Container container) || container.isEmpty())
					return false;
				if (chargeKnotStack(level, container.getItem(0), manaAmount)) {
					markDirtyAndUpdateClients(blockEntity);
					return true;
				}
				return false;
			}

			if (chargingArrayBlockEntityClass.isInstance(blockEntity)) {
				if (!(blockEntity instanceof Container container))
					return false;

				boolean changed = false;
				int knotCount = 0;
				for (int i = 0; i < container.getContainerSize(); i++) {
					if (isKnotStack(container.getItem(i)))
						knotCount++;
				}
				if (knotCount == 0)
					return false;

				float share = manaAmount / knotCount;
				for (int i = 0; i < container.getContainerSize(); i++) {
					ItemStack stack = container.getItem(i);
					if (isKnotStack(stack) && chargeKnotStack(level, stack, share))
						changed = true;
				}
				if (changed)
					markDirtyAndUpdateClients(blockEntity);
				return changed;
			}
		} catch (ReflectiveOperationException e) {
			CreateTricks.LOGGER.error("Failed to charge trickster knot mana", e);
		}

		return false;
	}

	private static void markDirtyAndUpdateClients(BlockEntity blockEntity) throws ReflectiveOperationException {
		blockEntity.getClass().getMethod("markDirtyAndUpdateClients").invoke(blockEntity);
	}

	private static boolean chargeKnotStack(ServerLevel level, ItemStack stack, float manaAmount)
			throws ReflectiveOperationException {
		if (!isKnotStack(stack) || manaAmount <= 0)
			return false;

		Object component = itemStackGetComponentMethod.invoke(stack, manaComponentType);
		if (component == null)
			return false;

		Object pool = manaComponentPoolMethod.invoke(component);
		Object mutablePool = manaPoolMakeCloneMethod.invoke(pool, level);
		float leftover = (float) mutableManaPoolRefillMethod.invoke(mutablePool, manaAmount, level);
		if (leftover >= manaAmount)
			return false;

		Object updatedComponent = manaComponentWithMethod.invoke(component, mutablePool);
		itemStackSetComponentMethod.invoke(stack, manaComponentType, updatedComponent);
		return true;
	}

	@Nullable
	public static BlockEntity getBlockEntityFromSource(Object source) {
		if (!ensureDisplayInit() || source == null)
			return null;

		try {
			return (BlockEntity) blockSpellSourceBlockEntityField.get(source);
		} catch (ReflectiveOperationException e) {
			return null;
		}
	}

	private static List<Object> mergeDisplayArguments(BlockEntity be, int executorSlot, List<Object> base) {
		ArrayList<Object> merged = new ArrayList<>(base);
		for (int i = 0; i < SpellConstructDisplayArguments.MAX_ARGUMENTS; i++) {
			if (!SpellConstructDisplayArguments.hasStoredArgument(be, executorSlot, i))
				continue;

			Object fragment = getDisplayArgument(be, executorSlot, i);
			if (fragment == null)
				continue;

			while (merged.size() <= i)
				merged.add(voidFragmentInstance);
			merged.set(i, fragment);
		}
		return merged;
	}

	@Nullable
	private static Object getDisplayArgument(BlockEntity be, int executorSlot, int index) {
		if (!ensureDisplayInit())
			return null;

		String value = SpellConstructDisplayArguments.getArgumentString(be, executorSlot, index);
		if (value == null)
			return null;

		try {
			return stringFragmentCtor.newInstance(value);
		} catch (ReflectiveOperationException e) {
			CreateTricks.LOGGER.error("Failed to create StringFragment", e);
			return null;
		}
	}

	private static void syncExecutor(@Nullable Object executor, BlockEntity be, int executorSlot) {
		if (executor == null || !defaultSpellExecutorClass.isInstance(executor))
			return;

		try {
			Object state = spellExecutorGetDeepestStateMethod.invoke(executor);
			@SuppressWarnings("unchecked")
			List<Object> current = (List<Object>) executionStateGetArgumentsMethod.invoke(state);
			List<Object> merged = mergeDisplayArguments(be, executorSlot, current);

			if (current instanceof ArrayList<?> arrayList) {
				@SuppressWarnings("unchecked")
				ArrayList<Object> mutable = (ArrayList<Object>) arrayList;
				mutable.clear();
				mutable.addAll(merged);
			} else {
				Field argumentsField = state.getClass().getDeclaredField("arguments");
				argumentsField.setAccessible(true);
				argumentsField.set(state, merged);
			}
		} catch (ReflectiveOperationException e) {
			CreateTricks.LOGGER.error("Failed to update spell executor arguments", e);
		}
	}
}
