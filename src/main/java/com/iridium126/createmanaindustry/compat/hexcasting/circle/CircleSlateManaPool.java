package com.iridium126.createmanaindustry.compat.hexcasting.circle;

import java.util.ArrayList;
import java.util.List;

import com.iridium126.createmanaindustry.CreateManaIndustry;

import dev.enjarai.trickster.spell.mana.ManaPool;
import dev.enjarai.trickster.spell.mana.ManaPoolType;
import dev.enjarai.trickster.spell.mana.MutableManaPool;
import dev.enjarai.trickster.spell.mana.SimpleManaPool;
import dev.enjarai.trickster.spell.mana.storage.ManaStorage;
import dev.enjarai.trickster.spell.mana.storage.ManaVariant;
import dev.enjarai.trickster.spell.mana.type.Manae;
import io.wispforest.endec.StructEndec;
import io.wispforest.endec.impl.StructEndecBuilder;
import net.fabricmc.fabric.api.transfer.v1.context.ContainerItemContext;
import net.fabricmc.fabric.api.transfer.v1.item.InventoryStorage;
import net.fabricmc.fabric.api.transfer.v1.storage.Storage;
import net.fabricmc.fabric.api.transfer.v1.storage.base.SingleSlotStorage;
import net.fabricmc.fabric.api.transfer.v1.transaction.Transaction;
import net.minecraft.core.Registry;
import net.minecraft.world.level.Level;

/**
 * A {@link MutableManaPool} backed by the knot items slotted into a hex
 * circle's reached slates ({@link SlateKnotInventory}). Injected into the
 * {@code ExecutionState} pool override so a {@code execute_trick} cast in a
 * circle draws mana from the slate knots — on the first run AND on
 * HANDED_OFF continuation ticks (the pool lives in the state).
 * <p>
 * A {@code ManaPoolType} is registered so the pool override can be
 * serialized into player saves ({@code ExecutionState.ENDEC}); decoding
 * yields a detached snapshot pool (mana gating continues on the snapshot).
 */
public class CircleSlateManaPool implements MutableManaPool {

    public static final StructEndec<CircleSlateManaPool> ENDEC = StructEndecBuilder.of(
            ManaPool.LONG_OR_OLD_FLOAT_ENDEC.fieldOf("mana", pool -> pool.mana),
            ManaPool.LONG_OR_OLD_FLOAT_ENDEC.fieldOf("max_mana", pool -> pool.maxMana),
            ManaVariant.ENDEC.optionalFieldOf("variant", pool -> pool.variant, ManaVariant.of(Manae.TRADITIONAL)),
            CircleSlateManaPool::new);

    public static final ManaPoolType<CircleSlateManaPool> TYPE = Registry.register(
            ManaPoolType.REGISTRY, CreateManaIndustry.modLoc("circle_slate"), new ManaPoolType<>(ENDEC));

    /** Touches the class so {@link #TYPE} is registered (registry is written at class init). */
    public static void ensureTypeRegistered() {
    }

    private final SlateKnotInventory inventory;
    private final Level level;
    private final List<SingleSlotStorage<ManaVariant>> views;

    // Snapshot state — truth for the detached (decoded) form, best-effort
    // serialization cache for the live form (updated on every mutation).
    private long mana;
    private long maxMana;
    private ManaVariant variant = ManaVariant.EMPTY;

    /** Live form, backed by slate knots. */
    public CircleSlateManaPool(SlateKnotInventory inventory, Level level) {
        this.inventory = inventory;
        this.level = level;
        this.views = buildViews(inventory, level);
        updateSnapshot();
    }

    /** Detached snapshot form, used by ENDEC decode. */
    public CircleSlateManaPool(long mana, long maxMana, ManaVariant variant) {
        this.inventory = null;
        this.level = null;
        this.views = List.of();
        this.mana = mana;
        this.maxMana = maxMana;
        this.variant = variant;
    }

