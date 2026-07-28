package com.iridium126.createmanaindustry.content.kinetics.manacogwheel;

import java.util.function.Consumer;

import org.jetbrains.annotations.Nullable;

import com.iridium126.createmanaindustry.CMIPartialModels;
import com.simibubi.create.AllPartialModels;
import com.simibubi.create.content.kinetics.base.IRotate;
import com.simibubi.create.content.kinetics.base.KineticBlockEntityVisual;
import com.simibubi.create.content.kinetics.base.RotatingInstance;
import com.simibubi.create.foundation.render.AllInstanceTypes;

import dev.engine_room.flywheel.api.instance.Instance;
import dev.engine_room.flywheel.api.model.Model;
import dev.engine_room.flywheel.api.visualization.VisualizationContext;
import dev.engine_room.flywheel.lib.model.Models;
import net.createmod.catnip.data.Iterate;
import net.minecraft.core.Direction;
import net.minecraft.core.Direction.AxisDirection;
import net.minecraft.world.level.block.Block;

public class ManaCogwheelVisual extends KineticBlockEntityVisual<ManaCogwheelBlockEntity> {

    protected final RotatingInstance rotatingModel;
    @Nullable
    protected final RotatingInstance rotatingTopShaft;
    @Nullable
    protected final RotatingInstance rotatingBottomShaft;

    public static ManaCogwheelVisual create(VisualizationContext context, ManaCogwheelBlockEntity be, float partialTick) {
        Block block = be.getBlockState().getBlock();
        Model model;
        if (block instanceof EncasedManaCogwheelBlock) {
            model = Models.partial(CMIPartialModels.MANA_COGWHEEL_SHAFTLESS);
        } else {
            model = Models.partial(CMIPartialModels.MANA_COGWHEEL);
        }
        return new ManaCogwheelVisual(context, be, partialTick, model);
    }

    public ManaCogwheelVisual(VisualizationContext context, ManaCogwheelBlockEntity be, float partialTick, Model model) {
        super(context, be, partialTick);

        rotatingModel = instancerProvider().instancer(AllInstanceTypes.ROTATING, model)
                .createInstance();

        rotatingModel.setup(be)
                .setPosition(getVisualPosition())
                .rotateToFace(rotationAxis())
                .setChanged();

        RotatingInstance topShaft = null;
        RotatingInstance bottomShaft = null;

        if (blockState.getBlock() instanceof IRotate def && blockState.getBlock() instanceof EncasedManaCogwheelBlock) {
            for (Direction d : Iterate.directionsInAxis(rotationAxis())) {
                if (!def.hasShaftTowards(be.getLevel(), be.getBlockPos(), blockState, d))
                    continue;
                RotatingInstance instance = instancerProvider()
                        .instancer(AllInstanceTypes.ROTATING, Models.partial(AllPartialModels.SHAFT_HALF))
                        .createInstance();
                instance.setup(be)
                        .setPosition(getVisualPosition())
                        .rotateToFace(Direction.SOUTH, d)
                        .setChanged();

                if (d.getAxisDirection() == AxisDirection.POSITIVE) {
                    topShaft = instance;
                } else {
                    bottomShaft = instance;
                }
            }
        }

        this.rotatingTopShaft = topShaft;
        this.rotatingBottomShaft = bottomShaft;
    }

    @Override
    public void update(float pt) {
        rotatingModel.setup(blockEntity).setChanged();
        if (rotatingTopShaft != null) rotatingTopShaft.setup(blockEntity).setChanged();
        if (rotatingBottomShaft != null) rotatingBottomShaft.setup(blockEntity).setChanged();
    }

    @Override
    public void updateLight(float partialTick) {
        relight(rotatingModel, rotatingTopShaft, rotatingBottomShaft);
    }

    @Override
    protected void _delete() {
        rotatingModel.delete();
        if (rotatingTopShaft != null) rotatingTopShaft.delete();
        if (rotatingBottomShaft != null) rotatingBottomShaft.delete();
    }

    @Override
    public void collectCrumblingInstances(Consumer<@Nullable Instance> consumer) {
        consumer.accept(rotatingModel);
        consumer.accept(rotatingTopShaft);
        consumer.accept(rotatingBottomShaft);
    }
}
