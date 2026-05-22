package com.iridium126.createtricks.mixin;

import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import com.iridium126.createtricks.trickster.TricksterReflection;

import net.minecraft.world.level.block.entity.BlockEntity;

@Mixin(targets = "dev.enjarai.trickster.spell.trick.func.LoadArgumentTrick")
public class LoadArgumentTrickMixin {
	@Shadow
	@Final
	private int index;

	@Inject(method = "load", at = @At("HEAD"), cancellable = true)
	private void createtricks$loadDisplayArgument(Object ctx, CallbackInfoReturnable<Object> cir) {
		Object source = getSpellSource(ctx);
		BlockEntity blockEntity = TricksterReflection.getBlockEntityFromSource(source);
		if (blockEntity == null)
			return;

		Object fragment = TricksterReflection.getDisplayArgument(blockEntity, index);
		if (fragment != null)
			cir.setReturnValue(fragment);
	}

	private static Object getSpellSource(Object ctx) {
		try {
			return ctx.getClass().getMethod("source").invoke(ctx);
		} catch (ReflectiveOperationException e) {
			return null;
		}
	}
}
