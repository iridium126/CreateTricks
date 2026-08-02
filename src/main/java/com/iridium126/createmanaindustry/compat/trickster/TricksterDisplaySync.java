package com.iridium126.createmanaindustry.compat.trickster;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import com.iridium126.createmanaindustry.CreateManaIndustry;
import com.iridium126.createmanaindustry.content.display.SpellConstructDisplayArguments;

import dev.enjarai.trickster.block.ModularSpellConstructBlockEntity;
import dev.enjarai.trickster.block.SpellConstructBlockEntity;
import dev.enjarai.trickster.spell.SpellExecutor;
import dev.enjarai.trickster.spell.execution.ExecutionState;
import dev.enjarai.trickster.spell.execution.executor.DefaultSpellExecutor;
import dev.enjarai.trickster.spell.Fragment;
import dev.enjarai.trickster.spell.fragment.StringFragment;
import dev.enjarai.trickster.spell.fragment.VoidFragment;

import net.minecraft.world.level.block.entity.BlockEntity;

/**
 * Synchronizes DisplayLink argument overrides into live Trickster spell executors
 * so that text written by Create's Display Link appears as spell arguments inside
 * Spell Construct and Modular Spell Construct block entities.
 */
public final class TricksterDisplaySync {

    /** Cached reflective access to ExecutionState.arguments (only used as fallback). */
    private static volatile Field argumentsField;
    private static volatile boolean argumentsFieldTried;

    private TricksterDisplaySync() {}

    // ---- public API ----------------------------------------------------------

    /**
     * Synchronize stored display arguments into every executor on the given
     * block entity. Safe to call on any BE — silently returns if the BE is
     * not a Trickster spell construct.
     */
    public static void syncExecutors(BlockEntity be) {
        if (!CreateManaIndustry.TRICKSTER_ACTIVE)
            return;

        switch (be) {
            case SpellConstructBlockEntity sc ->
                syncExecutor(sc.executor, be, -1);
            case ModularSpellConstructBlockEntity msc -> {
                List<Optional<SpellExecutor>> executors = msc.executors;
                int limit = Math.min(executors.size(), SpellConstructDisplayArguments.MODULAR_EXECUTOR_SLOTS);
                for (int slot = 0; slot < limit; slot++) {
                    int executorSlot = slot;
                    executors.get(slot).ifPresent(executor -> syncExecutor(executor, be, executorSlot));
                }
            }
            default -> {}
        }
    }

    // ---- internals -----------------------------------------------------------

    private static void syncExecutor(SpellExecutor executor, BlockEntity be, int executorSlot) {
        if (!(executor instanceof DefaultSpellExecutor))
            return;

        ExecutionState state = executor.getDeepestState();
        List<Fragment> current = state.getArguments();
        List<Fragment> merged = mergeDisplayArguments(be, executorSlot, current);

        if (merged == current)
            return; // no changes — short-circuit

        try {
            current.clear();
            current.addAll(merged);
        } catch (UnsupportedOperationException e) {
            setArgumentsViaReflection(state, merged);
        }
    }

    private static List<Fragment> mergeDisplayArguments(BlockEntity be, int executorSlot, List<Fragment> base) {
        // Lazy-copy: only allocate when at least one override exists
        ArrayList<Fragment> merged = null;

        for (int i = 0; i < SpellConstructDisplayArguments.MAX_ARGUMENTS; i++) {
            String value = SpellConstructDisplayArguments.getArgumentString(be, executorSlot, i);
            if (value == null)
                continue;

            Fragment fragment = new StringFragment(value);

            if (merged == null) {
                merged = new ArrayList<>(base);
                // Pad with VoidFragment up to index
                while (merged.size() <= i)
                    merged.add(VoidFragment.INSTANCE);
            }
            merged.set(i, fragment);
        }
        return merged != null ? merged : base;
    }

    private static void setArgumentsViaReflection(ExecutionState state, List<Fragment> merged) {
        Field field = argumentsField;
        if (field == null && !argumentsFieldTried) {
            argumentsFieldTried = true;
            try {
                field = ExecutionState.class.getDeclaredField("arguments");
                field.setAccessible(true);
                argumentsField = field;
            } catch (ReflectiveOperationException e) {
                CreateManaIndustry.LOGGER.error("Failed to access ExecutionState.arguments field", e);
                return;
            }
        }
        if (field != null) {
            try {
                field.set(state, new ArrayList<>(merged));
            } catch (ReflectiveOperationException e) {
                CreateManaIndustry.LOGGER.error("Failed to update spell executor arguments", e);
            }
        }
    }
}
