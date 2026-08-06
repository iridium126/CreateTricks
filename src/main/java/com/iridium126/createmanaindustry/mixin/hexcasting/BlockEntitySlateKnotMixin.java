package com.iridium126.createmanaindustry.mixin.hexcasting;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import com.iridium126.createmanaindustry.compat.hexcasting.circle.SlateKnotHolder;

import at.petrak.hexcasting.common.blocks.circles.BlockEntitySlate;
import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.Tag;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockState;

/**
 * Adds a single knot-item slot to HexCasting's {@link BlockEntitySlate},
 * persisted under the {@code cmi_knot} NBT key. Circle spells executed via
 * {@code execute_trick} draw mana from these slate knots (see
 * {@code CircleSlateManaPool}).
 */
@Mixin(value = BlockEntitySlate.class, remap = false)
public abstract class BlockEntitySlateKnotMixin extends BlockEntity implements SlateKnotHolder {

    @Unique
    private static final String KNOT_TAG = "cmi_knot";

    @Unique
    private ItemStack createmanaindustry$knot = ItemStack.EMPTY;

    protected BlockEntitySlateKnotMixin(BlockEntityType<?> type, BlockPos pos, BlockState state) {
        super(type, pos, state);
    }

    @Override
    public ItemStack createmanaindustry$getKnot() {
        return createmanaindustry$knot;
    }

    @Override
    public void createmanaindustry$setKnot(ItemStack stack) {
        createmanaindustry$knot = stack.copyWithCount(1);
    }

    @Override
    public ItemStack createmanaindustry$removeKnot() {
        var out = createmanaindustry$knot;
        createmanaindustry$knot = ItemStack.EMPTY;
        return out;
    }

    @Inject(method = "saveModData", at = @At("TAIL"))
    private void createmanaindustry$saveKnot(CompoundTag tag, HolderLookup.Provider registries, CallbackInfo ci) {
        if (createmanaindustry$knot.isEmpty()) {
            tag.remove(KNOT_TAG);
        } else {
            tag.put(KNOT_TAG, createmanaindustry$knot.save(registries, new CompoundTag()));
        }
    }

    @Inject(method = "loadModData", at = @At("TAIL"))
    private void createmanaindustry$loadKnot(CompoundTag tag, HolderLookup.Provider registries, CallbackInfo ci) {
        createmanaindustry$knot = tag.contains(KNOT_TAG, Tag.TAG_COMPOUND)
                ? ItemStack.parseOptional(registries, tag.getCompound(KNOT_TAG))
                : ItemStack.EMPTY;
    }
}
