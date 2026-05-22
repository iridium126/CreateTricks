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

import net.minecraft.world.level.block.entity.BlockEntity;

public final class TricksterReflection {
	private static final String SPELL_CONSTRUCT_BE = "dev.enjarai.trickster.block.SpellConstructBlockEntity";
	private static final String MODULAR_SPELL_CONSTRUCT_BE = "dev.enjarai.trickster.block.ModularSpellConstructBlockEntity";
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

	private TricksterReflection() {}

	public static boolean isAvailable() {
		ensureInitialized();
		return available;
	}

	public static void syncExecutors(BlockEntity be) {
		if (!isAvailable())
			return;

		List<Object> arguments = buildArgumentObjects(be);
		try {
			if (SPELL_CONSTRUCT_BE.equals(be.getClass().getName())) {
				Object executor = spellConstructExecutorField.get(be);
				syncExecutor(executor, arguments);
			} else if (MODULAR_SPELL_CONSTRUCT_BE.equals(be.getClass().getName())) {
				@SuppressWarnings("unchecked")
				List<Optional<Object>> executors = (List<Optional<Object>>) modularExecutorsField.get(be);
				for (Optional<Object> optional : executors)
					optional.ifPresent(executor -> syncExecutor(executor, arguments));
			}
		} catch (ReflectiveOperationException e) {
			CreateTricks.LOGGER.error("Failed to sync spell construct display arguments", e);
		}
	}

	@Nullable
	public static Object getDisplayArgument(BlockEntity be, int index) {
		if (!isAvailable())
			return null;

		String value = SpellConstructDisplayArguments.getArgumentString(be, index);
		if (value == null)
			return null;

		try {
			return stringFragmentConstructor.newInstance(value);
		} catch (ReflectiveOperationException e) {
			CreateTricks.LOGGER.error("Failed to create StringFragment", e);
			return null;
		}
	}

	public static List<?> buildExecutorArguments(BlockEntity be) {
		if (!isAvailable())
			return List.of();

		return buildArgumentObjects(be);
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

	private static List<Object> buildArgumentObjects(BlockEntity be) {
		List<Object> arguments = new ArrayList<>(SpellConstructDisplayArguments.MAX_ARGUMENTS);
		for (int i = 0; i < SpellConstructDisplayArguments.MAX_ARGUMENTS; i++) {
			Object fragment = getDisplayArgument(be, i);
			arguments.add(fragment != null ? fragment : voidFragmentInstance);
		}
		trimTrailingVoid(arguments);
		return arguments;
	}

	private static void syncExecutor(@Nullable Object executor, List<Object> arguments) {
		if (executor == null || !DEFAULT_SPELL_EXECUTOR.equals(executor.getClass().getName()))
			return;

		try {
			Object state = defaultExecutorStateField.get(executor);
			@SuppressWarnings("unchecked")
			List<Object> current = (List<Object>) executionStateGetArguments.invoke(state);
			if (current instanceof ArrayList<?> arrayList) {
				@SuppressWarnings("unchecked")
				ArrayList<Object> mutable = (ArrayList<Object>) arrayList;
				mutable.clear();
				mutable.addAll(arguments);
			} else {
				Field argumentsField = state.getClass().getDeclaredField("arguments");
				argumentsField.setAccessible(true);
				argumentsField.set(state, new ArrayList<>(arguments));
			}
		} catch (ReflectiveOperationException e) {
			CreateTricks.LOGGER.error("Failed to update spell executor arguments", e);
		}
	}

	private static void trimTrailingVoid(List<Object> arguments) {
		int size = arguments.size();
		while (size > 0 && voidFragmentInstance.equals(arguments.get(size - 1)))
			size--;
		if (size < arguments.size())
			arguments.subList(size, arguments.size()).clear();
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

				available = true;
			} catch (ReflectiveOperationException e) {
				CreateTricks.LOGGER.warn("Trickster classes were not found; display link spell construct integration is disabled");
				available = false;
			}
		}
	}
}
