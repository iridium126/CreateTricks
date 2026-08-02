package com.iridium126.createmanaindustry.content.kinetics.kineticmanagenerator;

import java.util.List;

import com.iridium126.createmanaindustry.CreateManaIndustry;
import com.iridium126.createmanaindustry.compat.trickster.TricksterManaAccess;
import com.iridium126.createmanaindustry.config.Config;
import com.simibubi.create.content.kinetics.simpleRelays.SimpleKineticBlockEntity;
import com.simibubi.create.foundation.blockEntity.behaviour.BlockEntityBehaviour;
import com.simibubi.create.foundation.blockEntity.behaviour.scrollValue.ScrollValueBehaviour;

import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockState;

public class KineticManaGeneratorBlockEntity extends SimpleKineticBlockEntity {
    public static final int MIN_STRESS_PER_RPM = 4;
    public static final int MAX_STRESS_PER_RPM = 256;
    public static final int DEFAULT_STRESS_PER_RPM = 4;

    private ScrollValueBehaviour stressPerRpm;

    public KineticManaGeneratorBlockEntity(BlockEntityType<?> type, BlockPos pos, BlockState state) {
        super(type, pos, state);
    }

    @Override
    public void addBehaviours(List<BlockEntityBehaviour> behaviours) {
        stressPerRpm = new ScrollValueBehaviour(
                Component.translatable("createmanaindustry.kinetic_mana_generator.stress_per_rpm"),
                this,
                new KineticManaGeneratorScrollSlot())
                .between(MIN_STRESS_PER_RPM, MAX_STRESS_PER_RPM)
                .withCallback(this::onStressPerRpmChanged);
        stressPerRpm.setValue(DEFAULT_STRESS_PER_RPM);
        behaviours.add(stressPerRpm);
    }

    private void onStressPerRpmChanged(int value) {
        if (level == null || level.isClientSide || !hasNetwork())
            return;
        getOrCreateNetwork().updateStressFor(this, calculateStressApplied());
    }

    @Override
    public float calculateStressApplied() {
        float impact = stressPerRpm == null ? DEFAULT_STRESS_PER_RPM : stressPerRpm.getValue();
        this.lastStressApplied = impact;
        return impact;
    }

    @Override
    public void tick() {
        super.tick();

        if (level == null || level.isClientSide)
            return;

        float speed = Math.abs(getSpeed());
        if (speed == 0 || overStressed)
            return;

        float stressConsumed = calculateStressApplied() * speed;
        if (stressConsumed <= 0)
            return;

        float mana = (float) (stressConsumed * Config.manaPerStress);
        if (mana <= 0)
            return;

        if (CreateManaIndustry.TRICKSTER_ACTIVE) {
            BlockPos outputPos = KineticManaGeneratorBlock.getManaOutputPos(getBlockState(), worldPosition);
            TricksterManaAccess.chargeKnotsAt((ServerLevel) level, outputPos, mana);
        }
    }

    public int getStressPerRpm() {
        return stressPerRpm == null ? DEFAULT_STRESS_PER_RPM : stressPerRpm.getValue();
    }
}
