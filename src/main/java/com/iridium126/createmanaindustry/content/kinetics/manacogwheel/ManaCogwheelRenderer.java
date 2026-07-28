package com.iridium126.createmanaindustry.content.kinetics.manacogwheel;

import static net.minecraft.world.level.block.state.properties.BlockStateProperties.AXIS;

import com.iridium126.createmanaindustry.CMIPartialModels;
import com.simibubi.create.content.kinetics.base.KineticBlockEntityRenderer;

import net.createmod.catnip.render.CachedBuffers;
import net.createmod.catnip.render.SuperByteBuffer;
import net.minecraft.client.renderer.blockentity.BlockEntityRendererProvider;
import net.minecraft.core.Direction;
import net.minecraft.core.Direction.AxisDirection;
import net.minecraft.world.level.block.state.BlockState;

public class ManaCogwheelRenderer extends KineticBlockEntityRenderer<ManaCogwheelBlockEntity> {

    public ManaCogwheelRenderer(BlockEntityRendererProvider.Context context) {
        super(context);
    }

    @Override
    protected SuperByteBuffer getRotatedModel(ManaCogwheelBlockEntity be, BlockState state) {
        Direction facing = Direction.get(AxisDirection.POSITIVE, state.getValue(AXIS));
        return CachedBuffers.partialFacingVertical(CMIPartialModels.MANA_COGWHEEL, state, facing);
    }
}
