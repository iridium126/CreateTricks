package com.iridium126.createmanaindustry.mixin.fluids;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import com.iridium126.createmanaindustry.content.fluids.FluidTransferContextSupport;
import com.simibubi.create.content.fluids.transfer.GenericItemFilling;

import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.neoforged.neoforge.fluids.FluidStack;

@Mixin(value = GenericItemFilling.class, remap = false)
public class GenericItemFillingMixin {
    @Inject(method = "canItemBeFilled", at = @At("HEAD"))
    private static void createmanaindustry$setLevelForFillCheck(Level world, ItemStack stack,
            CallbackInfoReturnable<Boolean> cir) {
        FluidTransferContextSupport.captureLevel(world);
    }

    @Inject(method = "canItemBeFilled", at = @At("RETURN"))
    private static void createmanaindustry$clearLevelForFillCheck(Level world, ItemStack stack,
            CallbackInfoReturnable<Boolean> cir) {
        FluidTransferContextSupport.clearLevel();
    }

    @Inject(method = "getRequiredAmountForItem", at = @At("HEAD"), cancellable = true)
    private static void createmanaindustry$setLevelForRequiredAmount(Level world, ItemStack stack, FluidStack availableFluid,
            CallbackInfoReturnable<Integer> cir) {
        FluidTransferContextSupport.captureLevel(world);
        int requiredAmount = FluidTransferContextSupport.getEsotericManaRequiredAmount(stack, availableFluid);
        if (requiredAmount >= 0)
            cir.setReturnValue(requiredAmount);
    }

    @Inject(method = "getRequiredAmountForItem", at = @At("RETURN"))
    private static void createmanaindustry$clearLevelForRequiredAmount(Level world, ItemStack stack, FluidStack availableFluid,
            CallbackInfoReturnable<Integer> cir) {
        FluidTransferContextSupport.clearLevel();
    }

    @Inject(method = "fillItem", at = @At("HEAD"), cancellable = true)
    private static void createmanaindustry$setLevelForFilling(Level world, int requiredAmount, ItemStack stack,
            FluidStack availableFluid, CallbackInfoReturnable<ItemStack> cir) {
        FluidTransferContextSupport.captureLevel(world);
        if (requiredAmount != FluidTransferContextSupport.getEsotericManaRequiredAmount(stack, availableFluid))
            return;

        ItemStack filledItem = FluidTransferContextSupport.fillEsotericMana(stack, availableFluid);
        if (!filledItem.isEmpty())
            cir.setReturnValue(filledItem);
    }

    @Inject(method = "fillItem", at = @At("RETURN"))
    private static void createmanaindustry$clearLevelForFilling(Level world, int requiredAmount, ItemStack stack,
            FluidStack availableFluid, CallbackInfoReturnable<ItemStack> cir) {
        FluidTransferContextSupport.clearLevel();
    }
}
