package com.iridium126.createmanaindustry.compat.trickster;

import org.jetbrains.annotations.Nullable;

import com.iridium126.createmanaindustry.CreateManaIndustry;

import dev.enjarai.trickster.block.ChargingArrayBlockEntity;
import dev.enjarai.trickster.block.ModularSpellConstructBlockEntity;
import dev.enjarai.trickster.block.SpellConstructBlockEntity;
import dev.enjarai.trickster.item.KnotItem;
import dev.enjarai.trickster.item.component.ManaComponent;
import dev.enjarai.trickster.item.component.ModComponents;
import dev.enjarai.trickster.spell.SpellContext;
import dev.enjarai.trickster.spell.mana.InfiniteManaPool;
import dev.enjarai.trickster.spell.mana.ManaPool;
import dev.enjarai.trickster.spell.mana.MutableManaPool;
import dev.enjarai.trickster.spell.mana.storage.ManaVariant;
import dev.enjarai.trickster.spell.mana.type.Manae;
import dev.enjarai.trickster.spell.trick.Trick;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;

/**
 * Read / write / transfer mana on Trickster knot items and block entities.
 * All mana operations use direct Trickster API calls — no reflection.
 */
public final class TricksterManaAccess {

    private static final ManaVariant TRADITIONAL_VARIANT = ManaVariant.of(Manae.TRADITIONAL);

    private TricksterManaAccess() {}

    // ---- public: query -------------------------------------------------------

    /** Returns the current liquid-mana amount in the knot stack (0 if unavailable). */
    public static float getMana(ItemStack stack, @Nullable Level level) {
        return readManaValue(stack, level, false);
    }

    /** Returns the maximum liquid-mana capacity of the knot stack (0 if unavailable). */
    public static float getMaxMana(ItemStack stack, @Nullable Level level) {
        return readManaValue(stack, level, true);
    }

    /** Returns {@code true} when the stack has an infinite mana pool. */
    public static boolean hasInfiniteMana(ItemStack stack) {
        if (!CreateManaIndustry.TRICKSTER_ACTIVE || stack == null || stack.isEmpty())
            return false;

        ManaComponent comp = stack.get(ModComponents.MANA);
        return comp != null && comp.pool() instanceof InfiniteManaPool;
    }

    // ---- public: transfer ----------------------------------------------------

    /** Drain up to {@code manaAmount} traditional mana, returning the amount actually removed. */
    public static float drainMana(ItemStack stack, Level level, float manaAmount) {
        if (!CreateManaIndustry.TRICKSTER_ACTIVE || stack == null || stack.isEmpty() || level == null || manaAmount <= 0)
            return 0;

        ManaComponent comp = stack.get(ModComponents.MANA);
        if (comp == null)
            return 0;
        ManaPool pool = comp.pool();
        if (pool instanceof InfiniteManaPool)
            return manaAmount;

        if (!pool.getVariant(level).isOf(Manae.TRADITIONAL))
            return 0;

        long requested = toTricksterMana(manaAmount);
        if (requested <= 0)
            return 0;

        MutableManaPool mutablePool = pool.makeClone(level);
        long consumed = requested - mutablePool.use(TRADITIONAL_VARIANT, requested, level);
        if (consumed <= 0)
            return 0;

        stack.set(ModComponents.MANA, comp.with(mutablePool));
        return fromTricksterMana(consumed);
    }

    /** Refill up to {@code manaAmount} traditional mana, returning the amount actually inserted. */
    public static float refillMana(ItemStack stack, Level level, float manaAmount) {
        if (!CreateManaIndustry.TRICKSTER_ACTIVE || stack == null || stack.isEmpty() || level == null || manaAmount <= 0)
            return 0;

        ManaComponent comp = stack.get(ModComponents.MANA);
        if (comp == null)
            return 0;
        ManaPool pool = comp.pool();
        if (pool instanceof InfiniteManaPool)
            return 0;

        if (!canAcceptLiquidMana(pool.getVariant(level)))
            return 0;

        long requested = toTricksterMana(manaAmount);
        if (requested <= 0)
            return 0;

        MutableManaPool mutablePool = pool.makeClone(level);
        long inserted = requested - mutablePool.refill(TRADITIONAL_VARIANT, requested, level);
        if (inserted <= 0)
            return 0;

        stack.set(ModComponents.MANA, comp.with(mutablePool));
        return fromTricksterMana(inserted);
    }

    // ---- public: charging block entities -------------------------------------

    /**
     * Charge all knot items in the Trickster block entity at {@code targetPos}
     * with {@code manaAmount} traditional mana.
     * @return {@code true} if at least one knot received mana.
     */
    public static boolean chargeKnotsAt(ServerLevel level, BlockPos targetPos, float manaAmount) {
        if (!CreateManaIndustry.TRICKSTER_ACTIVE || manaAmount <= 0)
            return false;

        BlockEntity target = level.getBlockEntity(targetPos);
        return target != null && chargeKnotsInBlockEntity(level, target, manaAmount);
    }

