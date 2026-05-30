package com.iridium126.createtricks.mixin;

import org.jetbrains.annotations.Nullable;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import com.iridium126.createtricks.content.kinetics.TemporaryStressModel;
import com.simibubi.create.AllBlocks;
import com.simibubi.create.content.kinetics.base.KineticBlockEntity;
import com.simibubi.create.content.kinetics.base.KineticBlockEntityRenderer;
import com.simibubi.create.content.kinetics.simpleRelays.AbstractSimpleShaftBlock;

import net.createmod.catnip.render.CachedBuffers;
import net.createmod.catnip.render.SuperByteBuffer;
import net.minecraft.core.Direction;
import net.minecraft.world.level.block.state.BlockState;

@Mixin(value = KineticBlockEntityRenderer.class, remap = false)
public class KineticBlockEntityRendererMixin<T extends KineticBlockEntity> {
	@Inject(method = "getRotatedModel", at = @At("HEAD"), cancellable = true)
	private void createtricks$useStressedPartial(T be, BlockState state, CallbackInfoReturnable<SuperByteBuffer> cir) {
		@Nullable
		var partial = TemporaryStressModel.rotatingBlockModel(be);
		if (partial == null)
			return;

		if (AllBlocks.COGWHEEL.is(state.getBlock())) {
			Direction direction = Direction.fromAxisAndDirection(
					state.getValue(AbstractSimpleShaftBlock.AXIS), Direction.AxisDirection.POSITIVE);
			cir.setReturnValue(CachedBuffers.partialFacingVertical(partial, state, direction));
			return;
		}

		cir.setReturnValue(CachedBuffers.partial(partial, state));
	}
}
