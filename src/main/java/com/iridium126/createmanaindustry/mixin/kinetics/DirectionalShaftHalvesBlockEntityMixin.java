package com.iridium126.createmanaindustry.mixin.kinetics;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import com.simibubi.create.content.kinetics.base.DirectionalShaftHalvesBlockEntity;

import net.minecraft.core.Direction;

@Mixin(value = DirectionalShaftHalvesBlockEntity.class, remap = false)
public abstract class DirectionalShaftHalvesBlockEntityMixin {
    @Inject(method = "getSourceFacing", at = @At("HEAD"), cancellable = true)
    private void createmanaindustry$makeSourceFacingSafe(CallbackInfoReturnable<Direction> cir) {
        if (((KineticBlockEntityAccessor) this).createmanaindustry$getSource() == null) {
            cir.setReturnValue(Direction.UP);
        }
    }
}
