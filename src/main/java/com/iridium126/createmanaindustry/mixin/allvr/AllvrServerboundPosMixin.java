package com.iridium126.createmanaindustry.mixin.allvr;

import com.iridium126.createmanaindustry.CreateManaIndustry;
import com.iridium126.createmanaindustry.dimension.AllvrDimensionLimits;
import com.iridium126.createmanaindustry.dimension.AllvrDimensions;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import net.minecraft.core.BlockPos;
import net.minecraft.network.protocol.game.ServerboundPlayerActionPacket;
import net.minecraft.network.protocol.game.ServerboundSignUpdatePacket;
import net.minecraft.network.protocol.game.ServerboundUseItemOnPacket;
import net.minecraft.server.network.ServerGamePacketListenerImpl;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.Mth;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.BlockHitResult;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;

/**
 * Reconstructs the true block position and interaction bounds of client →
 * server actions inside the allay dimension (doc §2.4 阶段 4 兼容性批次⑤⑥).
 * <p>
 * <b>Wall 1 — 12-bit-Y wire aliasing.</b> Every vanilla packet carrying a
 * {@code BlockPos} writes it via {@code FriendlyByteBuf#writeBlockPos →
 * BlockPos#asLong}, whose Y packing is only 12 bit — a break/place at
 * Y=1,000,000 arrives at the server as Y=576 (1,000,000 &amp; 4095). The
 * server then runs the action at the aliased in-window position:
 * {@code canInteractWithBlock} rejects it (the player's eye is ~1M away) and
 * the action is silently dropped — no destroy, no authoritative cube packet.
 * The client's own prediction (full-precision in-memory positions) is then
 * reverted by the prediction ACK, which restores the pre-action state at the
 * real position: blocks bounce back instantly and placements pop off. X/Z
 * are exact on the wire (26 bit each); only Y folds.
 * <p>
 * The wire format is vanilla protocol and cannot be changed, so the server
 * reconstructs the true Y: the real position is always within interaction
 * reach of the player's eye, and exactly one Y in a ±64 window around the eye
 * is congruent to the wire Y (mod 4096 — the window is far narrower than the
 * modulus, so the reconstruction is unambiguous). The reconstruction is
 * uniform — there is no "decoded Y is small" shortcut, because the wire folds
 * EVERY position into [-2048, 2047] regardless of where the player stands
 * (an island at block Y≈9,632 decodes to 1,440); identity falls out of the
 * congruence math for genuine window-height interactions. Out-of-reach
 * positions (vanilla sends BlockPos.ZERO for DROP/RELEASE actions) keep the
 * decoded position.
 * <p>
 * <b>Wall 2 — the dimension_type window as interaction bound.</b> Both
 * handlers gate on {@code getMaxBuildHeight()} (break: {@code pos.getY() >=
 * maxBuildHeight} → revert + bounce via the aliased-key absorption; place:
 * {@code blockpos.getY() < i} → "build.tooHigh"). The window is a column
 * shell-size parameter (shrink to [-192, 191] by the "Optimize allay
 * dimension" commit — and 2031 before that), NOT the interaction bound; the
 * interaction bound of this dimension is {@link AllvrDimensionLimits#Y_BOUND}.
 * Inside the two handlers the bound is replaced by {@code Y_BOUND + 1}, so
 * the vanilla reject branches stay reachable exactly at the software edge.
 * The @At owner is {@code Level} — javac compiles a default-method call
 * through a class-typed receiver as {@code invokevirtual Level.getMaxBuildHeight}
 * (bytecode-verified against the 21.1.236 jar); targeting the declaring
 * interface {@code LevelHeightAccessor} scans 0 targets and crashes on
 * {@code defaultRequire = 1}.
 * <p>
 * Wrapped call sites (each handler reads the position / bound exactly once):
 * {@code handlePlayerAction} (break/dig — all actions), {@code
 * handleUseItemOn} (place/interact — the hit result's {@code location} is
 * wire-encoded as float deltas relative to the (aliased) block pos, so it is
 * shifted by the same delta to keep the {@code |location - center| < 1}
 * containment check meaningful), and {@code updateSignText} (sign editing).
 */
@Mixin(ServerGamePacketListenerImpl.class)
public abstract class AllvrServerboundPosMixin {

    @Shadow
    public ServerPlayer player;

