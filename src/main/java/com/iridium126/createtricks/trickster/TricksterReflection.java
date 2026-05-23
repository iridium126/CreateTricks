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
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.entity.BlockEntity;

public final class TricksterReflection {
	private static final String SPELL_CONSTRUCT_BE = "dev.enjarai.trickster.block.SpellConstructBlockEntity";
	private static final String MODULAR_SPELL_CONSTRUCT_BE = "dev.enjarai.trickster.block.ModularSpellConstructBlockEntity";
	private static final String CHARGING_ARRAY_BE = "dev.enjarai.trickster.block.ChargingArrayBlockEntity";
	private static final String KNOT_ITEM = "dev.enjarai.trickster.item.KnotItem";
	private static final String MANA_COMPONENT = "dev.enjarai.trickster.item.component.ManaComponent";
	private static final String MOD_COMPONENTS = "dev.enjarai.trickster.item.component.ModComponents";
	private static final String MUTABLE_MANA_POOL = "dev.enjarai.trickster.spell.mana.MutableManaPool";
	private static final String DEFAULT_SPELL_EXECUTOR = "dev.enjarai.trickster.spell.execution.executor.DefaultSpellExecutor";
	private static final String STRING_FRAGMENT = "dev.enjarai.trickster.spell.fragment.StringFragment";
	private static final String VOID_FRAGMENT = "dev.enjarai.trickster.spell.fragment.VoidFragment";
	private static final String EXECUTION_STATE = "dev.enjarai.trickster.spell.execution.ExecutionState";
	private static final String BLOCK_SPELL_SOURCE = "dev.enjarai.trickster.spell.execution.source.BlockSpellSource";

	private static volatile boolean initialized;
	private static volatile boolean available;

	private static Class<?> stringFragmentClass;
	private static Object voidFragmentInstance;
	private static Constructor<?> stringFragmentConstructor;
	private static Field spellConstructExecutorField;
	private static Field modularExecutorsField;
	private static Field defaultExecutorStateField;
	private static Method executionStateGetArguments;
	private static Field blockSpellSourceBlockEntityField;

	private static Class<?> knotItemClass;
	private static Object manaComponentType;
	private static Method manaComponentPoolMethod;
	private static Method manaComponentWithMethod;
	private static Method mutableManaPoolMakeCloneMethod;
	private static Method mutableManaPoolRefillMethod;
	private static Method itemStackGetComponentMethod;
	private static Method itemStackSetComponentMethod;
	private static Method markDirtyAndUpdateClientsMethod;
	private static Field spellConstructStackField;
	private static Field modularInventoryField;
	private static Field chargingArrayInventoryField;

	private static void resolveItemStackComponentAccess() throws ReflectiveOperationException {
		Class<?> componentTypeClass = Class.forName(MOD_COMPONENTS).getField("MANA").getType();
		for (Method method : ItemStack.class.getMethods()) {
			if ("get".equals(method.getName()) && method.getParameterCount() == 1
					&& componentTypeClass.isAssignableFrom(method.getParameterTypes()[0])) {
				itemStackGetComponentMethod = method;
			}
			if ("set".equals(method.getName()) && method.getParameterCount() == 2
					&& componentTypeClass.isAssignableFrom(method.getParameterTypes()[0])) {
				itemStackSetComponentMethod = method;
			}
		}
		if (itemStackGetComponentMethod == null || itemStackSetComponentMethod == null)
			throw new NoSuchMethodException("Could not resolve ItemStack component accessors");
	}

	private TricksterReflection() {}

	private static Class<?> resolveWorldClass() throws ClassNotFoundException {
		try {
			return Class.forName("net.minecraft.world.level.Level");
		} catch (ClassNotFoundException ignored) {
			return Class.forName("net.minecraft.world.World");
		}
	}

	public static boolean isAvailable() {
		ensureInitialized();
		return available;
	}

