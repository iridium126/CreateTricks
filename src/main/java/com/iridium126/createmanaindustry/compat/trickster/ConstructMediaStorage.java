package com.iridium126.createmanaindustry.compat.trickster;

import java.util.Map;
import java.util.WeakHashMap;

import at.petrak.hexcasting.api.utils.MediaHelper;
import at.petrak.hexcasting.common.lib.HexItems;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.BlockEntityType;

/**
 * Hex media stored on Trickster spell construct block entities, charged by
 * hoppers/funnels inserting media items and consumed by spells executed via
 * {@code eval_iota} in a {@link ConstructCastEnv}. Runtime state lives in a
 * weak map; persistence goes through the NBT tag {@code cmi_media} (written
 * by {@code ConstructBlockEntityMediaMixin}).
 */
public final class ConstructMediaStorage {

    public static final long MAX_CAPACITY = 9_000_000_000_000_000_000L;
    public static final String MEDIA_TAG = "cmi_media";

    private static final ResourceLocation SPELL_CONSTRUCT_ID =
            ResourceLocation.fromNamespaceAndPath("trickster", "spell_construct");
    private static final ResourceLocation MODULAR_SPELL_CONSTRUCT_ID =
            ResourceLocation.fromNamespaceAndPath("trickster", "modular_spell_construct");

    private static final Map<BlockEntity, Long> MEDIA = new WeakHashMap<>();

    private ConstructMediaStorage() {
    }

    public static boolean isConstruct(BlockEntity blockEntity) {
        if (blockEntity == null) {
            return false;
        }
        BlockEntityType<?> type = blockEntity.getType();
        return type.equals(spellConstructType()) || type.equals(modularSpellConstructType());
    }

    public static long getMedia(BlockEntity blockEntity) {
        return MEDIA.getOrDefault(blockEntity, 0L);
    }

    public static void setStoredMedia(BlockEntity blockEntity, long media) {
        MEDIA.put(blockEntity, media);
    }

    public static void syncMedia(BlockEntity blockEntity) {
        blockEntity.setChanged();
        Level level = blockEntity.getLevel();
        if (level != null) {
            level.sendBlockUpdated(
                    blockEntity.getBlockPos(),
                    blockEntity.getBlockState(),
                    blockEntity.getBlockState(),
                    3
            );
        }
    }

    public static long remainingMediaCapacity(BlockEntity blockEntity) {
        long media = getMedia(blockEntity);
        if (media < 0) {
            return 0;
        }
        return Math.max(0, MAX_CAPACITY - media);
    }

    public static long extractMediaFromItem(BlockEntity blockEntity, ItemStack stack, boolean simulate) {
        if (getMedia(blockEntity) < 0) {
            return 0;
        }
        return MediaHelper.extractMedia(stack, remainingMediaCapacity(blockEntity), true, simulate);
    }

    public static boolean canInsertMedia(BlockEntity blockEntity, ItemStack stack) {
        return !getInsertionRemainder(blockEntity, stack, true).equals(stack);
    }

    public static ItemStack getInsertionRemainder(BlockEntity blockEntity, ItemStack stack, boolean simulate) {
        if (stack.isEmpty()) {
            return stack;
        }

        ItemStack working = stack.copy();
        if (getMedia(blockEntity) >= 0 && working.is(HexItems.CREATIVE_UNLOCKER.get())) {
            if (!simulate) {
                setStoredMedia(blockEntity, -1);
                syncMedia(blockEntity);
            }
            working.shrink(1);
            return working;
        }

        if (remainingMediaCapacity(blockEntity) <= 0) {
            return stack;
        }

        long inserted = extractMediaFromItem(blockEntity, working, simulate);
        if (inserted <= 0) {
            return stack;
        }

        if (!simulate) {
            setStoredMedia(blockEntity, Math.min(getMedia(blockEntity) + inserted, MAX_CAPACITY));
            syncMedia(blockEntity);
        }
        return working;
    }

    public static boolean insertMedia(BlockEntity blockEntity, ItemStack stack) {
        ItemStack remainder = getInsertionRemainder(blockEntity, stack, false);
        if (remainder.equals(stack)) {
            return false;
        }
        stack.applyComponents(remainder.getComponents());
        stack.setCount(remainder.getCount());
        return true;
    }

    private static BlockEntityType<?> spellConstructType() {
        return BuiltInRegistries.BLOCK_ENTITY_TYPE.get(
                ResourceKey.create(Registries.BLOCK_ENTITY_TYPE, SPELL_CONSTRUCT_ID));
    }

    private static BlockEntityType<?> modularSpellConstructType() {
        return BuiltInRegistries.BLOCK_ENTITY_TYPE.get(
                ResourceKey.create(Registries.BLOCK_ENTITY_TYPE, MODULAR_SPELL_CONSTRUCT_ID));
    }
}
