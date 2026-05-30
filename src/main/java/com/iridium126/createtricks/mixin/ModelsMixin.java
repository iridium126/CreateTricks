package com.iridium126.createtricks.mixin;

import java.util.function.BiConsumer;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import com.iridium126.createtricks.content.kinetics.TemporaryStressVisualModels;
import com.mojang.blaze3d.vertex.PoseStack;

import dev.engine_room.flywheel.api.model.Model;
import dev.engine_room.flywheel.lib.model.Models;
import dev.engine_room.flywheel.lib.model.baked.PartialModel;
import net.minecraft.core.Direction;

@Mixin(value = Models.class, remap = false)
public class ModelsMixin {
	@Inject(method = "partial(Ldev/engine_room/flywheel/lib/model/baked/PartialModel;)Ldev/engine_room/flywheel/api/model/Model;", at = @At("RETURN"))
	private static void createtricks$trackPartial(PartialModel partial, CallbackInfoReturnable<Model> cir) {
		TemporaryStressVisualModels.track(partial, cir.getReturnValue());
	}

	@Inject(method = "partial(Ldev/engine_room/flywheel/lib/model/baked/PartialModel;Lnet/minecraft/core/Direction;)Ldev/engine_room/flywheel/api/model/Model;", at = @At("RETURN"))
	private static void createtricks$trackFacingPartial(PartialModel partial, Direction direction,
			CallbackInfoReturnable<Model> cir) {
		TemporaryStressVisualModels.track(partial, cir.getReturnValue(),
				replacement -> Models.partial(replacement, direction));
	}

	@Inject(method = "partial(Ldev/engine_room/flywheel/lib/model/baked/PartialModel;Ljava/lang/Object;Ljava/util/function/BiConsumer;)Ldev/engine_room/flywheel/api/model/Model;", at = @At("RETURN"))
	private static <T> void createtricks$trackTransformedPartial(PartialModel partial, T data,
			BiConsumer<T, PoseStack> transformer, CallbackInfoReturnable<Model> cir) {
		TemporaryStressVisualModels.track(partial, cir.getReturnValue(),
				replacement -> Models.partial(replacement, data, transformer));
	}
}
