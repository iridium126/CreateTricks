package com.iridium126.createmanaindustry.mixin.allvr;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import com.iridium126.createmanaindustry.dimension.AllvrDimensionLimits;
import com.iridium126.createmanaindustry.dimension.AllvrDimensions;
import com.iridium126.createmanaindustry.dimension.cube.AllvrCubeMap;
import com.iridium126.createmanaindustry.dimension.cube.AllvrServerLevelDuck;

import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.Level;

/**
 * Server-side entity gates for the allay dimension:
 * <ul>
 *   <li>Clamps entities to the ±30,000,000 Y software boundary
 *       (AllvrDimensionLimits) — a soft wall, not a teleport, to avoid the
 *       compensation jitter of cross-dimension repositioning mid-tick.</li>
 *   <li>Freezes entities whose cube is not loaded ({@code Entity#tick}
 *       cancelled) — vanilla parity: entities in unloaded chunks don't tick.
 *       Without this, an entity on a far island whose cube was unloaded reads
 *       air for collision and falls into the void. Players are exempt (vanilla
 *       players tick regardless of chunk load). The client is never involved:
 *       vanilla doesn't chunk-gate client entity ticks either.</li>
 * </ul>
 * Both run before the entity's own tick so movement, physics and block
 * queries this tick observe the clamped/frozen decision.
 */
@Mixin(Entity.class)
public abstract class AllvrEntityMixin {

    @Inject(method = "tick", at = @At("HEAD"), cancellable = true)
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
        if (self instanceof ServerPlayer) {
            return;
        }
        if (level instanceof AllvrServerLevelDuck duck) {
            AllvrCubeMap map = duck.allvr$getCubeMap();
            if (map != null && map.peek(self.blockPosition()) == null) {
                ci.cancel();
            }
        }
    }
}
