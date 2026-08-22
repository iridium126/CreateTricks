package com.iridium126.createmanaindustry.mixin.render;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.ModifyVariable;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import com.iridium126.createmanaindustry.content.kinetics.temporarykinetics.TemporaryKinetics;
import com.iridium126.createmanaindustry.content.kinetics.temporarykinetics.TemporaryKineticsModel;
import com.iridium126.createmanaindustry.content.kinetics.temporarykinetics.TemporaryKineticsRenderContext;
import com.simibubi.create.AllBlocks;
import com.simibubi.create.content.kinetics.base.IRotate;
import com.simibubi.create.content.kinetics.base.KineticBlockEntity;

import dev.engine_room.flywheel.lib.model.baked.PartialModel;
import net.createmod.catnip.render.CachedBuffers;
import net.createmod.catnip.render.SuperByteBuffer;
import net.createmod.catnip.render.SuperByteBufferCache;
import net.minecraft.core.Direction;
import net.minecraft.core.Direction.Axis;
import net.minecraft.core.Direction.AxisDirection;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;

@Mixin(value = CachedBuffers.class, remap = false)
public class CachedBuffersMixin {

    @ModifyVariable(method = "partial(Ldev/engine_room/flywheel/lib/model/baked/PartialModel;Lnet/minecraft/world/level/block/state/BlockState;)Lnet/createmod/catnip/render/SuperByteBuffer;", at = @At("HEAD"), argsOnly = true, ordinal = 0)
    private static PartialModel createmanaindustry$replacePartial(PartialModel partial) {
        return TemporaryKineticsRenderContext.replace(partial);
    }

    @ModifyVariable(method = "partial(Ldev/engine_room/flywheel/lib/model/baked/PartialModel;Lnet/minecraft/world/level/block/state/BlockState;Ljava/util/function/Supplier;)Lnet/createmod/catnip/render/SuperByteBuffer;", at = @At("HEAD"), argsOnly = true, ordinal = 0)
    private static PartialModel createmanaindustry$replaceTransformedPartial(PartialModel partial) {
        return TemporaryKineticsRenderContext.replace(partial);
    }

    @ModifyVariable(method = "partialFacing(Ldev/engine_room/flywheel/lib/model/baked/PartialModel;Lnet/minecraft/world/level/block/state/BlockState;)Lnet/createmod/catnip/render/SuperByteBuffer;", at = @At("HEAD"), argsOnly = true, ordinal = 0)
    private static PartialModel createmanaindustry$replaceFacingPartial(PartialModel partial) {
        return TemporaryKineticsRenderContext.replace(partial);
    }

    @ModifyVariable(method = "partialFacing(Ldev/engine_room/flywheel/lib/model/baked/PartialModel;Lnet/minecraft/world/level/block/state/BlockState;Lnet/minecraft/core/Direction;)Lnet/createmod/catnip/render/SuperByteBuffer;", at = @At("HEAD"), argsOnly = true, ordinal = 0)
    private static PartialModel createmanaindustry$replaceDirectedFacingPartial(PartialModel partial) {
        return TemporaryKineticsRenderContext.replace(partial);
    }

    @ModifyVariable(method = "partialFacingVertical(Ldev/engine_room/flywheel/lib/model/baked/PartialModel;Lnet/minecraft/world/level/block/state/BlockState;Lnet/minecraft/core/Direction;)Lnet/createmod/catnip/render/SuperByteBuffer;", at = @At("HEAD"), argsOnly = true, ordinal = 0)
    private static PartialModel createmanaindustry$replaceVerticalFacingPartial(PartialModel partial) {
        return TemporaryKineticsRenderContext.replace(partial);
    }

    @ModifyVariable(method = "partialDirectional(Ldev/engine_room/flywheel/lib/model/baked/PartialModel;Lnet/minecraft/world/level/block/state/BlockState;Lnet/minecraft/core/Direction;Ljava/util/function/Supplier;)Lnet/createmod/catnip/render/SuperByteBuffer;", at = @At("HEAD"), argsOnly = true, ordinal = 0)
    private static PartialModel createmanaindustry$replaceDirectionalPartial(PartialModel partial) {
        return TemporaryKineticsRenderContext.replace(partial);
    }

    @Inject(method = "block(Lnet/createmod/catnip/render/SuperByteBufferCache$Compartment;Lnet/minecraft/world/level/block/state/BlockState;)Lnet/createmod/catnip/render/SuperByteBuffer;", at = @At("HEAD"), cancellable = true)
    private static void createmanaindustry$replaceBlockBuffer(SuperByteBufferCache.Compartment<BlockState> compartment,
            BlockState toRender, CallbackInfoReturnable<SuperByteBuffer> cir) {
        BlockEntity be = TemporaryKineticsRenderContext.get();
        if (!(be instanceof KineticBlockEntity kinetic) || !TemporaryKinetics.isActive(kinetic))
            return;
        PartialModel model = TemporaryKineticsModel.rotatingBlockModel(kinetic);
        if (model == null && AllBlocks.SHAFT.has(toRender))
            model = TemporaryKineticsModel.shaft(kinetic);
        if (model == null)
            return;
        Axis axis = ((IRotate) kinetic.getBlockState().getBlock()).getRotationAxis(kinetic.getBlockState());
        Direction facing = Direction.fromAxisAndDirection(axis, AxisDirection.POSITIVE);
        cir.setReturnValue(CachedBuffers.partialFacingVertical(model, toRender, facing));
    }
}
