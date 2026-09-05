package com.iridium126.createmanaindustry.mixin.allvr;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import net.minecraft.client.multiplayer.prediction.BlockStatePredictionHandler;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.state.BlockState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Cube-safe keys for the client block-state prediction handler (doc §2.4).
 * <p>
 * {@code BlockStatePredictionHandler} keys its {@code serverVerifiedStates}
 * by {@code BlockPos#asLong()}, whose Y packing is only 12 bit — inside the
 * allay dimension (|Y| &gt; 2032) two predictions 4096 blocks apart in Y
 * alias to the same key. The insert/lookup paths are self-consistent under
 * that aliasing (same position → same key), so they need no changes; the one
 * real defect is {@code endPredictionsUpTo} decoding the key back via
 * {@code BlockPos.of(long)}: an aliased key decodes to a random Y inside the
 * vanilla window and {@code ClientLevel#syncBlockState} then writes the
 * server-verified state to that wrong position (and can {@code absMoveTo}
 * the player standing there).
 * <p>
 * Fix: a side map records the true position under the (possibly aliased) key
 * at insert time; the single {@code BlockPos.of} call site in
 * {@code endPredictionsUpTo} prefers the recorded position and only falls
 * back to the key decode when no record exists (bit-identical for in-window
 * positions, so vanilla dimensions are unaffected — they never produce
 * out-of-window predictions). Entries leave both maps together: the side map
 * is consumed (removed) exactly where the vanilla loop removes the main-map
 * entry, and the handler is per-{@code ClientLevel}, so the map's lifetime
 * matches the level's.
 */
@Mixin(BlockStatePredictionHandler.class)
public abstract class AllvrPredictionHandlerMixin {

    @Unique
    private final Long2ObjectOpenHashMap<BlockPos> allvr$posByKey = new Long2ObjectOpenHashMap<>();

    @Inject(method = "retainKnownServerState", at = @At("TAIL"))
    private void allvr$recordPosition(BlockPos pos, BlockState state, LocalPlayer player, CallbackInfo ci) {
        this.allvr$posByKey.put(pos.asLong(), pos.immutable());
    }

    @WrapOperation(
        method = "endPredictionsUpTo",
        at = @At(value = "INVOKE", target = "Lnet/minecraft/core/BlockPos;of(J)Lnet/minecraft/core/BlockPos;")
    )
    private BlockPos allvr$decodeRecordedPosition(long key, Operation<BlockPos> original) {
        BlockPos recorded = this.allvr$posByKey.remove(key);
        return recorded != null ? recorded : original.call(key);
    }
}