	public static void syncExecutors(BlockEntity be) {
		if (!isAvailable())
			return;

		try {
			if (SPELL_CONSTRUCT_BE.equals(be.getClass().getName())) {
				Object executor = spellConstructExecutorField.get(be);
				syncExecutor(executor, be, -1);
			} else if (MODULAR_SPELL_CONSTRUCT_BE.equals(be.getClass().getName())) {
				@SuppressWarnings("unchecked")
				List<Optional<Object>> executors = (List<Optional<Object>>) modularExecutorsField.get(be);
				for (int slot = 0; slot < executors.size() && slot < SpellConstructDisplayArguments.MODULAR_EXECUTOR_SLOTS; slot++) {
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
		if (!isAvailable())
			return List.of();

		return mergeDisplayArguments(be, -1, List.of());
	}

	public static boolean chargeKnotsAbove(ServerLevel level, BlockPos converterPos, float manaAmount) {
		if (!isAvailable() || manaAmount <= 0)
			return false;

		BlockEntity target = level.getBlockEntity(converterPos.above());
		if (target == null)
			return false;

		return chargeKnotsInBlockEntity(level, target, manaAmount);
	}

	private static boolean chargeKnotsInBlockEntity(ServerLevel level, BlockEntity blockEntity, float manaAmount) {
		String className = blockEntity.getClass().getName();
		try {
			if (SPELL_CONSTRUCT_BE.equals(className)) {
				ItemStack stack = (ItemStack) spellConstructStackField.get(blockEntity);
				if (chargeKnotStack(level, stack, manaAmount)) {
					markDirtyAndUpdateClientsMethod.invoke(blockEntity);
					return true;
				}
				return false;
			}

			if (MODULAR_SPELL_CONSTRUCT_BE.equals(className)) {
				@SuppressWarnings("unchecked")
				List<ItemStack> inventory = (List<ItemStack>) modularInventoryField.get(blockEntity);
				if (inventory.isEmpty())
					return false;
				if (chargeKnotStack(level, inventory.get(0), manaAmount)) {
					markDirtyAndUpdateClientsMethod.invoke(blockEntity);
					return true;
				}
				return false;
			}

			if (CHARGING_ARRAY_BE.equals(className)) {
				@SuppressWarnings("unchecked")
				List<ItemStack> inventory = (List<ItemStack>) chargingArrayInventoryField.get(blockEntity);
				boolean changed = false;
				int knotCount = 0;
				for (ItemStack stack : inventory) {
					if (isKnotStack(stack))
						knotCount++;
				}
				if (knotCount == 0)
					return false;

				float share = manaAmount / knotCount;
				for (int i = 0; i < inventory.size(); i++) {
					ItemStack stack = inventory.get(i);
					if (isKnotStack(stack) && chargeKnotStack(level, stack, share))
						changed = true;
				}
				if (changed)
					markDirtyAndUpdateClientsMethod.invoke(blockEntity);
				return changed;
			}
		} catch (ReflectiveOperationException e) {
			CreateTricks.LOGGER.error("Failed to charge trickster knot mana", e);
		}

		return false;
	}

	private static boolean isKnotStack(ItemStack stack) {
		return stack != null && !stack.isEmpty() && knotItemClass.isInstance(stack.getItem());
	}

	private static boolean chargeKnotStack(ServerLevel level, ItemStack stack, float manaAmount) throws ReflectiveOperationException {
		if (!isKnotStack(stack) || manaAmount <= 0)
			return false;

		Object component = itemStackGetComponentMethod.invoke(stack, manaComponentType);
		if (component == null)
			return false;

		Object pool = manaComponentPoolMethod.invoke(component);
		Object mutablePool = mutableManaPoolMakeCloneMethod.invoke(pool, level);
		float leftover = (float) mutableManaPoolRefillMethod.invoke(mutablePool, manaAmount, level);
		if (leftover >= manaAmount)
			return false;

		Object updatedComponent = manaComponentWithMethod.invoke(component, mutablePool);
		itemStackSetComponentMethod.invoke(stack, manaComponentType, updatedComponent);
		return true;
	}

	@Nullable
	public static BlockEntity getBlockEntityFromSource(Object source) {
		if (!isAvailable() || source == null)
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
		if (!isAvailable())
			return null;

		String value = SpellConstructDisplayArguments.getArgumentString(be, executorSlot, index);
		if (value == null)
			return null;

		try {
			return stringFragmentConstructor.newInstance(value);
		} catch (ReflectiveOperationException e) {
			CreateTricks.LOGGER.error("Failed to create StringFragment", e);
			return null;
		}
	}

	private static void syncExecutor(@Nullable Object executor, BlockEntity be, int executorSlot) {
		if (executor == null || !DEFAULT_SPELL_EXECUTOR.equals(executor.getClass().getName()))
			return;

		try {
			Object state = defaultExecutorStateField.get(executor);
			@SuppressWarnings("unchecked")
			List<Object> current = (List<Object>) executionStateGetArguments.invoke(state);
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

	private static void ensureInitialized() {
		if (initialized)
			return;

		synchronized (TricksterReflection.class) {
			if (initialized)
				return;
			initialized = true;
			try {
				stringFragmentClass = Class.forName(STRING_FRAGMENT);
				Class<?> voidFragmentClass = Class.forName(VOID_FRAGMENT);
				voidFragmentInstance = voidFragmentClass.getField("INSTANCE").get(null);
				stringFragmentConstructor = stringFragmentClass.getConstructor(String.class);

				spellConstructExecutorField = Class.forName(SPELL_CONSTRUCT_BE).getField("executor");
				modularExecutorsField = Class.forName(MODULAR_SPELL_CONSTRUCT_BE).getField("executors");
				defaultExecutorStateField = Class.forName(DEFAULT_SPELL_EXECUTOR).getDeclaredField("state");
				defaultExecutorStateField.setAccessible(true);
				executionStateGetArguments = Class.forName(EXECUTION_STATE).getMethod("getArguments");
				blockSpellSourceBlockEntityField = Class.forName(BLOCK_SPELL_SOURCE).getField("blockEntity");

				knotItemClass = Class.forName(KNOT_ITEM);
				Class<?> manaComponentClass = Class.forName(MANA_COMPONENT);
				Class<?> modComponentsClass = Class.forName(MOD_COMPONENTS);
				Class<?> mutableManaPoolClass = Class.forName(MUTABLE_MANA_POOL);
				Class<?> worldClass = resolveWorldClass();
				manaComponentType = modComponentsClass.getField("MANA").get(null);
				manaComponentPoolMethod = manaComponentClass.getMethod("pool");
				manaComponentWithMethod = manaComponentClass.getMethod("with", mutableManaPoolClass);
				mutableManaPoolMakeCloneMethod = mutableManaPoolClass.getMethod("makeClone", worldClass);
				mutableManaPoolRefillMethod = mutableManaPoolClass.getMethod("refill", float.class, worldClass);
				resolveItemStackComponentAccess();
				spellConstructStackField = Class.forName(SPELL_CONSTRUCT_BE).getField("stack");
				modularInventoryField = Class.forName(MODULAR_SPELL_CONSTRUCT_BE).getDeclaredField("inventory");
				modularInventoryField.setAccessible(true);
				chargingArrayInventoryField = Class.forName(CHARGING_ARRAY_BE).getDeclaredField("inventory");
				chargingArrayInventoryField.setAccessible(true);
				markDirtyAndUpdateClientsMethod = Class.forName(SPELL_CONSTRUCT_BE).getMethod("markDirtyAndUpdateClients");

				available = true;
			} catch (ReflectiveOperationException e) {
				CreateTricks.LOGGER.warn("Trickster classes were not found; display link spell construct integration is disabled");
				available = false;
			}
		}
	}
}
