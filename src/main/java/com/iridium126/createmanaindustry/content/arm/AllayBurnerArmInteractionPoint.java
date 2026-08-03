package com.iridium126.createmanaindustry.content.arm;

import com.iridium126.createmanaindustry.content.burner.AllayBurnerBlock;
import com.simibubi.create.content.kinetics.mechanicalArm.AllArmInteractionPointTypes;
import com.simibubi.create.content.kinetics.mechanicalArm.ArmBlockEntity;
import com.simibubi.create.content.kinetics.mechanicalArm.ArmInteractionPoint;
import com.simibubi.create.content.kinetics.mechanicalArm.ArmInteractionPointType;

import net.minecraft.core.BlockPos;
import net.minecraft.world.Containers;
import net.minecraft.world.InteractionResultHolder;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;

/**
 * Deposit-only arm point for the Allay Burner — a verbatim mirror of Create's
 * {@code BlazeBurnerPoint}: the arm feeds fuel through
 * {@link AllayBurnerBlock#tryInsert} with the same remainder/drop handling,
 * and never extracts or touches records.
 */
public class AllayBurnerArmInteractionPoint extends AllArmInteractionPointTypes.DepositOnlyArmInteractionPoint {

    public AllayBurnerArmInteractionPoint(ArmInteractionPointType type, Level level, BlockPos pos, BlockState state) {
        super(type, level, pos, state);
    }

    @Override
    public ItemStack insert(ArmBlockEntity armBlockEntity, ItemStack stack, boolean simulate) {
        ItemStack input = stack.copy();
        InteractionResultHolder<ItemStack> res =
            AllayBurnerBlock.tryInsert(cachedState, level, pos, input, false, false, simulate);
        ItemStack remainder = res.getObject();
        if (input.isEmpty()) {
            return remainder;
        } else {
            if (!simulate)
                Containers.dropItemStack(level, pos.getX(), pos.getY(), pos.getZ(), remainder);
            return input;
        }
    }
}
