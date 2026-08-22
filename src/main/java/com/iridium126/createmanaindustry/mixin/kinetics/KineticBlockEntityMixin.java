package com.iridium126.createmanaindustry.mixin.kinetics;

import java.util.List;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import com.iridium126.createmanaindustry.content.kinetics.temporarykinetics.TemporaryKinetics;
import com.simibubi.create.content.kinetics.base.KineticBlockEntity;

import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;

/**
 * Temporary kinetics support for kinetic block entities:
 * <ul>
 *   <li>Adds temporary speed / stress capacity and source tracking.</li>
 *   <li>Syncs temporary kinetics state in client packets (write/read).</li>
 *   <li>Drops the client-mirrored state when the block entity is removed.</li>
 * </ul>
 * <p>
 * Field accessors live in {@link KineticBlockEntityAccessor} — applied mixin
 * classes cannot be loaded as regular classes at runtime, so external code
 * must go through the interface.
 * <p>
 * Priority 1100 (default 1000): mixins apply in ascending priority order and
 * injection callbacks run in application order, so this mixin's cancellable
 * RETURN hooks on {@code getGeneratedSpeed} / {@code calculateAddedStressCapacity}
 * are evaluated last and a temporary override deterministically wins over
 * same-point modifications from other addons instead of depending on mod load
 * order.
 */
@Mixin(value = KineticBlockEntity.class, remap = false, priority = 1100)
public class KineticBlockEntityMixin {

    @Inject(method = "getGeneratedSpeed", at = @At("RETURN"), cancellable = true)
    private void createmanaindustry$addTemporaryGeneratedSpeed(CallbackInfoReturnable<Float> cir) {
        KineticBlockEntity be = (KineticBlockEntity) (Object) this;
        float speed = TemporaryKinetics.getSpeed(be);
        if (speed != 0)
            cir.setReturnValue(speed);
    }

    // Note: no isSource hook needed — Create's implementation derives it from
    // getGeneratedSpeed(), which the hook above already overrides.

    @Inject(method = "calculateAddedStressCapacity", at = @At("RETURN"), cancellable = true)
    private void createmanaindustry$addTemporaryKineticsCapacity(CallbackInfoReturnable<Float> cir) {
        KineticBlockEntity be = (KineticBlockEntity) (Object) this;
        cir.setReturnValue(cir.getReturnValueF() + TemporaryKinetics.getStress(be));
    }

    @Inject(method = "removeSource", at = @At("HEAD"))
    private void createmanaindustry$rememberTemporarySource(CallbackInfo ci) {
        KineticBlockEntity be = (KineticBlockEntity) (Object) this;
        TemporaryKinetics.removeSource(be);
    }

    @Inject(method = "setSource", at = @At("RETURN"))
    private void createmanaindustry$updateTemporaryReactivation(BlockPos source, CallbackInfo ci) {
        KineticBlockEntity be = (KineticBlockEntity) (Object) this;
        if (source == null || be.getLevel() == null)
            return;
        BlockEntity sourceBE = be.getLevel()
            .getBlockEntity(source);
        TemporaryKinetics.setSource(be, sourceBE);
    }

    @Inject(method = "tick", at = @At("RETURN"))
    private void createmanaindustry$tickTemporarySource(CallbackInfo ci) {
        TemporaryKinetics.tickBlockEntity((KineticBlockEntity) (Object) this);
    }

    @Inject(method = "addToGoggleTooltip", at = @At("RETURN"), cancellable = true)
    private void createmanaindustry$addTemporaryGeneratorStats(List<Component> tooltip, boolean isPlayerSneaking,
            CallbackInfoReturnable<Boolean> cir) {
        KineticBlockEntity be = (KineticBlockEntity) (Object) this;
        if (TemporaryKinetics.addToGoggleTooltip(be, tooltip))
            cir.setReturnValue(true);
    }

    @Inject(method = "write", at = @At("RETURN"))
    private void createmanaindustry$writeTemporaryKinetics(CompoundTag compound, HolderLookup.Provider registries,
            boolean clientPacket, CallbackInfo ci) {
        if (clientPacket)
            TemporaryKinetics.writeClient((KineticBlockEntity) (Object) this, compound);
    }

    @Inject(method = "read", at = @At("RETURN"))
    private void createmanaindustry$readTemporaryKinetics(CompoundTag compound, HolderLookup.Provider registries,
            boolean clientPacket, CallbackInfo ci) {
        if (clientPacket)
            TemporaryKinetics.readClient((KineticBlockEntity) (Object) this, compound);
    }

    /**
     * Client mirror cleanup: the mirrored store entry must die together with
     * the block entity. Without this, a block broken mid-effect leaves its
     * client-side state behind until the position is re-synced or the
     * dimension changes — server expiry packets only cover blocks that
     * outlive their effect. The server entry is intentionally kept: it is a
     * harmless countdown, and eager removal would break temporary states on
     * contraptions (assembly removes and later recreates the block entity).
     */
    @Inject(method = "remove", at = @At("HEAD"))
    private void createmanaindustry$clearTemporaryKineticsOnRemove(CallbackInfo ci) {
        KineticBlockEntity be = (KineticBlockEntity) (Object) this;
        Level level = be.getLevel();
        if (level != null && level.isClientSide)
            TemporaryKinetics.clearClient(be);
    }
}