    // ---- public: trickster spell mana ----------------------------------------

    /** Consume traditional mana from a spell context. */
    public static void useTraditionalMana(SpellContext ctx, Trick<?> trick, double amount) {
        if (CreateManaIndustry.TRICKSTER_ACTIVE)
            ctx.useScaledMana(trick, amount);
    }

    // ---- public: knot crafting -----------------------------------------------

    /** Returns the Trickster-defined creation cost of a knot item, or {@code fallback}. */
    public static float getCreationCost(Item item, float fallback) {
        if (!CreateManaIndustry.TRICKSTER_ACTIVE || !(item instanceof KnotItem knotItem))
            return fallback;
        return knotItem.getCreationCost();
    }

    /** Transfer mana / properties from a knot input to the output stack (e.g. after pressing). */
    public static ItemStack applyKnotTransfer(Level level, ItemStack input, ItemStack output) {
        if (!CreateManaIndustry.TRICKSTER_ACTIVE || !TricksterKnotUtils.isKnotStack(input))
            return output;

        if (!(input.getItem() instanceof KnotItem knotItem))
            return output;

        KnotItem crackedVersion = knotItem.getCrackedVersion();
        return crackedVersion == null ? output : knotItem.transferPropertiesToCracked(level, input, output);
    }

    // ---- private helpers -----------------------------------------------------

    private static float readManaValue(ItemStack stack, @Nullable Level level, boolean max) {
        if (!CreateManaIndustry.TRICKSTER_ACTIVE || stack == null || stack.isEmpty() || level == null)
            return 0;

        ManaComponent comp = stack.get(ModComponents.MANA);
        if (comp == null)
            return 0;

        ManaPool pool = comp.pool();
        ManaVariant variant = pool.getVariant(level);

        if (max) {
            return canAcceptLiquidMana(variant) ? fromTricksterMana(pool.getMax(level)) : 0;
        }
        return variant.isOf(Manae.TRADITIONAL) ? fromTricksterMana(pool.get(level)) : 0;
    }

    private static boolean canAcceptLiquidMana(ManaVariant variant) {
        return variant.isBlank() || variant.isOf(Manae.TRADITIONAL);
    }

    private static long toTricksterMana(float manaAmount) {
        return Math.max(0L, Math.round(manaAmount * ManaPool.MANA_SCALE));
    }

    private static float fromTricksterMana(long manaAmount) {
        return manaAmount <= 0 ? 0 : manaAmount / (float) ManaPool.MANA_SCALE;
    }

    private static boolean chargeKnotsInBlockEntity(ServerLevel level, BlockEntity be, float manaAmount) {
        return switch (be) {
            case SpellConstructBlockEntity sc -> {
                if (chargeKnotStack(level, sc.getItem(0), manaAmount)) {
                    sc.markDirtyAndUpdateClients();
                    yield true;
                }
                yield false;
            }
            case ModularSpellConstructBlockEntity msc -> {
                if (msc.isEmpty())
                    yield false;
                if (chargeKnotStack(level, msc.getItem(0), manaAmount)) {
                    msc.markDirtyAndUpdateClients();
                    yield true;
                }
                yield false;
            }
            case ChargingArrayBlockEntity ca -> {
                int knotCount = 0;
                int size = ca.getContainerSize();
                for (int i = 0; i < size; i++) {
                    if (TricksterKnotUtils.isKnotStack(ca.getItem(i)))
                        knotCount++;
                }
                if (knotCount == 0)
                    yield false;

                float share = manaAmount / knotCount;
                boolean changed = false;
                for (int i = 0; i < size; i++) {
                    ItemStack stack = ca.getItem(i);
                    if (TricksterKnotUtils.isKnotStack(stack) && chargeKnotStack(level, stack, share))
                        changed = true;
                }
                if (changed)
                    ca.markDirtyAndUpdateClients();
                yield changed;
            }
            default -> false;
        };
    }

    private static boolean chargeKnotStack(ServerLevel level, ItemStack stack, float manaAmount) {
        if (!TricksterKnotUtils.isKnotStack(stack) || manaAmount <= 0)
            return false;

        ManaComponent comp = stack.get(ModComponents.MANA);
        if (comp == null)
            return false;

        ManaPool pool = comp.pool();
        if (!canAcceptLiquidMana(pool.getVariant(level)))
            return false;

        long requested = toTricksterMana(manaAmount);
        if (requested <= 0)
            return false;

        MutableManaPool mutablePool = pool.makeClone(level);
        long leftover = mutablePool.refill(TRADITIONAL_VARIANT, requested, level);
        if (leftover >= requested)
            return false;

        stack.set(ModComponents.MANA, comp.with(mutablePool));
        return true;
    }
}
