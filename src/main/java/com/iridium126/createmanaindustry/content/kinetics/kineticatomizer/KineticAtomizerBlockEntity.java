package com.iridium126.createmanaindustry.content.kinetics.kineticatomizer;

import java.util.List;

import com.iridium126.createmanaindustry.config.Config;
import com.iridium126.createmanaindustry.content.fluids.mist.MistEmitter;
import com.simibubi.create.content.kinetics.base.KineticBlockEntity;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.tags.FluidTags;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.neoforged.neoforge.fluids.FluidStack;
import net.neoforged.neoforge.fluids.capability.IFluidHandler;
import net.neoforged.neoforge.fluids.capability.templates.FluidTank;

public class KineticAtomizerBlockEntity extends KineticBlockEntity {

    static final int TANK_CAPACITY = 1000;

    private final FluidTank tank = new FluidTank(TANK_CAPACITY) {
        @Override
        public boolean isFluidValid(FluidStack stack) {
            return !stack.is(FluidTags.LAVA);
        }

        @Override
        protected void onContentsChanged() {
            if (hasLevel() && !level.isClientSide) {
                setChanged();
                sendData();
            }
        }
    };

    private boolean wasActive = false;
    private int currentRadius = 0;

    public KineticAtomizerBlockEntity(BlockEntityType<?> type, BlockPos pos, BlockState state) {
        super(type, pos, state);
    }

    public IFluidHandler getFluidHandler(Direction side) {
        Direction facing = getBlockState().getValue(BlockStateProperties.FACING);
        Direction opposite = facing.getOpposite();
        if (side != opposite)
            return null;
        return new IFluidHandler() {
            @Override
            public int getTanks() { return tank.getTanks(); }

            @Override
            public FluidStack getFluidInTank(int t) { return tank.getFluidInTank(t); }

            @Override
            public int getTankCapacity(int t) { return tank.getTankCapacity(t); }

            @Override
            public boolean isFluidValid(int t, FluidStack stack) { return tank.isFluidValid(t, stack); }

            @Override
            public int fill(FluidStack resource, IFluidHandler.FluidAction action) { return tank.fill(resource, action); }

            @Override
            public FluidStack drain(FluidStack resource, IFluidHandler.FluidAction action) { return FluidStack.EMPTY; }

            @Override
            public FluidStack drain(int maxDrain, IFluidHandler.FluidAction action) { return FluidStack.EMPTY; }
        };
    }

    @Override
    public boolean addToGoggleTooltip(List<Component> tooltip, boolean isPlayerSneaking) {
        super.addToGoggleTooltip(tooltip, isPlayerSneaking);
        return containedFluidTooltip(tooltip, isPlayerSneaking, tank);
    }

    @Override
    protected void read(CompoundTag tag, HolderLookup.Provider registries, boolean clientPacket) {
        super.read(tag, registries, clientPacket);
        tank.readFromNBT(registries, tag.getCompound("Tank"));
        if (clientPacket) {
            wasActive = tag.getBoolean("MistActive");
            int radius = tag.getInt("MistRadius");
            currentRadius = radius;
            MistEmitter.notifyClientSync(worldPosition,
                    wasActive ? tank.getFluid() : FluidStack.EMPTY, radius);
        }
    }

    @Override
    protected void write(CompoundTag tag, HolderLookup.Provider registries, boolean clientPacket) {
        super.write(tag, registries, clientPacket);
        tag.put("Tank", tank.writeToNBT(registries, new CompoundTag()));
        if (clientPacket) {
            tag.putBoolean("MistActive", wasActive);
            tag.putInt("MistRadius", currentRadius);
        }
    }

    /**
     * @return {@code true} if this atomizer is currently producing mist (has fluid
     *         and rotation). Synced to the client for visual rendering.
     */
    public boolean isMistActive() {
        return wasActive;
    }

    /** Exposed for renderer use — returns the current tank fluid. */
    FluidStack getTankFluid() {
        return tank.getFluid();
    }

    @Override
    public void tick() {
        super.tick();

        if (level == null || level.isClientSide)
            return;

        float absSpeed = Math.abs(getSpeed());
        boolean hasFluid = !tank.isEmpty();
        boolean isActive = absSpeed > 0 && hasFluid;

        if (isActive) {
            int newRadius = computeRadius(absSpeed);

            if (!wasActive) {
                MistEmitter.activate(level, worldPosition, tank.getFluid(), newRadius);
            } else if (newRadius != currentRadius) {
                MistEmitter.updateRadius(level, worldPosition, newRadius);
            }
            currentRadius = newRadius;

            float speedFactor = absSpeed / 256f;
            int toConsume = Math.max(1, (int) (Config.mistFluidPerTick * speedFactor));
            FluidStack drained = tank.drain(toConsume, IFluidHandler.FluidAction.EXECUTE);
            if (!drained.isEmpty())
                MistEmitter.addCapacity(level, worldPosition, drained.getAmount());
        } else if (wasActive) {
            MistEmitter.deactivate(level, worldPosition);
            currentRadius = 0;
        }
        wasActive = isActive;
        sendData();
    }

    private int computeRadius(float absSpeed) {
        return Math.max(1, Math.round(absSpeed * Config.mistMaxRadius / 256f));
    }

    @Override
    public void invalidate() {
        super.invalidate();
        if (level != null && !level.isClientSide)
            MistEmitter.deactivate(level, worldPosition);
    }
}
