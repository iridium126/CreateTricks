package com.iridium126.createmanaindustry.compat.hexcasting;

import at.petrak.hexcasting.api.casting.eval.CastingEnvironment;
import at.petrak.hexcasting.api.casting.iota.GarbageIota;
import at.petrak.hexcasting.api.casting.iota.Iota;
import at.petrak.hexcasting.api.casting.iota.Vec3Iota;
import at.petrak.hexcasting.api.casting.mishaps.Mishap;
import at.petrak.hexcasting.api.pigment.FrozenPigment;
import at.petrak.hexcasting.api.utils.TreeList;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.DyeColor;
import net.minecraft.world.phys.Vec3;

/**
 * Thrown when a Depot or Placard at the target position does not hold an
 * {@link at.petrak.hexcasting.api.item.IotaHolderItem}.
 */
public class MishapInvalidBlockOrItem extends Mishap {

    public enum Reason {
        /** The block at the position is not a Depot or Placard. */
        INVALID_BLOCK,
        /** The Depot/Placard holds no item. */
        EMPTY_ITEM,
        /** The held item is not an IotaHolderItem, or contains no Iota. */
        INVALID_ITEM
    }

    private final Vec3 position;
    private final Reason reason;

    public MishapInvalidBlockOrItem(Vec3 position, Reason reason) {
        this.position = position;
        this.reason = reason;
    }

    @Override
    public FrozenPigment accentColor(CastingEnvironment ctx, Context errorCtx) {
        return dyeColor(DyeColor.LIGHT_BLUE);
    }

    @Override
    public TreeList<Iota> execute(CastingEnvironment env, Context errorCtx, TreeList<Iota> stack) {
        return stack.appended(new GarbageIota());
    }

    @Override
    protected Component errorMessage(CastingEnvironment ctx, Context errorCtx) {
        String key = switch (reason) {
            case INVALID_BLOCK -> "createmanaindustry.hex.mishap.invalid_block";
            case EMPTY_ITEM -> "createmanaindustry.hex.mishap.empty_item";
            case INVALID_ITEM -> "createmanaindustry.hex.mishap.invalid_item";
        };
        return Component.translatable(key, Vec3Iota.display(position));
    }
}
