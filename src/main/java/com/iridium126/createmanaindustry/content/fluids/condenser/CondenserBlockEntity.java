package com.iridium126.createmanaindustry.content.fluids.condenser;

import java.util.List;

import com.iridium126.createmanaindustry.config.Config;
import com.iridium126.createmanaindustry.content.fluids.mist.MistFieldStore;
import com.simibubi.create.AllParticleTypes;
import com.simibubi.create.content.fluids.FluidPropagator;
import com.simibubi.create.content.fluids.FluidTransportBehaviour;
import com.simibubi.create.content.fluids.PipeConnection;
import com.simibubi.create.content.fluids.drain.ItemDrainBlockEntity;
import com.simibubi.create.content.fluids.particle.FluidParticleData;
import com.simibubi.create.foundation.blockEntity.SmartBlockEntity;
import com.simibubi.create.foundation.blockEntity.behaviour.BlockEntityBehaviour;
import com.simibubi.create.foundation.blockEntity.behaviour.fluid.SmartFluidTankBehaviour;

import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.particle.ParticleEngine;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.BlockAndTintGetter;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.material.Fluid;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.api.distmarker.OnlyIn;
import net.neoforged.neoforge.fluids.FluidStack;
import net.neoforged.neoforge.fluids.capability.IFluidHandler;

/**
 * Condenser: coolant passes through along its axis like a pipe, and when a mist
 * field is present with a drain below, condenses that mist back into fluid.
 * <p>
 * The condenser exposes <b>no</b> fluid capability — exposing one would make
 * Create's pipe network treat it as a tank terminal ({@code FlowSource.FluidHandler})
 * and break through-flow. Instead, {@code FluidNetworkMixin} charges the 1:1
 * coolant cost directly against the passing flow whenever a coolant-carrying
 * network passes through this block, and {@link #consumeFromFlow} claims this
 * block's per-tick demand.
 */
public class CondenserBlockEntity extends SmartBlockEntity {

    private boolean condensing = false;
    private FluidStack condensingFluid = FluidStack.EMPTY;

    /** mB of coolant claimed from the passing flow this game tick (set by FluidNetworkMixin). */
    private int consumedThisTick = 0;
    /** Game time at which the claim happened — guards against double-claiming within a tick. */
    private long claimTick = -1;

    public CondenserBlockEntity(BlockEntityType<?> type, BlockPos pos, BlockState state) {
        super(type, pos, state);
    }

    @Override
    public void addBehaviours(List<BlockEntityBehaviour> behaviours) {
        behaviours.add(new CondenserFluidTransportBehaviour(this));
        registerAwardables(behaviours, FluidPropagator.getSharedTriggers());
    }

    /**
     * Called by {@code FluidNetworkMixin} when a coolant-carrying pipe network
     * passes through this condenser. Claims this condenser's per-tick coolant
     * demand — at most once per game tick, so a network's SIMULATE pass claims
     * and its EXECUTE pass reuses {@link #getConsumed()}, and multiple networks
     * cannot double-charge.
     *
     * @return mB actually consumed from the passing flow this tick
     */
    public int consumeFromFlow(int available) {
        if (level == null || level.isClientSide)
            return 0;
        long now = level.getGameTime();
        if (claimTick == now)
            return 0;
        claimTick = now;
        int demand = computeDemand();
        int consumed = Math.min(demand, available);
        consumedThisTick = consumed;
        return consumed;
    }

    /** The amount claimed this tick — reused by the mixin's EXECUTE pass. */
    public int getConsumed() {
        return consumedThisTick;
    }

