package com.iridium126.createmanaindustry.compat.jei;

import com.iridium126.createmanaindustry.CMIBlocks;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.math.Axis;
import com.simibubi.create.AllBlocks;
import com.simibubi.create.compat.jei.category.animations.AnimatedKinetics;

import net.minecraft.client.gui.GuiGraphics;

/**
 * JEI station element for vapor deposition: the deposition lid (a closed framed
 * glass trapdoor) resting on the basin it seals. Mirrors Create Diesel
 * Generators' {@code BasinFermentingStationElement}.
 */
public class VaporDepositionStationElement extends AnimatedKinetics {

    @Override
    public void draw(GuiGraphics graphics, int xOffset, int yOffset) {
        PoseStack matrixStack = graphics.pose();
        matrixStack.pushPose();
        matrixStack.translate(xOffset, yOffset, 200);
        matrixStack.mulPose(Axis.XP.rotationDegrees(-15.5f));
        matrixStack.mulPose(Axis.YP.rotationDegrees(22.5f));
        int scale = 23;

        blockElement(CMIBlocks.DEPOSITION_LID.getDefaultState())
                .atLocal(0, 0, 0)
                .scale(scale)
                .render(graphics);

        blockElement(AllBlocks.BASIN.getDefaultState())
                .atLocal(0, 1, 0)
                .scale(scale)
                .render(graphics);

        matrixStack.popPose();
    }
}
