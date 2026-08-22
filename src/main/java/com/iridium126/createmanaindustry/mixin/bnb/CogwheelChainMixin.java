package com.iridium126.createmanaindustry.mixin.bnb;

import java.util.List;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.gen.Invoker;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import com.iridium126.createmanaindustry.content.kinetics.bnb.BnBKineticsCoreNodes;
import com.kipti.bnb.content.kinetics.cogwheel_chain.graph.PlacingCogwheelChain;
import com.kipti.bnb.content.kinetics.cogwheel_chain.graph.PlacingCogwheelNode;
import com.kipti.bnb.content.kinetics.cogwheel_chain.types.CogwheelChainType;

import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;

import com.simibubi.create.content.kinetics.base.KineticBlockEntity;

/**
 * Chain placement extension for spell constructs. Spell nodes participate in
 * every upstream check (candidates, type predicate, geometry, caps) like any
 * cogwheel — the only thing they cannot do is receive a chain behaviour, so
 * {@code placeInLevel} is replaced with a variant that skips them and roots
 * the stored offsets at the first non-spell node: that node becomes the
 * controller, keeping BnB's "controller == origin" invariant intact even when
 * the player starts the chain on a spell construct.
 */
@Mixin(targets = "com.kipti.bnb.content.kinetics.cogwheel_chain.graph.CogwheelChain", remap = false)
public abstract class CogwheelChainMixin {

    @Shadow
    public abstract CogwheelChainType getChainType();

    @Invoker("placeChainCogwheelInLevel")
    abstract void createmanaindustry$invokePlaceChainCogwheelInLevel(Level level, PlacingCogwheelNode node,
            boolean isController, int chainsRequired, BlockPos controllerPos, boolean isCreative);

    @Inject(method = "placeInLevel", at = @At("HEAD"), cancellable = true, remap = false)
    private void createmanaindustry$customPlaceInLevel(Level level, PlacingCogwheelChain placingChain,
            boolean isCreative, CallbackInfo ci) {
        List<PlacingCogwheelNode> visited = placingChain.getVisitedNodes();
        if (!BnBKineticsCoreNodes.containsSpellNode(level, visited))
            return; // pure-cogwheel chain — native placement handles it

        // A chain made purely of spell constructs has no kinetic member to act
        // as controller. The client-side completion path refuses these; refuse
        // here as well so a crafted packet cannot place an unresolvable chain.
        PlacingCogwheelNode controllerTemplate = null;
        for (PlacingCogwheelNode node : visited) {
            if (!BnBKineticsCoreNodes.isModularSpellConstruct(level, node.pos())) {
                controllerTemplate = node;
                break;
            }
        }
        if (controllerTemplate == null) {
            ci.cancel();
            return;
        }

        ci.cancel();
        // Root the stored offsets at the actual controller, not at whichever
        // node the player happened to start on — otherwise member offsets and
        // rendered geometry point at a position with no chain behaviour.
        BlockPos controllerPos = controllerTemplate.pos();
        int chainsRequired = placingChain.getChainsRequiredInLoop(getChainType());

        // Mirrors upstream placeInLevel: detach kinetics up front so Create's
        // BFS propagator cannot re-propagate through stale chain links while
        // nodes are re-placed one by one.
        for (PlacingCogwheelNode node : visited) {
            if (level.getBlockEntity(node.pos()) instanceof KineticBlockEntity kbe) {
                kbe.detachKinetics();
            }
        }

        boolean isController = true;
        for (PlacingCogwheelNode node : visited) {
            if (BnBKineticsCoreNodes.isModularSpellConstruct(level, node.pos()))
                continue; // not a real cogwheel — nothing to attach
            createmanaindustry$invokePlaceChainCogwheelInLevel(level, node, isController,
                    chainsRequired, controllerPos, isCreative);
            isController = false;
        }

        final BlockEntity be = level.getBlockEntity(controllerPos);
        if (be instanceof KineticBlockEntity kbe) {
            kbe.updateSpeed = true; // force speed re-evaluation, as upstream does
        }
    }
}
