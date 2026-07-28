package com.iridium126.createmanaindustry.content.kinetics.manacogwheel;

import static net.minecraft.world.level.block.state.properties.BlockStateProperties.AXIS;

import com.iridium126.createmanaindustry.CMIPartialModels;
import com.simibubi.create.AllPartialModels;
import com.simibubi.create.content.kinetics.base.IRotate;
import com.simibubi.create.content.kinetics.base.KineticBlockEntityRenderer;

import dev.engine_room.flywheel.api.visualization.VisualizationManager;
import dev.engine_room.flywheel.lib.model.baked.PartialModel;
import net.createmod.catnip.data.Iterate;
import net.createmod.catnip.render.CachedBuffers;
import net.createmod.catnip.render.SuperByteBuffer;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.blockentity.BlockEntityRendererProvider;
import net.minecraft.core.Direction;
import net.minecraft.core.Direction.AxisDirection;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;

import com.mojang.blaze3d.vertex.PoseStack;

public class ManaCogwheelRenderer extends KineticBlockEntityRenderer<ManaCogwheelBlockEntity> {

    public ManaCogwheelRenderer(BlockEntityRendererProvider.Context context) {
        super(context);
    }

    @Override
    protected void renderSafe(ManaCogwheelBlockEntity be, float partialTicks, PoseStack ms,
                              MultiBufferSource buffer, int light, int overlay) {
        super.renderSafe(be, partialTicks, ms, buffer, light, overlay);
        if (VisualizationManager.supportsVisualization(be.getLevel()))
            return;

        BlockState blockState = be.getBlockState();
        Block block = blockState.getBlock();
        if (!(block instanceof IRotate def) || !(block instanceof EncasedManaCogwheelBlock))
            return;

        Direction.Axis axis = getRotationAxisOf(be);
        float angle = getAngleForBe(be, be.getBlockPos(), axis);

        for (Direction d : Iterate.directionsInAxis(axis)) {
            if (!def.hasShaftTowards(be.getLevel(), be.getBlockPos(), blockState, d))
                continue;
            SuperByteBuffer shaft = CachedBuffers.partialFacing(AllPartialModels.SHAFT_HALF, blockState, d);
            kineticRotationTransform(shaft, be, axis, angle, light);
            shaft.renderInto(ms, buffer.getBuffer(RenderType.solid()));
        }
    }

    @Override
    protected SuperByteBuffer getRotatedModel(ManaCogwheelBlockEntity be, BlockState state) {
        Direction facing = Direction.get(AxisDirection.POSITIVE, state.getValue(AXIS));
        PartialModel model = state.getBlock() instanceof EncasedManaCogwheelBlock
                ? CMIPartialModels.MANA_COGWHEEL_SHAFTLESS
                : CMIPartialModels.MANA_COGWHEEL;
        return CachedBuffers.partialFacingVertical(model, state, facing);
    }

}
