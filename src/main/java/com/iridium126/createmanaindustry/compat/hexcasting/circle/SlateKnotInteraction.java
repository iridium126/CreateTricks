package com.iridium126.createmanaindustry.compat.hexcasting.circle;

import dev.enjarai.trickster.item.ModItems;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.stats.Stats;
import net.minecraft.world.Containers;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.entity.player.PlayerInteractEvent;
import net.neoforged.neoforge.event.level.BlockEvent;

/**
 * Player interactions for the slate knot slot: right-click with a mana knot
 * to insert, right-click empty-handed to remove, break to drop.
 * Only loaded under the HEX && TRICKSTER gate (references Trickster's tag).
 */
public final class SlateKnotInteraction {

    private SlateKnotInteraction() {
    }

    public static void register() {
        NeoForge.EVENT_BUS.addListener(SlateKnotInteraction::onRightClickBlock);
        NeoForge.EVENT_BUS.addListener(SlateKnotInteraction::onBreakBlock);
    }

    private static void onRightClickBlock(PlayerInteractEvent.RightClickBlock event) {
        Level level = event.getLevel();
        BlockEntity blockEntity = level.getBlockEntity(event.getPos());
        if (!(blockEntity instanceof SlateKnotHolder slate)) {
            return;
        }

        ItemStack held = event.getItemStack();
        ItemStack knot = slate.createmanaindustry$getKnot();
        if (knot.isEmpty()) {
            if (!held.is(ModItems.MANA_KNOTS)) {
                return;
            }
            placeKnot(level, event.getEntity(), blockEntity, slate, held);
        } else {
            if (!held.isEmpty()) {
                return;
            }
            removeKnot(level, event.getEntity(), blockEntity, slate);
        }

        event.setCancellationResult(InteractionResult.sidedSuccess(level.isClientSide));
        event.setCanceled(true);
    }

    private static void onBreakBlock(BlockEvent.BreakEvent event) {
        Level level = (Level) event.getLevel();
        if (level.isClientSide) {
            return;
        }
        BlockEntity blockEntity = level.getBlockEntity(event.getPos());
        if (!(blockEntity instanceof SlateKnotHolder slate)) {
            return;
        }
        ItemStack knot = slate.createmanaindustry$removeKnot();
        if (!knot.isEmpty()) {
            Containers.dropItemStack(level, event.getPos().getX() + 0.5, event.getPos().getY() + 0.5,
                    event.getPos().getZ() + 0.5, knot);
            sync(blockEntity);
        }
    }

    private static void placeKnot(Level level, Player player, BlockEntity blockEntity, SlateKnotHolder slate,
            ItemStack held) {
        if (level.isClientSide) {
            return;
        }
        slate.createmanaindustry$setKnot(held.copyWithCount(1));
        player.awardStat(Stats.ITEM_USED.get(held.getItem()));
        if (!player.getAbilities().instabuild) {
            held.shrink(1);
        }
        level.playSound(null, blockEntity.getBlockPos(), SoundEvents.ITEM_PICKUP, SoundSource.BLOCKS, 1.0f, 1.0f);
        sync(blockEntity);
    }

    private static void removeKnot(Level level, Player player, BlockEntity blockEntity, SlateKnotHolder slate) {
        if (level.isClientSide) {
            return;
        }
        ItemStack knot = slate.createmanaindustry$removeKnot();
        if (knot.isEmpty()) {
            return;
        }
        if (!player.getInventory().add(knot)) {
            player.drop(knot, false);
        }
        level.playSound(null, blockEntity.getBlockPos(), SoundEvents.ITEM_PICKUP, SoundSource.BLOCKS, 1.0f, 0.8f);
        sync(blockEntity);
    }

    private static void sync(BlockEntity blockEntity) {
        blockEntity.setChanged();
        Level level = blockEntity.getLevel();
        if (level != null) {
            level.sendBlockUpdated(blockEntity.getBlockPos(), blockEntity.getBlockState(), blockEntity.getBlockState(), 3);
        }
    }
}
