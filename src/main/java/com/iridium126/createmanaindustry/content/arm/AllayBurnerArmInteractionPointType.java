package com.iridium126.createmanaindustry.content.arm;

import com.iridium126.createmanaindustry.CMIBlocks;
import com.iridium126.createmanaindustry.content.burner.AllayBurnerBlock;
import com.simibubi.create.content.kinetics.mechanicalArm.ArmInteractionPoint;
import com.simibubi.create.content.kinetics.mechanicalArm.ArmInteractionPointType;

import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;

/**
 * Mechanical arm interaction point for the Allay Burner, mirroring Create's
 * Blaze Burner point: the arm may only deposit fuel (never extract, never
 * touch records). Empty cages (HEAT_LEVEL == NONE) have no block entity and
 * cannot be fed, so they cannot be selected as a point.
 */
public class AllayBurnerArmInteractionPointType extends ArmInteractionPointType {
    @Override
    public boolean canCreatePoint(Level level, BlockPos pos, BlockState state) {
        return state.is(CMIBlocks.ALLAY_BURNER.get())
            && state.getOptionalValue(AllayBurnerBlock.HEAT_LEVEL)
                .orElse(AllayBurnerBlock.HeatLevel.NONE) != AllayBurnerBlock.HeatLevel.NONE;
    }

    @Override
    public ArmInteractionPoint createPoint(Level level, BlockPos pos, BlockState state) {
        return new AllayBurnerArmInteractionPoint(this, level, pos, state);
    }
}
