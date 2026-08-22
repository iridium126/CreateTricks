package com.iridium126.createmanaindustry.mixin.render;

import org.spongepowered.asm.mixin.Mixin;

import com.iridium126.createmanaindustry.content.kinetics.temporarykinetics.TemporaryKineticsRenderContext;
import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.mojang.blaze3d.vertex.PoseStack;
import com.simibubi.create.content.kinetics.base.KineticBlockEntity;
import com.simibubi.create.foundation.blockEntity.renderer.SafeBlockEntityRenderer;

import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.world.level.block.entity.BlockEntity;

@Mixin(value = SafeBlockEntityRenderer.class, remap = false)
public class SafeBlockEntityRendererMixin {

    /**
     * Brackets the whole render call so the temporary-kinetics context is
     * always popped, even when {@code renderSafe} throws mid-frame (the plain
     * RETURN-based clear used before was skipped on exceptions and left a
     * stale ThreadLocal entry behind). MixinExtras ships with NeoForge.
     * <p>
     * Bits 'n' Bobs wraps this same funnel for its cogwheel materials; both
     * wrappers nest harmlessly — each manages its own thread-local inside its
     * own finally block, independent of application order.
     */
    @WrapMethod(method = "render(Lnet/minecraft/world/level/block/entity/BlockEntity;FLcom/mojang/blaze3d/vertex/PoseStack;Lnet/minecraft/client/renderer/MultiBufferSource;II)V")
    private void createmanaindustry$bracketRenderContext(BlockEntity be, float partialTicks, PoseStack ms,
            MultiBufferSource buffer, int light, int overlay, Operation<Void> original) {
        if (!(be instanceof KineticBlockEntity)) {
            original.call(be, partialTicks, ms, buffer, light, overlay);
            return;
        }
        TemporaryKineticsRenderContext.set(be);
        try {
            original.call(be, partialTicks, ms, buffer, light, overlay);
        } finally {
            TemporaryKineticsRenderContext.clear(be);
        }
    }
}
