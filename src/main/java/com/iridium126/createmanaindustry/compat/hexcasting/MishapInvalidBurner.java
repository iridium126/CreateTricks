package com.iridium126.createmanaindustry.compat.hexcasting;

import at.petrak.hexcasting.api.casting.eval.CastingEnvironment;
import at.petrak.hexcasting.api.casting.iota.Iota;
import at.petrak.hexcasting.api.casting.iota.Vec3Iota;
import at.petrak.hexcasting.api.casting.mishaps.Mishap;
import at.petrak.hexcasting.api.pigment.FrozenPigment;
import at.petrak.hexcasting.api.utils.TreeList;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.DyeColor;
import net.minecraft.world.phys.Vec3;

/**
 * Thrown when the block at the target position is not a lit Blaze Burner or an
 * Allay Burner. Unlike {@link MishapInvalidBlockOrItem}, the stack is left
 * untouched (no {@code GarbageIota} is appended).
 */
public class MishapInvalidBurner extends Mishap {

    private final Vec3 position;

    public MishapInvalidBurner(Vec3 position) {
        this.position = position;
    }

    @Override
    public FrozenPigment accentColor(CastingEnvironment ctx, Context errorCtx) {
        return dyeColor(DyeColor.LIGHT_BLUE);
    }

    @Override
    public TreeList<Iota> execute(CastingEnvironment env, Context errorCtx, TreeList<Iota> stack) {
        return stack;
    }

    @Override
    protected Component errorMessage(CastingEnvironment ctx, Context errorCtx) {
        return Component.translatable("createmanaindustry.hex.mishap.invalid_burner", Vec3Iota.display(position));
    }
}
