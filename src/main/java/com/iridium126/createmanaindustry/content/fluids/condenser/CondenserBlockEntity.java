package com.iridium126.createmanaindustry.content.fluids.condenser;

import java.util.List;

import com.iridium126.createmanaindustry.config.Config;
import com.iridium126.createmanaindustry.content.fluids.mist.MistEmitter;
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
import net.minecraft.tags.FluidTags;
import net.minecraft.world.level.BlockAndTintGetter;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.material.Fluid;
import net.neoforged.neoforge.fluids.FluidStack;
import net.neoforged.neoforge.fluids.capability.IFluidHandler;

public class CondenserBlockEntity extends SmartBlockEntity {

    private boolean condensing = false;
    private FluidStack condensingFluid = FluidStack.EMPTY;

    public CondenserBlockEntity(BlockEntityType<?> type, BlockPos pos, BlockState state) {
        super(type, pos, state);
    }

    @Override
    public void addBehaviours(List<BlockEntityBehaviour> behaviours) {
        behaviours.add(new CondenserFluidTransportBehaviour(this));
        registerAwardables(behaviours, FluidPropagator.getSharedTriggers());
    }

    @Override
    public void tick() {
        super.tick();
        if (level == null)
            return;

        if (!level.isClientSide) {
            // 1. Check for water flow through pipe connections and gather pressure
            FluidTransportBehaviour fluidBehaviour = getBehaviour(FluidTransportBehaviour.TYPE);
            if (fluidBehaviour == null) {
                setCondensing(false);
                return;
            }

            BlockState state = getBlockState();
            Direction.Axis axis = state.getValue(BlockStateProperties.AXIS);
            Direction dir1 = Direction.fromAxisAndDirection(axis, Direction.AxisDirection.POSITIVE);
            Direction dir2 = Direction.fromAxisAndDirection(axis, Direction.AxisDirection.NEGATIVE);
            float flowPressure = 0f;
            boolean waterFlowing = false;
            for (Direction side : new Direction[]{dir1, dir2}) {
                PipeConnection.Flow flow = fluidBehaviour.getFlow(side);
                if (flow != null && flow.complete && flow.fluid.is(FluidTags.WATER)) {
                    waterFlowing = true;
                    PipeConnection conn = fluidBehaviour.getConnection(side);
                    if (conn != null)
                        flowPressure = Math.max(flowPressure, conn.getPressure().get(flow.inbound));
                }
            }
            if (!waterFlowing) {
                setCondensing(false);
                return;
            }

            // 2. Check for mist at condenser position
            ResourceLocation mistFluidId = MistFieldStore.getFluidType(level, worldPosition);
            if (mistFluidId == null) {
                setCondensing(false);
                return;
            }
            float concentration = MistFieldStore.getConcentration(level, worldPosition);
            if (concentration <= 0) {
                setCondensing(false);
                return;
            }

            // 3. Check for Drain below
            BlockEntity below = level.getBlockEntity(worldPosition.below());
            if (!(below instanceof ItemDrainBlockEntity drain)) {
                setCondensing(false);
                return;
            }

            // 4. Inject mist fluid into Drain (capacity-aware)
            SmartFluidTankBehaviour tank = drain.getBehaviour(SmartFluidTankBehaviour.TYPE);
            if (tank == null) {
                setCondensing(false);
                return;
            }

            Fluid fluid = BuiltInRegistries.FLUID.get(mistFluidId);
            if (fluid == null) {
                setCondensing(false);
                return;
            }

            // Check drain fluid type compatibility
            IFluidHandler primaryHandler = tank.getPrimaryHandler();
            FluidStack drainFluid = primaryHandler.getFluidInTank(0);
            if (!drainFluid.isEmpty()) {
                ResourceLocation drainFluidId = BuiltInRegistries.FLUID.getKey(drainFluid.getFluid());
                if (!drainFluidId.equals(mistFluidId)) {
                    setCondensing(false);
                    return; // fluid type mismatch — don't collect, don't reduce capacity
                }
            }

            // Compute desired collection, clamp to drain remaining capacity
            int desired = Math.max(1, (int) (concentration * Config.condenseEfficiency * (1 + flowPressure / 64)));
            int drainRemaining = primaryHandler.getTankCapacity(0) - drainFluid.getAmount();
            int target = Math.min(desired, drainRemaining);
            if (target <= 0) {
                setCondensing(false);
                return;
            }

            // Consume from mist capacity (cascade through same-fluid sources)
            long collected = MistEmitter.consumeCapacity(level, worldPosition, mistFluidId, target);
            if (collected <= 0) {
                setCondensing(false);
                return;
            }

            FluidStack stack = new FluidStack(fluid, (int) collected);

            tank.allowInsertion();
            primaryHandler.fill(stack, IFluidHandler.FluidAction.EXECUTE);
            tank.forbidInsertion();

            setCondensing(true, stack);
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
