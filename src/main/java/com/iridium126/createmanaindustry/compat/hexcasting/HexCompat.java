package com.iridium126.createmanaindustry.compat.hexcasting;

import com.iridium126.createmanaindustry.CreateManaIndustry;

import at.petrak.hexcasting.api.item.MediaHolderItem;
import at.petrak.hexcasting.api.mod.HexConfig;
import net.minecraft.world.item.ItemStack;

/**
 * Bridge that isolates all compile-time Hexcasting API references.
 * <p>
 * This class must <b>only</b> be accessed when
 * {@link CreateManaIndustry#HEX_ACTIVE} is {@code true}.  When Hexcasting is
 * absent the JVM never loads this class, avoiding
 * {@link NoClassDefFoundError}.
 * <p>
 * Config accessors use safe defaults when {@link HexConfig#common()} returns
 * {@code null} (e.g. during early initialisation).
 */
public final class HexCompat {

    private HexCompat() {}

    // ---- MediaHolderItem wrappers ------------------------------------------

    public static long getMedia(ItemStack stack) {
        if (stack.getItem() instanceof MediaHolderItem holder)
            return holder.getMedia(stack);
        return 0;
    }

    public static long getMaxMedia(ItemStack stack) {
        if (stack.getItem() instanceof MediaHolderItem holder)
            return holder.getMaxMedia(stack);
        return 0;
    }

    public static boolean canRecharge(ItemStack stack) {
        if (stack.getItem() instanceof MediaHolderItem holder)
            return holder.canRecharge(stack);
        return false;
    }

    public static boolean canProvideMedia(ItemStack stack) {
        if (stack.getItem() instanceof MediaHolderItem holder)
            return holder.canProvideMedia(stack);
        return false;
    }

    public static long insertMedia(ItemStack stack, long amount) {
        if (stack.getItem() instanceof MediaHolderItem holder)
            return holder.insertMedia(stack, amount, false);
        return 0;
    }

    public static long withdrawMedia(ItemStack stack, long amount) {
        if (stack.getItem() instanceof MediaHolderItem holder)
            return holder.withdrawMedia(stack, amount, false);
        return 0;
    }

    // ---- HexConfig accessors with fallback defaults ------------------------

    /** Fallback: {@code 10000} ({@code MediaConstants.DUST_UNIT}). */
    public static long getDustMediaAmount() {
        try {
            HexConfig.CommonConfigAccess cfg = HexConfig.common();
            return cfg != null ? cfg.dustMediaAmount() : 10000L;
        } catch (Exception e) {
            return 10000L;
        }
    }

    /** Fallback: {@code 50000} ({@code MediaConstants.SHARD_UNIT}). */
    public static long getShardMediaAmount() {
        try {
            HexConfig.CommonConfigAccess cfg = HexConfig.common();
            return cfg != null ? cfg.shardMediaAmount() : 50000L;
        } catch (Exception e) {
            return 50000L;
        }
    }

    /** Fallback: {@code 100000} ({@code MediaConstants.CRYSTAL_UNIT}). */
    public static long getChargedCrystalMediaAmount() {
        try {
            HexConfig.CommonConfigAccess cfg = HexConfig.common();
            return cfg != null ? cfg.chargedCrystalMediaAmount() : 100000L;
        } catch (Exception e) {
            return 100000L;
        }
    }
}
