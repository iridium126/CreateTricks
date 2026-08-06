package com.iridium126.createmanaindustry.mixin.hextrick;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import com.iridium126.createmanaindustry.compat.trickster.ConstructMediaStorage;

import dev.enjarai.trickster.block.ModularSpellConstructBlockEntity;
import dev.enjarai.trickster.block.SpellConstructBlockEntity;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.Tag;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.entity.BlockEntity;

/**
 * Persists construct media ({@code cmi_media} NBT) and lets direct
 * {@code setItem} inserters charge media into the construct without placing
 * the item into its real inventory. Targets both construct block entities.
 * <p>
 * Gated on BOTH hexcasting and trickster (references hexcasting's
 * MediaHelper via ConstructMediaStorage) — see the {@code .hextrick.} package
 * gate in CMIMixinPlugin.
 */
@Mixin(value = {SpellConstructBlockEntity.class, ModularSpellConstructBlockEntity.class}, remap = false)
public abstract class ConstructBlockEntityMediaMixin {

    @Inject(method = "loadAdditional", at = @At("TAIL"))
    private void createmanaindustry$loadMedia(CompoundTag tag, HolderLookup.Provider registries, CallbackInfo ci) {
        long media = tag.contains(ConstructMediaStorage.MEDIA_TAG, Tag.TAG_LONG)
                ? tag.getLong(ConstructMediaStorage.MEDIA_TAG)
                : 0L;
        ConstructMediaStorage.setStoredMedia((BlockEntity) (Object) this, media);
    }

    @Inject(method = "writeCommonNbt", at = @At("TAIL"))
    private void createmanaindustry$saveMedia(CompoundTag tag, HolderLookup.Provider registries, CallbackInfo ci) {
        tag.putLong(ConstructMediaStorage.MEDIA_TAG, ConstructMediaStorage.getMedia((BlockEntity) (Object) this));
    }

    @Inject(method = "setItem", at = @At("HEAD"), cancellable = true)
    private void createmanaindustry$absorbMedia(int slot, ItemStack stack, CallbackInfo ci) {
        if (ConstructMediaStorage.insertMedia((BlockEntity) (Object) this, stack)) {
            ci.cancel();
        }
    }

    @Inject(method = "canPlaceItem", at = @At("HEAD"), cancellable = true, require = 0)
    private void createmanaindustry$canInsertMedia(int slot, ItemStack stack, CallbackInfoReturnable<Boolean> cir) {
        if (ConstructMediaStorage.canInsertMedia((BlockEntity) (Object) this, stack)) {
            cir.setReturnValue(true);
        }
    }
}
