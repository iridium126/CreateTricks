package com.iridium126.createmanaindustry.mixin.trickster;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Coerce;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.ModifyVariable;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import com.iridium126.createmanaindustry.client.render.KineticsSpellCoreRenderer;
import com.iridium126.createmanaindustry.content.items.KineticsSpellCoreItem;
import com.iridium126.createmanaindustry.content.kinetics.bnb.BnBChainRenderContext;
import com.iridium126.createmanaindustry.content.kinetics.bnb.BnBKineticsCoreNodes;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.math.Axis;
import net.createmod.catnip.animation.AnimationTickHolder;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.core.BlockPos;
import net.minecraft.world.Container;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.entity.BlockEntity;

@Mixin(targets = "dev.enjarai.trickster.render.ModularSpellConstructBlockEntityRenderer")
public abstract class ModularSpellConstructBlockEntityRendererMixin {

    @ModifyVariable(method = "render", at = @At("STORE"), ordinal = 1, remap = false)
    private ItemStack createmanaindustry$hideKineticsCoreStack(ItemStack coreStack) {
        if (KineticsSpellCoreItem.is(coreStack)) {
            return ItemStack.EMPTY;
        }
        return coreStack;
    }

    @Inject(method = "render", at = @At("TAIL"), remap = false)
    private void createmanaindustry$renderKineticsCores(@Coerce Object entity, float partialTicks,
            PoseStack matrices, MultiBufferSource vertexConsumers, int light, int overlay,
            CallbackInfo ci) {
        if (!(entity instanceof Container inventory) || !(entity instanceof BlockEntity blockEntity))
            return;

        var facing = BnBKineticsCoreNodes.getFacing(blockEntity.getBlockState());

        matrices.pushPose();
        matrices.translate(0.5f, 0.5f, 0.5f);
        matrices.mulPose(facing.getRotation());
        matrices.translate(-0.5f, -0.5f, -0.5f);

        for (int slot = 1; slot < inventory.getContainerSize(); slot++) {
            if (KineticsSpellCoreItem.is(inventory.getItem(slot)))
                renderCogwheelCore(slot, matrices, vertexConsumers, light, overlay,
                        blockEntity.getBlockPos());
        }

        matrices.popPose();
    }

    private static void renderCogwheelCore(int slot, PoseStack matrices,
            MultiBufferSource vertexConsumers, int light, int overlay, BlockPos spellPos) {
        int index = slot - 1;
        int x = index % 2;
        int z = index / 2;

        matrices.pushPose();
        matrices.translate((18f / 2 * x + 3.5f) / 16f, 10.5f / 16f,
                (18f / 2 * z + 3.5f) / 16f);

        float angularVelocity = BnBChainRenderContext.getChainAngularVelocity(spellPos);
        if (angularVelocity != 0) {
            matrices.mulPose(Axis.YP.rotation(angularVelocity * AnimationTickHolder.getRenderTime()));
        }

        matrices.scale(0.28f, 0.28f, 0.28f);
        matrices.translate(-0.5f, -0.5f, -0.5f);

        KineticsSpellCoreRenderer.render(matrices, vertexConsumers, light, overlay);

        matrices.popPose();
    }
}
