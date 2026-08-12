package com.iridium126.createmanaindustry.content.kinetics.kineticatomizer;

import java.util.List;

import com.iridium126.createmanaindustry.CMITags;
import com.iridium126.createmanaindustry.config.ServerConfig;
import com.iridium126.createmanaindustry.content.fluids.mist.MistSync;
import com.iridium126.createmanaindustry.content.fluids.mist.MistFieldStore;
import com.simibubi.create.content.kinetics.base.KineticBlockEntity;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.material.Fluid;
import net.neoforged.neoforge.fluids.FluidStack;
import net.neoforged.neoforge.fluids.capability.IFluidHandler;
import net.neoforged.neoforge.fluids.capability.templates.FluidTank;

public class KineticAtomizerBlockEntity extends KineticBlockEntity {

    static final int TANK_CAPACITY = 1000;

    private final FluidTank tank = new FluidTank(TANK_CAPACITY) {
        @Override
        public boolean isFluidValid(FluidStack stack) {
            // Molten fluids (and lava) are never atomized — their mist comes only
            // from recipe byproducts (vaporizing), never from spraying.
            return !stack.is(CMITags.MOLTEN_FLUID);
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
    /** Fluid whose mist the server-side field is currently tracking, for mid-operation tank switches. */
    private Fluid lastAtomizedFluid;

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
            MistSync.notifyClientSync(worldPosition,
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
                // Copy the tank's FluidStack — the stored mist fluid must be a
                // stable snapshot. Aliasing the tank's internal stack would make
                // the field's fluid identity go stale when the tank empties and
                // refills (its amount/instance would track the tank live).
                MistFieldStore.setActive(level, worldPosition, true, newRadius, tank.getFluid().copy());
                lastAtomizedFluid = tank.getFluid().getFluid();
            } else if (newRadius != currentRadius) {
                MistFieldStore.updateRadius(level, worldPosition, newRadius);
            }
            currentRadius = newRadius;

            // Follow a mid-operation tank fluid switch (tank refilled with a
            // different fluid while the atomizer keeps running), so the
            // server-side field's fluid — and the client's mist color — stay in
            // sync with what is actually being atomized.
            Fluid tankFluid = tank.getFluid().getFluid();
            if (lastAtomizedFluid != tankFluid) {
                MistFieldStore.updateFluid(level, worldPosition, tank.getFluid());
                lastAtomizedFluid = tankFluid;
            }

            float speedFactor = absSpeed / 256f;
            int toConsume = Math.max(1, (int) (ServerConfig.mistFluidPerTick * speedFactor));
            FluidStack drained = tank.drain(toConsume, IFluidHandler.FluidAction.EXECUTE);
            if (!drained.isEmpty())
                MistFieldStore.addCapacity(level, worldPosition, drained.getAmount());
        } else if (wasActive) {
            MistFieldStore.setActive(level, worldPosition, false, 0);
            lastAtomizedFluid = null;
            currentRadius = 0;
        }
        wasActive = isActive;
        sendData();
    }

    private int computeRadius(float absSpeed) {
        return Math.max(1, Math.round(absSpeed * ServerConfig.mistMaxRadius / 256f));
    }

    @Override
    public void invalidate() {
        super.invalidate();
        if (level != null && !level.isClientSide) {
            MistFieldStore.setActive(level, worldPosition, false, 0);
        } else if (level != null) {
            // Fade the local source on break / chunk unload. The server-side
            // deactivation only reaches the client via BE sync (which never
            // fires for a removed BE) or the shared in-JVM callback (single
            // player only) — dedicated-server clients need an explicit fade.
            MistSync.notifyClientSync(worldPosition, FluidStack.EMPTY, 0);
        }
    }
}
