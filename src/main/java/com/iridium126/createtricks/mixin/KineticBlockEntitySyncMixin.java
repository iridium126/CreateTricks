package com.iridium126.createtricks.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import com.iridium126.createtricks.content.kinetics.TemporaryStress;
import com.simibubi.create.content.kinetics.base.KineticBlockEntity;

import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;

@Mixin(value = KineticBlockEntity.class, remap = false)
public class KineticBlockEntitySyncMixin {
	@Inject(method = "write", at = @At("RETURN"))
	private void createtricks$writeTemporaryStress(CompoundTag compound, HolderLookup.Provider registries,
			boolean clientPacket, CallbackInfo ci) {
		if (clientPacket)
			TemporaryStress.writeClient((KineticBlockEntity) (Object) this, compound);
	}

	@Inject(method = "read", at = @At("RETURN"))
	private void createtricks$readTemporaryStress(CompoundTag compound, HolderLookup.Provider registries,
			boolean clientPacket, CallbackInfo ci) {
		if (clientPacket)
			TemporaryStress.readClient((KineticBlockEntity) (Object) this, compound);
	}
}