    /**
     * How much coolant this condenser wants to consume this tick, based on the
     * mist field and the drain below. Returns 0 when there is no mist, no drain,
     * the drain holds a different fluid, or the drain is full.
     */
    private int computeDemand() {
        // Mist present at the condenser position?
        ResourceLocation mistFluidId = MistFieldStore.getFluidType(level, worldPosition);
        if (mistFluidId == null)
            return 0;
        float concentration = MistFieldStore.getConcentration(level, worldPosition);
        if (concentration <= 0)
            return 0;

        // Drain below with a compatible fluid and free capacity?
        BlockEntity below = level.getBlockEntity(worldPosition.below());
        if (!(below instanceof ItemDrainBlockEntity drain))
            return 0;
        SmartFluidTankBehaviour tank = drain.getBehaviour(SmartFluidTankBehaviour.TYPE);
        if (tank == null)
            return 0;
        IFluidHandler primaryHandler = tank.getPrimaryHandler();
        FluidStack drainFluid = primaryHandler.getFluidInTank(0);
        if (!drainFluid.isEmpty()) {
            ResourceLocation drainFluidId = BuiltInRegistries.FLUID.getKey(drainFluid.getFluid());
            if (!drainFluidId.equals(mistFluidId))
                return 0;
        }
        int drainRemaining = primaryHandler.getTankCapacity(0) - drainFluid.getAmount();
        if (drainRemaining <= 0)
            return 0;

        // Coolant flow pressure through the axis scales the efficiency.
        float flowPressure = 0f;
        FluidTransportBehaviour fluidBehaviour = getBehaviour(FluidTransportBehaviour.TYPE);
        if (fluidBehaviour != null) {
            Direction.Axis axis = getBlockState().getValue(BlockStateProperties.AXIS);
            for (Direction side : axisSides(axis)) {
                PipeConnection conn = fluidBehaviour.getConnection(side);
                if (conn != null) {
                    PipeConnection.Flow flow = fluidBehaviour.getFlow(side);
                    if (flow != null && flow.complete)
                        flowPressure = Math.max(flowPressure, conn.getPressure().get(flow.inbound));
                }
            }
        }

        // Cap by the mist field's actual capacity — a field with no capacity
        // (freshly activated or exhausted) must not claim coolant, otherwise
        // coolant is deducted from the passing flow but nothing condenses.
        long availableCapacity = MistFieldStore.availableCapacity(level, worldPosition, mistFluidId);
        if (availableCapacity <= 0)
            return 0;

        int desired = Math.max(1, (int) (concentration * Config.condenseEfficiency * (1 + flowPressure / 64)));
        int byCapacity = (int) Math.min(desired, availableCapacity);
        return Math.min(byCapacity, drainRemaining);
    }

    private static Direction[] axisSides(Direction.Axis axis) {
        return new Direction[] {
                Direction.fromAxisAndDirection(axis, Direction.AxisDirection.POSITIVE),
                Direction.fromAxisAndDirection(axis, Direction.AxisDirection.NEGATIVE)
        };
    }

    /**
     * Converts a claimed coolant amount into condensed fluid: reduces the mist
     * field's capacity and injects the collected fluid into the drain below.
     */
    private void condenseFromConsumed(int amount) {
        ResourceLocation mistFluidId = MistFieldStore.getFluidType(level, worldPosition);
        if (mistFluidId == null) {
            setCondensing(false);
            return;
        }
        Fluid fluid = BuiltInRegistries.FLUID.get(mistFluidId);
        if (fluid == null) {
            setCondensing(false);
            return;
        }

        long collected = MistFieldStore.consumeCapacity(level, worldPosition, mistFluidId, amount);
        if (collected <= 0) {
            setCondensing(false);
            return;
        }

        BlockEntity below = level.getBlockEntity(worldPosition.below());
        if (!(below instanceof ItemDrainBlockEntity drain)) {
            setCondensing(false);
            return;
        }
        SmartFluidTankBehaviour tank = drain.getBehaviour(SmartFluidTankBehaviour.TYPE);
        if (tank == null) {
            setCondensing(false);
            return;
        }

        IFluidHandler primaryHandler = tank.getPrimaryHandler();
        FluidStack drainFluid = primaryHandler.getFluidInTank(0);
        if (!drainFluid.isEmpty()) {
            ResourceLocation drainFluidId = BuiltInRegistries.FLUID.getKey(drainFluid.getFluid());
            if (!drainFluidId.equals(mistFluidId)) {
                setCondensing(false);
                return;
            }
        }

        int toInject = Math.min((int) collected, primaryHandler.getTankCapacity(0) - drainFluid.getAmount());
        if (toInject <= 0) {
            setCondensing(false);
            return;
        }

        FluidStack stack = new FluidStack(fluid, toInject);
        tank.allowInsertion();
        primaryHandler.fill(stack, IFluidHandler.FluidAction.EXECUTE);
        tank.forbidInsertion();
        setCondensing(true, stack);
    }