    private static List<SingleSlotStorage<ManaVariant>> buildViews(SlateKnotInventory inventory, Level level) {
        var result = new ArrayList<SingleSlotStorage<ManaVariant>>();
        for (var slot : InventoryStorage.of(inventory, null).getSlots()) {
            Storage<ManaVariant> storage = ManaStorage.ITEM.find(
                    slot.getResource().toStack(),
                    new ManaStorage.LookupContext(ContainerItemContext.ofSingleSlot(slot), level));
            if (storage instanceof SingleSlotStorage<ManaVariant> single) {
                result.add(single);
            }
        }
        return result;
    }

    @Override
    public ManaPoolType<?> type() {
        return TYPE;
    }

    @Override
    public ManaVariant getVariant(Level world) {
        if (inventory == null) {
            return mana > 0 ? variant : ManaVariant.EMPTY;
        }
        for (var view : views) {
            var resource = view.getResource();
            if (!resource.isBlank()) {
                return resource;
            }
        }
        return ManaVariant.EMPTY;
    }

    @Override
    public void setVariant(ManaVariant variant, Level world) {
        // Variant lives in the knot items' components — nothing to set here.
        if (inventory == null) {
            this.variant = variant;
        }
    }

    @Override
    public void set(long value, Level world) {
        if (inventory == null) {
            mana = Math.clamp(value, 0, maxMana);
            return;
        }
        long target = Math.clamp(value, 0, getMax(world));
        long current = get(world);
        try (var trans = Transaction.openOuter()) {
            if (target < current) {
                long remaining = current - target;
                for (var view : views) {
                    if (remaining <= 0) {
                        break;
                    }
                    var resource = view.getResource();
                    if (resource.isBlank()) {
                        continue;
                    }
                    remaining -= view.extract(resource, remaining, trans);
                }
            } else if (target > current) {
                long remaining = target - current;
                var manaVariant = getVariant(world);
                if (!manaVariant.isBlank()) {
                    for (var view : views) {
                        if (remaining <= 0) {
                            break;
                        }
                        remaining -= view.insert(manaVariant, remaining, trans);
                    }
                }
            }
            trans.commit();
        }
        inventory.syncChangedSlots();
        updateSnapshot();
    }

    @Override
    public long get(Level world) {
        if (inventory == null) {
            return mana;
        }
        long total = 0;
        for (var view : views) {
            total += view.getAmount();
        }
        return total;
    }

    @Override
    public void setMax(long value, Level world) {
        // Capacity lives in the knot items' components — nothing to set here.
        if (inventory == null) {
            maxMana = value;
        }
    }

    @Override
    public long getMax(Level world) {
        if (inventory == null) {
            return maxMana;
        }
        long total = 0;
        for (var view : views) {
            total += view.getCapacity();
        }
        return total;
    }

    @Override
    public long use(ManaVariant variant, long amount, Level world) {
        if (inventory == null) {
            return MutableManaPool.super.use(variant, amount, world);
        }
        long remaining = amount;
        try (var trans = Transaction.openOuter()) {
            for (var view : views) {
                if (remaining <= 0) {
                    break;
                }
                if (!view.getResource().equals(variant)) {
                    continue;
                }
                remaining -= view.extract(variant, remaining, trans);
            }
            trans.commit();
        }
        inventory.syncChangedSlots();
        updateSnapshot();
        return remaining;
    }

    @Override
    public long refill(ManaVariant variant, long amount, Level world) {
        if (inventory == null) {
            return MutableManaPool.super.refill(variant, amount, world);
        }
        long remaining = amount;
        try (var trans = Transaction.openOuter()) {
            for (var view : views) {
                if (remaining <= 0) {
                    break;
                }
                var current = view.getResource();
                if (!current.isBlank() && !current.equals(variant)) {
                    continue;
                }
                remaining -= view.insert(variant, remaining, trans);
            }
            trans.commit();
        }
        inventory.syncChangedSlots();
        updateSnapshot();
        return remaining;
    }

    @Override
    public MutableManaPool makeClone(Level world) {
        return new SimpleManaPool(get(world), getMax(world), getVariant(world));
    }

    private void updateSnapshot() {
        mana = get(level);
        maxMana = getMax(level);
        variant = getVariant(level);
    }
}
