package com.iridium126.createmanaindustry.content.kinetics.bnb;

import com.cake.azimuth.behaviour.SuperBlockEntityBehaviour;
import com.iridium126.createmanaindustry.CreateManaIndustry;
import com.kipti.bnb.content.kinetics.cogwheel_chain.behaviour.CogwheelChainBehaviour;
import com.kipti.bnb.content.kinetics.cogwheel_chain.graph.CogwheelChain;
import com.kipti.bnb.content.kinetics.cogwheel_chain.graph.PathedCogwheelNode;
import com.kipti.bnb.content.kinetics.cogwheel_chain.graph.ResidualChainResult;

import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.level.BlockEvent;

/**
 * Handles Spell Construct chain membership when the block is broken, mirroring
 * BnB native {@code CogwheelChainBehaviour.onBlockBroken} behaviour.
 * <p>
 * When a Modular Spell Construct is broken this handler scans for nearby
 * chain controllers that include the broken position, attempts to rebuild a
 * residual chain that excludes the broken node, and either places the
 * residual chain or destroys the chain entirely.
 */
@EventBusSubscriber(modid = CreateManaIndustry.MODID)
public final class SpellConstructChainHandler {

    private static final int CHAIN_SEARCH_RADIUS = 16;

    private SpellConstructChainHandler() {}

    @SubscribeEvent
    public static void onBlockBreak(final BlockEvent.BreakEvent event) {
        if (!CreateManaIndustry.TRICKSTER_ACTIVE || !CreateManaIndustry.BNB_ACTIVE)
            return;
        Level level = (Level) event.getLevel();
        if (level.isClientSide())
            return;

        BlockPos pos = event.getPos();
        if (!BnBKineticsCoreNodes.isModularSpellConstructBlock(event.getState().getBlock()))
            return;

        Player player = event.getPlayer();
        boolean isCreative = player != null && player.hasInfiniteMaterials();

        for (BlockPos checkPos : BlockPos.betweenClosed(
                pos.offset(-CHAIN_SEARCH_RADIUS, -CHAIN_SEARCH_RADIUS, -CHAIN_SEARCH_RADIUS),
                pos.offset(CHAIN_SEARCH_RADIUS, CHAIN_SEARCH_RADIUS, CHAIN_SEARCH_RADIUS))) {

            BlockEntity be = level.getBlockEntity(checkPos);
            if (be == null)
                continue;

            CogwheelChainBehaviour behaviour = SuperBlockEntityBehaviour
                    .getOptional(level, checkPos, CogwheelChainBehaviour.TYPE).orElse(null);
            if (behaviour == null || !behaviour.isController())
                continue;

            CogwheelChain chain = behaviour.getControlledChain();
            if (chain == null)
                continue;

            // Check if this controller's chain includes the broken position
            boolean containsBrokenPos = false;
            for (PathedCogwheelNode node : chain.getChainPathCogwheelNodes()) {
                if (checkPos.offset(node.localPos()).equals(pos)) {
                    containsBrokenPos = true;
                    break;
                }
            }
            if (!containsBrokenPos)
                continue;

            ResidualChainResult result = ResidualChainResult
                    .tryBuildResidualChain(chain, checkPos, pos);

            if (result != null) {
                int oldCost = chain.getChainsRequired();
                int newCost = result.placingChain()
                        .getChainsRequiredInLoop(chain.getChainType());
                int costDifference = oldCost - newCost;

                // Destroy old chain without dropping items
                behaviour.destroyChain(false, true);

                if (result.chain().checkIntegrity(level,
                        result.placingChain().getNodes().getFirst().pos())) {
                    result.chain().placeInLevel(level, result.placingChain(),
                            isCreative);
                }

                // Refund the cost difference
                if (!isCreative && costDifference > 0) {
                    ItemStack drops = chain.getReturnedItem().getDefaultInstance()
                            .copyWithCount(costDifference);
                    Block.popResource(level, pos, drops);
                }
            } else {
                behaviour.destroyChain(!isCreative, true);
            }
        }
    }
}
