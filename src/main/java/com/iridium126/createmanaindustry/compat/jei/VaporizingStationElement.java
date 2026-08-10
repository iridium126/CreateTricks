package com.iridium126.createmanaindustry.compat.jei;

import com.iridium126.createmanaindustry.CMIBlocks;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.math.Axis;
import com.simibubi.create.AllBlocks;
import com.simibubi.create.compat.jei.category.animations.AnimatedKinetics;

import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.core.Direction;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;

/**
 * JEI station element for vaporizing: the deposition lid (an OPEN framed glass
 * trapdoor) resting on the basin below. Mirrors
 * {@link VaporDepositionStationElement}, whose lid is drawn closed — here the
 * lid is drawn open and mirrored to the trapdoor's default facing (NORTH →
 * SOUTH) so the open door swings toward the viewer's right.
 */
public class VaporizingStationElement extends AnimatedKinetics {

    @Override
    public void draw(GuiGraphics graphics, int xOffset, int yOffset) {
        PoseStack matrixStack = graphics.pose();
        matrixStack.pushPose();
        matrixStack.translate(xOffset, yOffset, 200);
        matrixStack.mulPose(Axis.XP.rotationDegrees(-15.5f));
        matrixStack.mulPose(Axis.YP.rotationDegrees(22.5f));
        int scale = 23;

        blockElement(CMIBlocks.DEPOSITION_LID.getDefaultState()
                .setValue(BlockStateProperties.OPEN, true)
                // Opposite of the trapdoor's default facing (NORTH).
                .setValue(BlockStateProperties.HORIZONTAL_FACING, Direction.SOUTH))
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
