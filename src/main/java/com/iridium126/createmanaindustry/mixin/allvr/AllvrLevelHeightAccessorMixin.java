package com.iridium126.createmanaindustry.mixin.allvr;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import com.iridium126.createmanaindustry.dimension.AllvrDimensionLimits;
import com.iridium126.createmanaindustry.dimension.AllvrDimensions;

import net.minecraft.world.level.Level;
import net.minecraft.world.level.LevelHeightAccessor;

/**
 * Widens the world-bounds predicate ({@code isOutsideBuildHeight}, and with
 * it {@code isInWorldBounds}) from the dimension_type's formal 4064 window to
 * the cube range (±AllvrDimensionLimits.Y_BOUND) inside the allay dimension.
 * <p>
 * Without this, vanilla consumers of the predicate reject cube-only Y
 * positions before cube code ever runs — e.g. {@code BlockPosArgument}
 * fails {@code /setblock}, {@code /data get block}, {@code /fill}, ... with
 * "Position is outside of this world". Vanilla bodies that pass the widened
 * check and then touch column sections are all either routed to the cube map
 * first (Level get/setBlockState), guarded by section-index bounds checks
 * (LevelChunk get/setBlockState), or Y-independent (heightmaps, chunk coords).
 * <p>
 * Gated by {@code instanceof Level} so other implementors keep vanilla
 * semantics: {@code LevelChunk} (window-sized section arrays) and
 * {@code WorldGenRegion} (window-only worldgen) are deliberately excluded.
 */
@Mixin(LevelHeightAccessor.class)
public interface AllvrLevelHeightAccessorMixin {

    @Inject(method = "isOutsideBuildHeight(I)Z", at = @At("HEAD"), cancellable = true)
    private void allvr$widenBounds(int y, CallbackInfoReturnable<Boolean> cir) {
        if ((Object) this instanceof Level level && level.dimension() == AllvrDimensions.ALLAY_LEVEL) {
            cir.setReturnValue(y < -AllvrDimensionLimits.Y_BOUND || y > AllvrDimensionLimits.Y_BOUND);
        }
    }
}
