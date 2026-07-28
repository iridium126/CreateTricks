package com.iridium126.createmanaindustry.content.kinetics.manacogwheel;

import com.iridium126.createmanaindustry.CMIBlockEntityTypes;
import com.simibubi.create.content.kinetics.base.KineticBlockEntity;
import com.simibubi.create.content.kinetics.simpleRelays.CogWheelBlock;

import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockBehaviour;

public class ManaCogwheelBlock extends CogWheelBlock {

    public ManaCogwheelBlock(BlockBehaviour.Properties properties) {
        super(false, properties);
    }

    @Override
    public BlockEntityType<? extends KineticBlockEntity> getBlockEntityType() {
        return CMIBlockEntityTypes.MANA_COGWHEEL.get();
    }

}
