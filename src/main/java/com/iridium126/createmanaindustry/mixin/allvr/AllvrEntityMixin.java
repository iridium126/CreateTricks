package com.iridium126.createmanaindustry.mixin.allvr;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import com.iridium126.createmanaindustry.dimension.AllvrDimensionLimits;
import com.iridium126.createmanaindustry.dimension.AllvrDimensions;

import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.Level;

/**
 * Clamps entities to the dimension's ±30,000,000 Y software boundary
 * (AllvrDimensionLimits): positions beyond the bound are pushed back and the
 * outgoing vertical velocity is cancelled — a soft wall, not a teleport, to
 * avoid the compensation jitter of cross-dimension repositioning mid-tick.
 * Runs before the entity's own tick so movement, physics and block queries
 * this tick all observe the clamped position.
 */
@Mixin(Entity.class)
public abstract class AllvrEntityMixin {

    @Inject(method = "tick", at = @At("HEAD"))
    private void allvr$clampToBounds(CallbackInfo ci) {
        Entity self = (Entity) (Object) this;
        Level level = self.level();
        if (level.isClientSide || level.dimension() != AllvrDimensions.ALLAY_LEVEL) {
            return;
        }
        double y = self.getY();
        if (y > AllvrDimensionLimits.Y_BOUND) {
            self.setPos(self.getX(), AllvrDimensionLimits.Y_BOUND, self.getZ());
            if (self.getDeltaMovement().y > 0) {
                self.setDeltaMovement(self.getDeltaMovement().x, 0, self.getDeltaMovement().z);
            }
        } else if (y < -AllvrDimensionLimits.Y_BOUND) {
            self.setPos(self.getX(), -AllvrDimensionLimits.Y_BOUND, self.getZ());
            if (self.getDeltaMovement().y < 0) {
                self.setDeltaMovement(self.getDeltaMovement().x, 0, self.getDeltaMovement().z);
            }
        }
    }
}
