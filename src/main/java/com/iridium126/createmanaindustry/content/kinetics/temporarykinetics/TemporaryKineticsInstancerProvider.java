package com.iridium126.createmanaindustry.content.kinetics.temporarykinetics;

import dev.engine_room.flywheel.api.instance.Instance;
import dev.engine_room.flywheel.api.instance.InstanceType;
import dev.engine_room.flywheel.api.instance.Instancer;
import dev.engine_room.flywheel.api.instance.InstancerProvider;
import dev.engine_room.flywheel.api.model.Model;
import net.minecraft.world.level.block.entity.BlockEntity;

public class TemporaryKineticsInstancerProvider implements InstancerProvider {
    private final InstancerProvider wrapped;
    private final BlockEntity blockEntity;

    public TemporaryKineticsInstancerProvider(InstancerProvider wrapped, BlockEntity blockEntity) {
        this.wrapped = wrapped;
        this.blockEntity = blockEntity;
    }

    @Override
    public <I extends Instance> Instancer<I> instancer(InstanceType<I> type, Model model, int bias) {
        return wrapped.instancer(type, TemporaryKineticsVisualModels.replace(blockEntity, model), bias);
    }
}
