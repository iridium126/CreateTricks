package com.iridium126.createmanaindustry.mixin.fluids;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.ModifyVariable;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import com.iridium126.createmanaindustry.CMIFluids;
import com.iridium126.createmanaindustry.content.fluids.condenser.CondenserBlockEntity;
import com.simibubi.create.content.fluids.FluidNetwork;

import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.neoforged.neoforge.fluids.FluidStack;

/**
 * Gives the Condenser true transparent pass-through consumption: instead of
 * exposing a fluid capability (which would make Create's pipe network treat the
 * condenser as a tank terminal and block through-flow), the coolant cost of
 * condensing is charged directly against the passing pipe flow inside the
 * network's transfer accounting.
 * <p>
 * In {@code FluidNetwork.tick()} the source is drained in full and the amount is
 * split across targets. We inject at the target-distribution point and shrink
 * {@code transfer} by whatever {@link CondenserBlockEntity#consumeFromFlow(int)}
 * claims, so the source loses the full amount while the targets receive
 * {@code full - consumed} — the difference is the coolant actually consumed.
 * <p>
 * The condenser claims its demand once per game tick (its own guard), so the
 * SIMULATE pass records which condensers this network claimed from and the
 * EXECUTE pass replays the same amounts — keeping both passes consistent and
 * preventing two networks passing through the same condenser from double-charging.
 */
@Mixin(value = FluidNetwork.class, remap = false)
public abstract class FluidNetworkMixin {

    @Shadow
    private Level world;
    @Shadow
    private Set<BlockPos> visited;
    @Shadow
    private FluidStack fluid;

    /** Counts SIMULATE (0) vs EXECUTE (1) invocation within one {@code tick()} call. */
    private int createmanaindustry$passCount = 0;
    /** Condensers this network claimed from during its SIMULATE pass. */
    private final List<CondenserBlockEntity> createmanaindustry$claimedThisNetwork = new ArrayList<>();
    /** Cached condensers on this network's path, rebuilt only when {@code visited} changes. */
    private List<CondenserBlockEntity> createmanaindustry$condenserCache = new ArrayList<>();
    /** Size of {@code visited} when {@code condenserCache} was last rebuilt. */
    private int createmanaindustry$cachedVisitedSize = -1;

    @Inject(method = "tick", at = @At("HEAD"))
    private void createmanaindustry$resetPassState(CallbackInfo ci) {
        createmanaindustry$passCount = 0;
        createmanaindustry$claimedThisNetwork.clear();
    }

    /**
     * Runs twice per tick (SIMULATE then EXECUTE) at the target-distribution
     * point, where {@code transfer} holds the fluid drained from the source.
     */
    @ModifyVariable(method = "tick",
            at = @At(value = "INVOKE",
                    target = "Ljava/util/ArrayList;<init>(Ljava/util/Collection;)V",
                    ordinal = 0),
            ordinal = 0)
    private FluidStack createmanaindustry$consumeCoolantFromFlow(FluidStack transfer) {
        if (transfer.isEmpty())
            return transfer;
        // Only condense from coolant; leave other fluids untouched.
        if (!CMIFluids.COOLANT.is(fluid.getFluid()))
            return transfer;

        boolean simulate = createmanaindustry$passCount == 0;
        createmanaindustry$passCount++;

        if (simulate) {
            // Claim from condensers on this network's path, capped by the flow.
            int available = transfer.getAmount();
            for (CondenserBlockEntity condenser : findCondensers()) {
                int c = condenser.consumeFromFlow(available);
                if (c > 0) {
                    createmanaindustry$claimedThisNetwork.add(condenser);
                    available -= c;
                }
                if (available <= 0)
                    break;
            }
            int consumed = transfer.getAmount() - available;
            if (consumed > 0)
                transfer.shrink(consumed);
        } else {
            // Replay the exact amounts claimed in SIMULATE so both passes agree.
            for (CondenserBlockEntity condenser : createmanaindustry$claimedThisNetwork) {
                int c = Math.min(condenser.getConsumed(), transfer.getAmount());
                if (c > 0)
                    transfer.shrink(c);
            }
        }
        return transfer;
    }

    /**
     * Returns the condensers on this network's path. {@code visited} only grows
     * for the lifetime of a network — rebuilds create a fresh {@code FluidNetwork}
     * (see {@code PipeConnection}) — so the result is cached and rescanned only
     * when its size changes, turning an O(visited) block-entity scan into an
     * O(1) cache hit for the steady state. Removed condensers are harmless:
     * {@code CondenserBlockEntity.consumeFromFlow} guards on {@code level == null}.
     */
    private List<CondenserBlockEntity> findCondensers() {
        if (createmanaindustry$cachedVisitedSize != visited.size())
            createmanaindustry$rescanCondensers();
        return createmanaindustry$condenserCache;
    }

    private void createmanaindustry$rescanCondensers() {
        List<CondenserBlockEntity> result = new ArrayList<>();
        for (BlockPos pos : visited) {
            BlockEntity be = world.getBlockEntity(pos);
            if (be instanceof CondenserBlockEntity condenser)
                result.add(condenser);
        }
        createmanaindustry$condenserCache = result;
        createmanaindustry$cachedVisitedSize = visited.size();
    }
}
