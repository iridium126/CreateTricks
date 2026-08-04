package com.iridium126.createmanaindustry.mixin.hexcasting;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import com.iridium126.createmanaindustry.content.recipes.HexItemFillingLogic;
import com.simibubi.create.content.fluids.spout.FillingBySpout;

import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.neoforged.neoforge.fluids.FluidStack;

/**
 * Extends the Spout's filling logic to handle incomplete hex items during
 * {@code liquid_media} filling.
 * <p>
 * Mirrors {@link FillingBySpoutMixin} exactly — same injection points,
 * same pattern — but delegates to {@link HexItemFillingLogic}.
 */
@Mixin(value = FillingBySpout.class, remap = false)
public class FillingBySpoutHexMixin {

    @Inject(method = "getRequiredAmountForItem", at = @At("HEAD"), cancellable = true)
    private static void createmanaindustry$overrideIncompleteHexItemFluidAmount(Level world, ItemStack stack,
            FluidStack availableFluid, CallbackInfoReturnable<Integer> cir) {
        int requiredAmount = HexItemFillingLogic.getRequiredFluidAmount(stack, availableFluid);
        if (requiredAmount >= 0)
            cir.setReturnValue(requiredAmount);
    }

    @Inject(method = "fillItem", at = @At("HEAD"), cancellable = true)
    private static void createmanaindustry$fillIncompleteHexItem(Level level, int requiredAmount, ItemStack stack,
            FluidStack availableFluid, CallbackInfoReturnable<ItemStack> cir) {
        ItemStack result = HexItemFillingLogic.fillIncompleteHexItem(stack, requiredAmount);
        if (!result.isEmpty()) {
            availableFluid.shrink(requiredAmount);
            stack.shrink(1);
            cir.setReturnValue(result);
        }
    }
}
