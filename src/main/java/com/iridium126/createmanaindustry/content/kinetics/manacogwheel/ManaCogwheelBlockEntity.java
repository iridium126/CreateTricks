package com.iridium126.createmanaindustry.content.kinetics.manacogwheel;

import com.iridium126.createmanaindustry.CreateManaIndustry;
import com.iridium126.createmanaindustry.content.fluids.mist.MistFieldStore;
import com.simibubi.create.content.kinetics.base.GeneratingKineticBlockEntity;

import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockState;

public class ManaCogwheelBlockEntity extends GeneratingKineticBlockEntity {

    private static final ResourceLocation LIQUID_MANA_ID = CreateManaIndustry.modLoc("liquid_mana");
    private static final ResourceLocation LIQUID_MEDIA_ID = CreateManaIndustry.modLoc("liquid_media");

    private float lastGeneratedSpeed = 0f;

    public ManaCogwheelBlockEntity(BlockEntityType<?> type, BlockPos pos, BlockState state) {
        super(type, pos, state);
    }

    @Override
    public float getGeneratedSpeed() {
        if (level == null)
            return 0f;

        ResourceLocation fluidType = MistFieldStore.getFluidType(level, worldPosition);
        if (fluidType == null)
            return 0f;

        if (fluidType.equals(LIQUID_MANA_ID))
            return 256f;
        if (fluidType.equals(LIQUID_MEDIA_ID))
            return -256f;

        return 0f;
    }

    @Override
    public float calculateAddedStressCapacity() {
        float capacity = 8.0f;
        this.lastCapacityProvided = capacity;
        return capacity;
    }

    @Override
    public float calculateStressApplied() {
        this.lastStressApplied = 0f;
        return 0f;
    }

    @Override
    public void tick() {
        super.tick();

        if (level == null || level.isClientSide)
            return;

        float targetSpeed = getGeneratedSpeed();
        if (targetSpeed != lastGeneratedSpeed) {
            lastGeneratedSpeed = targetSpeed;
            updateGeneratedRotation();
        }
    }

}
