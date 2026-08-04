package com.iridium126.createmanaindustry.mixin.kinetics;

import java.util.List;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import com.iridium126.createmanaindustry.content.kinetics.TemporaryStress;
import com.simibubi.create.content.kinetics.base.KineticBlockEntity;

import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.world.level.block.entity.BlockEntity;

/**
 * Temporary stress (kinetic spell) support for kinetic block entities:
 * <ul>
 *   <li>Adds temporary speed / stress capacity and source tracking.</li>
 *   <li>Syncs temporary stress state in client packets (write/read).</li>
 * </ul>
 * <p>
 * Field accessors live in {@link KineticBlockEntityAccessor} — applied mixin
 * classes cannot be loaded as regular classes at runtime, so external code
 * must go through the interface.
 */
@Mixin(value = KineticBlockEntity.class, remap = false)
public class KineticBlockEntityMixin {

    @Inject(method = "getGeneratedSpeed", at = @At("RETURN"), cancellable = true)
    private void createmanaindustry$addTemporaryGeneratedSpeed(CallbackInfoReturnable<Float> cir) {
        KineticBlockEntity be = (KineticBlockEntity) (Object) this;
        float speed = TemporaryStress.getSpeed(be);
        if (speed != 0)
            cir.setReturnValue(speed);
    }

    @Inject(method = "calculateAddedStressCapacity", at = @At("RETURN"), cancellable = true)
    private void createmanaindustry$addTemporaryStressCapacity(CallbackInfoReturnable<Float> cir) {
        KineticBlockEntity be = (KineticBlockEntity) (Object) this;
        cir.setReturnValue(cir.getReturnValueF() + TemporaryStress.getStress(be));
    }

    @Inject(method = "isSource", at = @At("RETURN"), cancellable = true)
    private void createmanaindustry$useTemporarySource(CallbackInfoReturnable<Boolean> cir) {
        KineticBlockEntity be = (KineticBlockEntity) (Object) this;
        if (TemporaryStress.isSource(be))
            cir.setReturnValue(true);
    }

    @Inject(method = "removeSource", at = @At("HEAD"))
    private void createmanaindustry$rememberTemporarySource(CallbackInfo ci) {
        KineticBlockEntity be = (KineticBlockEntity) (Object) this;
        TemporaryStress.removeSource(be);
    }

    @Inject(method = "setSource", at = @At("RETURN"))
    private void createmanaindustry$updateTemporaryReactivation(BlockPos source, CallbackInfo ci) {
        KineticBlockEntity be = (KineticBlockEntity) (Object) this;
        if (source == null || be.getLevel() == null)
            return;
        BlockEntity sourceBE = be.getLevel()
            .getBlockEntity(source);
        TemporaryStress.setSource(be, sourceBE);
    }

    @Inject(method = "tick", at = @At("RETURN"))
    private void createmanaindustry$tickTemporarySource(CallbackInfo ci) {
        TemporaryStress.tickBlockEntity((KineticBlockEntity) (Object) this);
    }

    @Inject(method = "addToGoggleTooltip", at = @At("RETURN"), cancellable = true)
    private void createmanaindustry$addTemporaryGeneratorStats(List<Component> tooltip, boolean isPlayerSneaking,
            CallbackInfoReturnable<Boolean> cir) {
        KineticBlockEntity be = (KineticBlockEntity) (Object) this;
        if (TemporaryStress.addToGoggleTooltip(be, tooltip))
            cir.setReturnValue(true);
    }

    @Inject(method = "write", at = @At("RETURN"))
    private void createmanaindustry$writeTemporaryStress(CompoundTag compound, HolderLookup.Provider registries,
            boolean clientPacket, CallbackInfo ci) {
        if (clientPacket)
            TemporaryStress.writeClient((KineticBlockEntity) (Object) this, compound);
    }

    @Inject(method = "read", at = @At("RETURN"))
    private void createmanaindustry$readTemporaryStress(CompoundTag compound, HolderLookup.Provider registries,
            boolean clientPacket, CallbackInfo ci) {
        if (clientPacket)
            TemporaryStress.readClient((KineticBlockEntity) (Object) this, compound);
    }
}
