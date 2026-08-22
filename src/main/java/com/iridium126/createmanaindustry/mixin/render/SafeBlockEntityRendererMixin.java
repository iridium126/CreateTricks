package com.iridium126.createmanaindustry.mixin.render;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import com.iridium126.createmanaindustry.content.kinetics.temporarykinetics.TemporaryKineticsRenderContext;
import com.mojang.blaze3d.vertex.PoseStack;
import com.simibubi.create.content.kinetics.base.KineticBlockEntity;
import com.simibubi.create.foundation.blockEntity.renderer.SafeBlockEntityRenderer;

import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.world.level.block.entity.BlockEntity;

@Mixin(value = SafeBlockEntityRenderer.class, remap = false)
public class SafeBlockEntityRendererMixin<T extends BlockEntity> {

    @Inject(method = "render", at = @At("HEAD"))
    private void createmanaindustry$setRenderedKineticBlockEntity(T be, float partialTicks, PoseStack ms,
            MultiBufferSource buffer, int light, int overlay, CallbackInfo ci) {
        if (be instanceof KineticBlockEntity)
            TemporaryKineticsRenderContext.set(be);
    }

    @Inject(method = "render", at = @At("RETURN"))
    private void createmanaindustry$clearRenderedKineticBlockEntity(T be, float partialTicks, PoseStack ms,
            MultiBufferSource buffer, int light, int overlay, CallbackInfo ci) {
        if (be instanceof KineticBlockEntity)
            TemporaryKineticsRenderContext.clear(be);
    }
}
