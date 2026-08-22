package com.iridium126.createmanaindustry.mixin.bnb;

import java.util.ArrayList;
import java.util.List;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.Level;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

import com.iridium126.createmanaindustry.content.kinetics.bnb.BnBKineticsCoreNodes;
import com.kipti.bnb.content.kinetics.cogwheel_chain.graph.PathedCogwheelNode;
import com.kipti.bnb.content.kinetics.cogwheel_chain.graph.PlacingCogwheelChain;
import com.kipti.bnb.content.kinetics.cogwheel_chain.graph.PlacingCogwheelNode;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.llamalad7.mixinextras.sugar.Local;

@Mixin(targets = "com.kipti.bnb.network.packets.from_client.PlaceCogwheelChainPacket", remap = false)
public abstract class PlaceCogwheelChainPacketMixin {

    /**
     * Server-side path building with a spell-construct fallback. The native
     * geometry walker cannot traverse spell nodes, so when it fails and the
     * chain actually contains one, the path is rebuilt manually — straight
     * node-to-node segments around the loop. Offsets are rooted at the first
     * non-spell node so they match the controller position used by
     * {@code CogwheelChainMixin#placeInLevel} ("controller == origin").
     * <p>
     * Wrapped as a {@link WrapOperation} instead of a redirect: it composes
     * with other addons hooking the same call, and the player arrives via
     * {@code @Local} instead of a thread-local that could leak across packets
     * when {@code handle} throws.
     */
    @WrapOperation(method = "handle",
        at = @At(value = "INVOKE",
            target = "Lcom/kipti/bnb/content/kinetics/cogwheel_chain/graph/CogwheelChainPathfinder;buildChainPath(Lcom/kipti/bnb/content/kinetics/cogwheel_chain/graph/PlacingCogwheelChain;)Ljava/util/List;"),
        remap = false)
    private List<PathedCogwheelNode> createmanaindustry$buildChainPathWithSpellFallback(
            PlacingCogwheelChain placingChain,
            Operation<List<PathedCogwheelNode>> original,
            @Local(argsOnly = true) ServerPlayer player) {
        List<PathedCogwheelNode> result = null;
        try {
            result = original.call(placingChain);
        } catch (Exception e) {
            // Native failure — normally ChainInteractionFailedException, which
            // the surrounding upstream handler would log and swallow anyway.
            // Returning null below reproduces exactly that abort path.
            result = null;
        }
        if (result != null)
            return result;

        Level level = player.level();
        if (!BnBKineticsCoreNodes.containsSpellNode(level, placingChain.getVisitedNodes()))
            return null; // genuine native failure — abort like upstream

        return createmanaindustry$manualBuildChainPath(level, placingChain);
    }

    /**
     * Straight-segment fallback path. Sides simply alternate (+1/-1); this is
     * an approximation of the native geometric side assignment, only used for
     * chains the native pathfinder cannot walk.
     */
    private static List<PathedCogwheelNode> createmanaindustry$manualBuildChainPath(
            Level level, PlacingCogwheelChain chain) {
        List<PlacingCogwheelNode> visitedNodes = chain.getVisitedNodes();

        // First non-spell node is the controller/origin, matching the custom
        // placement in CogwheelChainMixin. (All-spell chains never reach this
        // point — both completion guards reject them earlier.)
        BlockPos controllerPos = visitedNodes.getFirst().pos();
        for (PlacingCogwheelNode node : visitedNodes) {
            if (!BnBKineticsCoreNodes.isModularSpellConstruct(level, node.pos())) {
                controllerPos = node.pos();
                break;
            }
        }

        List<PathedCogwheelNode> pathNodes = new ArrayList<>();
        int side = 1;
        for (PlacingCogwheelNode node : visitedNodes) {
            pathNodes.add(new PathedCogwheelNode(side, node.isLarge(), node.rotationAxis(),
                node.pos().subtract(controllerPos), node.hasSmallCogwheelOffset()));
            side *= -1;
        }
        return pathNodes;
    }
}