    @Override
    public void tick() {
        super.tick();
        if (level == null)
            return;

        if (!level.isClientSide) {
            // FluidNetworkMixin consumed coolant from the passing flow on our
            // behalf; convert it 1:1 into condensed fluid in the drain below.
            if (consumedThisTick > 0) {
                condenseFromConsumed(consumedThisTick);
                consumedThisTick = 0;
            } else {
                setCondensing(false);
            }
        } else {
            // Client side: spawn particles
            if (condensing) {
                BlockState state = getBlockState();
                Direction.Axis axis = state.getValue(BlockStateProperties.AXIS);
                spawnCondensationParticles(axis, condensingFluid);
            }
        }
    }

    private void setCondensing(boolean active) {
        setCondensing(active, FluidStack.EMPTY);
    }

    private void setCondensing(boolean active, FluidStack fluid) {
        if (condensing != active || !FluidStack.isSameFluidSameComponents(condensingFluid, fluid)) {
            condensing = active;
            condensingFluid = fluid.copy();
            sendData();
        }
    }

    @Override
    protected void write(CompoundTag tag, HolderLookup.Provider registries, boolean clientPacket) {
        super.write(tag, registries, clientPacket);
        if (clientPacket) {
            tag.putBoolean("Condensing", condensing);
            if (!condensingFluid.isEmpty())
                tag.put("CondensingFluid", condensingFluid.saveOptional(registries));
        }
    }

    @Override
    protected void read(CompoundTag tag, HolderLookup.Provider registries, boolean clientPacket) {
        super.read(tag, registries, clientPacket);
        if (clientPacket) {
            condensing = tag.getBoolean("Condensing");
            condensingFluid = tag.contains("CondensingFluid")
                    ? FluidStack.parseOptional(registries, tag.getCompound("CondensingFluid"))
                    : FluidStack.EMPTY;
        }
    }

    @OnlyIn(Dist.CLIENT)
    private void spawnCondensationParticles(Direction.Axis axis, FluidStack fluidStack) {
        double cx = worldPosition.getX() + 0.5;
        double cy = worldPosition.getY() + 0.5;
        double cz = worldPosition.getZ() + 0.5;
        float radius = 0.5f;
        float halfHeight = 0.3125f;

        var particle = new FluidParticleData(AllParticleTypes.FLUID_DRIP.get(), fluidStack);

        double angle = Math.PI + (level.random.nextDouble() + level.random.nextDouble()) * 0.5 * Math.PI;
        double sin = Math.sin(angle);
        double cos = Math.cos(angle);

        double x, y, z;

        switch (axis) {
            case X -> {
                x = cx + level.random.nextDouble() * (halfHeight * 2) - halfHeight;
                y = cy + radius * sin;
                z = cz + radius * cos;
            }
            case Z -> {
                x = cx + radius * cos;
                y = cy + radius * sin;
                z = cz + level.random.nextDouble() * (halfHeight * 2) - halfHeight;
            }
            default -> {
                return;
            }
        }

        if (level instanceof ClientLevel) {
            ParticleEngine engine = Minecraft.getInstance().particleEngine;
            var p = engine.createParticle(particle, x, y, z, 0, 0, 0);
            if (p != null) {
                p.scale(0.4f);
                engine.add(p);
            }
        }
    }

    class CondenserFluidTransportBehaviour extends FluidTransportBehaviour {

        public CondenserFluidTransportBehaviour(SmartBlockEntity be) {
            super(be);
        }

        @Override
        public boolean canHaveFlowToward(BlockState state, Direction direction) {
            Direction.Axis axis = state.getValue(BlockStateProperties.AXIS);
            return direction.getAxis() == axis;
        }

        @Override
        public AttachmentTypes getRenderedRimAttachment(BlockAndTintGetter world, BlockPos pos,
                BlockState state, Direction direction) {
            if (!canHaveFlowToward(state, direction))
                return AttachmentTypes.NONE;
            AttachmentTypes attachment = super.getRenderedRimAttachment(world, pos, state, direction);
            if (attachment == AttachmentTypes.RIM)
                return AttachmentTypes.DETAILED_CONNECTION;
            return attachment;
        }
    }
}