    @WrapOperation(
        method = "handlePlayerAction",
        at = @At(value = "INVOKE",
            target = "Lnet/minecraft/network/protocol/game/ServerboundPlayerActionPacket;getPos()Lnet/minecraft/core/BlockPos;")
    )
    private BlockPos allvr$remapActionPos(ServerboundPlayerActionPacket packet, Operation<BlockPos> original) {
        return this.allvr$remap(original.call(packet));
    }

    @WrapOperation(
        method = "handlePlayerAction",
        at = @At(value = "INVOKE", target = "Lnet/minecraft/world/level/Level;getMaxBuildHeight()I")
    )
    private int allvr$actionMaxBuildHeight(Level level, Operation<Integer> original) {
        return this.allvr$maxBuildHeight(level, original);
    }

    @WrapOperation(
        method = "handleUseItemOn",
        at = @At(value = "INVOKE",
            target = "Lnet/minecraft/network/protocol/game/ServerboundUseItemOnPacket;getHitResult()Lnet/minecraft/world/phys/BlockHitResult;")
    )
    private BlockHitResult allvr$remapUseItemOn(ServerboundUseItemOnPacket packet, Operation<BlockHitResult> original) {
        BlockHitResult hit = original.call(packet);
        BlockPos remapped = this.allvr$remap(hit.getBlockPos());
        if (remapped == hit.getBlockPos()) {
            return hit;
        }
        // the wire stores the hit location as float deltas from the (aliased)
        // block pos — shift it by the same delta so the true location survives
        double dy = remapped.getY() - hit.getBlockPos().getY();
        return new BlockHitResult(hit.getLocation().add(0.0, dy, 0.0), hit.getDirection(), remapped, hit.isInside());
    }

    @WrapOperation(
        method = "handleUseItemOn",
        at = @At(value = "INVOKE", target = "Lnet/minecraft/world/level/Level;getMaxBuildHeight()I")
    )
    private int allvr$useItemOnMaxBuildHeight(Level level, Operation<Integer> original) {
        return this.allvr$maxBuildHeight(level, original);
    }

    @WrapOperation(
        method = "updateSignText",
        at = @At(value = "INVOKE",
            target = "Lnet/minecraft/network/protocol/game/ServerboundSignUpdatePacket;getPos()Lnet/minecraft/core/BlockPos;")
    )
    private BlockPos allvr$remapSignPos(ServerboundSignUpdatePacket packet, Operation<BlockPos> original) {
        return this.allvr$remap(original.call(packet));
    }

    /**
     * Unique Y reconstruction: {@code candidate = wireY + 4096·k} for the one
     * {@code k} landing within ±64 of the player's eye Y. Vanilla dimensions
     * are untouched (dimension gate).
     * <p>
     * NB: there is deliberately NO "decoded Y is small → identity" shortcut.
     * The wire folds Y into [-2048, 2047] for EVERY position — an island at
     * block Y≈9,632 decodes to 1,440 — so magnitude says nothing about
     * whether the decoded position is the true one. The congruence
     * reconstruction is identity exactly when the interaction really is at
     * window height (candidate == decoded), so it is applied uniformly.
     */
    @Unique
    private BlockPos allvr$remap(BlockPos pos) {
        if (this.player.level().dimension() != AllvrDimensions.ALLAY_LEVEL) {
            return pos;
        }
        int eyeY = Mth.floor(this.player.getEyePosition().y);
        int candidate = pos.getY() + 4096 * Math.floorDiv(eyeY - pos.getY() + 2048, 4096);
        if (Math.abs(candidate - eyeY) <= 64) {
            return candidate == pos.getY() ? pos : new BlockPos(pos.getX(), candidate, pos.getZ());
        }
        // vanilla itself sends BlockPos.ZERO for RELEASE_USE_ITEM / DROP_ITEM
        // (position unused) — out-of-reach positions are normal here, so debug only
        CreateManaIndustry.LOGGER.debug(
            "[Allvr] client action at wire pos {} has no true Y within ±64 of the player eye (y={}) — using decoded position",
            pos, eyeY);
        return pos;
    }

    /**
     * Interaction bound replacement (wall 2): the dimension_type window is
     * the column shell size, not the play area — the vanilla reject branches
     * ("too high" revert / "build.tooHigh") must only fire at the software
     * edge, so the handlers see {@code Y_BOUND + 1} (exclusive bound → the
     * last interactive block is exactly Y_BOUND).
     */
    @Unique
    private int allvr$maxBuildHeight(Level level, Operation<Integer> original) {
        if (level.dimension() != AllvrDimensions.ALLAY_LEVEL) {
            return original.call(level);
        }
        return AllvrDimensionLimits.Y_BOUND + 1;
    }
}
