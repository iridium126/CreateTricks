package com.iridium126.createmanaindustry.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import com.iridium126.createmanaindustry.content.burner.AllayBurnerBlock;
import com.simibubi.create.content.processing.basin.BasinBlockEntity;
import com.simibubi.create.content.processing.burner.BlazeBurnerBlock;

import net.minecraft.world.level.block.state.BlockState;

/**
 * Makes an actively burning Allay Burner report itself as {@code SEETHING} to
 * basins, so both {@code heated} and {@code superheated} recipes run on it.
 * The Allay Burner is deliberately NOT in the {@code create:passive_boiler_heaters}
 * tag — its idle state must provide no heat.
 */
@Mixin(value = BasinBlockEntity.class, remap = false)
public class BasinBlockEntityHeatMixin {

    @Inject(method = "getHeatLevelOf(Lnet/minecraft/world/level/block/state/BlockState;)Lcom/simibubi/create/content/processing/burner/BlazeBurnerBlock$HeatLevel;",
            at = @At("HEAD"), cancellable = true)
    private static void createmanaindustry$allayBurnerHeat(BlockState state,
            CallbackInfoReturnable<BlazeBurnerBlock.HeatLevel> cir) {
        if (state.getBlock() instanceof AllayBurnerBlock
                && state.getValue(AllayBurnerBlock.HEAT_LEVEL) == AllayBurnerBlock.HeatLevel.ALLAYHEATED)
            cir.setReturnValue(BlazeBurnerBlock.HeatLevel.SEETHING);
    }
}
