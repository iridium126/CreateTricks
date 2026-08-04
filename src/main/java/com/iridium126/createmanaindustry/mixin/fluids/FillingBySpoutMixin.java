package com.iridium126.createmanaindustry.mixin.fluids;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import com.iridium126.createmanaindustry.CreateManaIndustry;
import com.iridium126.createmanaindustry.content.recipes.KnotFillingLogic;
import com.simibubi.create.content.fluids.spout.FillingBySpout;

import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.neoforged.neoforge.fluids.FluidStack;

@Mixin(value = FillingBySpout.class, remap = false)
public class FillingBySpoutMixin {

    @Inject(method = "getRequiredAmountForItem", at = @At("HEAD"), cancellable = true)
    private static void createmanaindustry$overrideIncompleteKnotFluidAmount(Level world, ItemStack stack,
            FluidStack availableFluid, CallbackInfoReturnable<Integer> cir) {
        if (!CreateManaIndustry.TRICKSTER_ACTIVE)
            return;
        int requiredAmount = KnotFillingLogic.getRequiredFluidAmount(stack, availableFluid);
        if (requiredAmount >= 0)
            cir.setReturnValue(requiredAmount);
    }

    @Inject(method = "fillItem", at = @At("HEAD"), cancellable = true)
    private static void createmanaindustry$fillIncompleteKnot(Level level, int requiredAmount, ItemStack stack,
            FluidStack availableFluid, CallbackInfoReturnable<ItemStack> cir) {
        if (!CreateManaIndustry.TRICKSTER_ACTIVE)
            return;
        ItemStack result = KnotFillingLogic.fillIncompleteKnot(stack);
        if (!result.isEmpty()) {
            availableFluid.shrink(requiredAmount);
            stack.shrink(1);
            cir.setReturnValue(result);
        }
    }
}
