package com.iridium126.createmanaindustry.mixin.bnb;

import java.util.function.Predicate;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import com.iridium126.createmanaindustry.content.kinetics.bnb.BnBKineticsCoreNodes;

import net.minecraft.world.level.block.Block;

import com.kipti.bnb.content.kinetics.cogwheel_chain.types.CogwheelChainType;

/**
 * Lets modular spell constructs satisfy every chain type's cogwheel predicate,
 * matching the per-type availability of the regular cogwheels they stand in
 * for (flanged cogs pass BELT/ROPE, plain cogs and constructs pass CHAIN —
 * with this wrapper constructs pass all three). One chokepoint covers every
 * gate: interaction start, placement preview colouring, node addition and
 * level checks all funnel through {@link CogwheelChainType#getCogwheelPredicate()}.
 */
@Mixin(targets = "com.kipti.bnb.content.kinetics.cogwheel_chain.types.CogwheelChainType", remap = false)
public class CogwheelChainTypeMixin {

    @Inject(method = "getCogwheelPredicate", at = @At("RETURN"), cancellable = true, remap = false)
    private void createmanaindustry$acceptSpellConstructs(
            CallbackInfoReturnable<Predicate<Block>> cir) {
        final Predicate<Block> original = cir.getReturnValue();
        cir.setReturnValue(block -> original.test(block)
                || BnBKineticsCoreNodes.isModularSpellConstructBlock(block));
    }
}
