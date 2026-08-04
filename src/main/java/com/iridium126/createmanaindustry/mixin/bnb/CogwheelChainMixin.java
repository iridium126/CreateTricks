package com.iridium126.createmanaindustry.mixin.bnb;

import java.util.List;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.gen.Invoker;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import com.iridium126.createmanaindustry.content.kinetics.bnb.BnBKineticsCoreNodes;
import com.kipti.bnb.content.kinetics.cogwheel_chain.graph.CogwheelChainCandidate;
import com.kipti.bnb.content.kinetics.cogwheel_chain.graph.PathedCogwheelNode;
import com.kipti.bnb.content.kinetics.cogwheel_chain.graph.PlacingCogwheelChain;
import com.kipti.bnb.content.kinetics.cogwheel_chain.graph.PlacingCogwheelNode;
import com.kipti.bnb.content.kinetics.cogwheel_chain.types.CogwheelChainType;

import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;

/**
 * Cogwheel chain graph extensions for spell constructs:
 * <ul>
 *   <li>Integrity check — spell-construct nodes are allowed but require a
 *       kinetics core; other nodes keep BnB's candidate consistency check.</li>
 *   <li>Placement — chain placement skips spell-construct nodes (they are not
 *       real cogwheels), placing real cogwheels only at the remaining nodes.</li>
 * </ul>
 */
@Mixin(targets = "com.kipti.bnb.content.kinetics.cogwheel_chain.graph.CogwheelChain", remap = false)
public abstract class CogwheelChainMixin {

    @Shadow
    private List<PathedCogwheelNode> cogwheelNodes;

    @Shadow
    public abstract CogwheelChainType getChainType();

    @Invoker("placeChainCogwheelInLevel")
    abstract void createmanaindustry$invokePlaceChainCogwheelInLevel(Level level, PlacingCogwheelNode node,
            boolean isController, int chainsRequired, BlockPos controllerPos, boolean isCreative);

    @Inject(method = "checkIntegrity", at = @At("HEAD"), cancellable = true)
    private void createmanaindustry$checkKineticsCoreNodes(Level level, BlockPos origin,
            CallbackInfoReturnable<Boolean> cir) {
        boolean hasSpellConstruct = false;

        for (PathedCogwheelNode node : cogwheelNodes) {
            BlockPos pos = origin.offset(node.localPos());
            if (!level.isLoaded(pos))
                continue;

            if (BnBKineticsCoreNodes.isModularSpellConstruct(level, pos)) {
                hasSpellConstruct = true;
                if (!BnBKineticsCoreNodes.hasAnyKineticsCore(level, pos)) {
                    cir.setReturnValue(false);
                    return;
                }
                continue;
            }

            BlockState state = level.getBlockState(pos);
            CogwheelChainCandidate candidate = CogwheelChainCandidate.getForBlock(state);
            if (candidate == null || !candidate.isConsistentWithNode(node)) {
                cir.setReturnValue(false);
                return;
            }
        }

        cir.setReturnValue(hasSpellConstruct || true);
    }

    @Inject(method = "placeInLevel", at = @At("HEAD"), cancellable = true, remap = false)
    private void createmanaindustry$customPlaceInLevel(Level level, PlacingCogwheelChain placingChain,
            boolean isCreative, CallbackInfo ci) {
        boolean hasSpellConstruct = false;
        for (PlacingCogwheelNode node : placingChain.getVisitedNodes()) {
            if (BnBKineticsCoreNodes.isModularSpellConstruct(level, node.pos())) {
                hasSpellConstruct = true;
                break;
            }
        }
        if (!hasSpellConstruct)
            return;

        ci.cancel();
        boolean isFirst = true;
        BlockPos controllerPos = placingChain.getFirstNode().pos();
        int chainsRequired = placingChain.getChainsRequiredInLoop(getChainType());

        for (PlacingCogwheelNode node : placingChain.getVisitedNodes()) {
            if (BnBKineticsCoreNodes.isModularSpellConstruct(level, node.pos())) {
                isFirst = false;
                continue;
            }
            createmanaindustry$invokePlaceChainCogwheelInLevel(level, node, isFirst, chainsRequired,
                    controllerPos, isCreative);
            isFirst = false;
        }
    }
}
