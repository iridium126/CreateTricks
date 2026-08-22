package com.iridium126.createmanaindustry.mixin.bnb;

import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.world.phys.BlockHitResult;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import com.iridium126.createmanaindustry.content.kinetics.bnb.BnBKineticsCoreNodes;
import com.kipti.bnb.content.kinetics.cogwheel_chain.graph.CogwheelChainPathfinder;
import com.kipti.bnb.content.kinetics.cogwheel_chain.graph.PlacingCogwheelChain;
import com.kipti.bnb.content.kinetics.cogwheel_chain.graph.PlacingCogwheelNode;
import com.kipti.bnb.content.kinetics.cogwheel_chain.placement.ChainInteractionFailedException;
import com.kipti.bnb.content.kinetics.cogwheel_chain.placement.CogwheelChainPlacementInteraction;

@Mixin(targets = "com.kipti.bnb.content.kinetics.cogwheel_chain.placement.CogwheelChainPlacementInteraction", remap = false)
public abstract class CogwheelChainPlacementInteractionMixin {

    /**
     * Intercept right-clicks on spell construct blocks when the player is
     * holding a chain drive item or is already building a chain. If the spell
     * construct has no kinetics core, show an error — it cannot participate in
     * a chain without one. Everything else (candidate validity, type
     * predicate, geometric connection rules) flows through the native paths.
     */
    @Inject(method = "onClickInput",
        at = @At(value = "INVOKE",
            target = "Lcom/kipti/bnb/content/kinetics/cogwheel_chain/placement/CogwheelChainPlacementInteraction;onRightClick(Lnet/neoforged/neoforge/client/event/InputEvent$InteractionKeyMappingTriggered;)Z"),
        cancellable = true, remap = false)
    private static void createmanaindustry$handleSpellConstructInteraction(
        net.neoforged.neoforge.client.event.InputEvent.InteractionKeyMappingTriggered event,
        CallbackInfo ci) {

        Minecraft mc = Minecraft.getInstance();
        ClientLevel level = mc.level;
        if (level == null || !(mc.hitResult instanceof BlockHitResult hit))
            return;
        if (mc.player == null)
            return;

        BlockPos pos = hit.getBlockPos();

        // Only intercept when the player is holding a chain drive item or is
        // already building a chain — otherwise let the normal onRightClick
        // handle the interaction (e.g. open the spell construct inventory).
        boolean holdingChain = CogwheelChainPlacementInteraction.getChainItemInHand(mc.player) != null;
        boolean alreadyBuilding = CogwheelChainPlacementInteraction.getCurrentBuildingChain() != null;
        if (!holdingChain && !alreadyBuilding)
            return;

        if (!BnBKineticsCoreNodes.isModularSpellConstruct(level, pos))
            return;

        // Only block the click when the spell construct has no kinetics core
        // at all. Already-linked constructs pass through: the old chain is
        // destroyed by placeChainCogwheelInLevel when the new chain is placed,
        // matching BnB's native replacement behaviour.
        if (!BnBKineticsCoreNodes.hasAnyKineticsCore(level, pos)) {
            mc.player.displayClientMessage(
                Component.translatable("createmanaindustry.bnb_chain.no_core"), true);
            ci.cancel();
            event.setCanceled(true);
        }
    }

    /**
     * Loop closure for chains containing spell constructs. Mirrors the native
     * {@code tryCompleteLoop} step by step — including the trailing-duplicate
     * removal — but tolerates pathfinder failures caused by spell nodes, which
     * the native geometry walker cannot traverse; the server rebuilds those
     * segments itself on receipt. A chain made purely of spell constructs is
     * rejected: it has no kinetic member that could act as controller.
     */
    @Redirect(method = "rightClickForChain",
        at = @At(value = "INVOKE",
            target = "Lcom/kipti/bnb/content/kinetics/cogwheel_chain/graph/PlacingCogwheelChain;tryCompleteLoop()Z"),
        remap = false)
    private static boolean createmanaindustry$completeLoopWithSpellNodes(PlacingCogwheelChain chain)
            throws ChainInteractionFailedException {
        if (chain.getSize() < 2)
            return false;
        if (!chain.getFirstNode().pos().equals(chain.getLastNode().pos()))
            return false;

        chain.getNodes().removeLast(); // native dedup of the closure click

        try {
            if (CogwheelChainPathfinder.buildChainPath(chain) != null)
                return true; // pure-cogwheel chain closed and verified natively
        } catch (ChainInteractionFailedException ignored) {
            // fall through to spell-tolerant handling
        }

        ClientLevel level = Minecraft.getInstance().level;
        if (level == null || !BnBKineticsCoreNodes.containsSpellNode(level, chain.getVisitedNodes()))
            throw new ChainInteractionFailedException("pathfinding_failed");

        boolean hasRealCogwheel = false;
        for (PlacingCogwheelNode node : chain.getVisitedNodes()) {
            if (!BnBKineticsCoreNodes.isModularSpellConstruct(level, node.pos())) {
                hasRealCogwheel = true;
                break;
            }
        }
        if (!hasRealCogwheel)
            throw new ChainInteractionFailedException("pathfinding_failed");
        return true;
    }
}
