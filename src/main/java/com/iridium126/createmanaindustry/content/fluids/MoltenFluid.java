package com.iridium126.createmanaindustry.content.fluids;

import org.jetbrains.annotations.Nullable;

import net.minecraft.core.BlockPos;
import net.minecraft.core.particles.ParticleOptions;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.LevelAccessor;
import net.minecraft.world.level.LevelReader;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.StateDefinition;
import net.minecraft.world.level.material.Fluid;
import net.minecraft.world.level.material.FluidState;
import net.neoforged.neoforge.fluids.BaseFlowingFluid;

/**
 * Shared base for this mod's molten fluids (Molten Rose Quartz, Molten
 * Prismarine Quartz, …) — strictly mirrors vanilla {@code LavaFluid} behaviour
 * (particles, sounds, flowing parameters, fluid replacement, block fizzing),
 * except for the exceptions the mod owner carved out:
 * <ul>
 *   <li><b>No fire spread</b> — {@code randomTick} / {@code isRandomlyTicking}
 *       are not overridden, so adjacent flammable blocks are never ignited.</li>
 *   <li><b>No water → stone</b> — {@code spreadTo} is not overridden; water
 *       replaces the fluid without generating stone.</li>
 *   <li><b>No infinite source</b> — {@code canConvertToSource} inherits
 *       {@link BaseFlowingFluid}'s {@code false}.</li>
 * </ul>
 * <p>
 * Must extend {@link BaseFlowingFluid} (not vanilla {@code LavaFluid}) because
 * Registrate's {@code FluidBuilder<T extends BaseFlowingFluid>} requires it for
 * the supplier wiring ({@code getFlowing}/{@code getSource}/{@code getBucket}).
 * The lava-specific methods below are ported from {@code LavaFluid}.
 */
public abstract class MoltenFluid extends BaseFlowingFluid {

    protected MoltenFluid(Properties properties) {
        super(properties);
    }

    @Override
    public void animateTick(Level level, BlockPos pos, FluidState state, RandomSource random) {
        BlockPos above = pos.above();
        if (level.getBlockState(above).isAir() && !level.getBlockState(above).isSolidRender(level, above)) {
            if (random.nextInt(100) == 0) {
                double d0 = pos.getX() + random.nextDouble();
                double d1 = pos.getY() + 1.0;
                double d2 = pos.getZ() + random.nextDouble();
                level.addParticle(ParticleTypes.LAVA, d0, d1, d2, 0.0, 0.0, 0.0);
                level.playLocalSound(d0, d1, d2, SoundEvents.LAVA_POP, SoundSource.BLOCKS,
                        0.2F + random.nextFloat() * 0.2F, 0.9F + random.nextFloat() * 0.15F, false);
            }

            if (random.nextInt(200) == 0) {
                level.playLocalSound(pos.getX(), pos.getY(), pos.getZ(), SoundEvents.LAVA_AMBIENT,
                        SoundSource.BLOCKS, 0.2F + random.nextFloat() * 0.2F,
                        0.9F + random.nextFloat() * 0.15F, false);
            }
        }
    }

    @Nullable
    @Override
    public ParticleOptions getDripParticle() {
        return ParticleTypes.DRIPPING_LAVA;
    }

    @Override
    protected void beforeDestroyingBlock(LevelAccessor level, BlockPos pos, BlockState state) {
        this.fizz(level, pos);
    }

    private void fizz(LevelAccessor level, BlockPos pos) {
        level.levelEvent(1501, pos, 0);
    }

    @Override
    public int getSlopeFindDistance(LevelReader level) {
        return level.dimensionType().ultraWarm() ? 4 : 2;
    }

    @Override
    public int getDropOff(LevelReader level) {
        return level.dimensionType().ultraWarm() ? 1 : 2;
    }

    @Override
    public int getTickDelay(LevelReader level) {
        return level.dimensionType().ultraWarm() ? 10 : 30;
    }

    @Override
    public int getSpreadDelay(Level level, BlockPos pos, FluidState currentState, FluidState newState) {
        int i = this.getTickDelay(level);
        if (!currentState.isEmpty()
                && !newState.isEmpty()
                && !currentState.getValue(FALLING)
                && !newState.getValue(FALLING)
                && newState.getHeight(level, pos) > currentState.getHeight(level, pos)
                && level.getRandom().nextInt(4) != 0) {
            i *= 4;
        }

        return i;
    }

    public static class Flowing extends MoltenFluid {
        public Flowing(Properties properties) {
            super(properties);
            registerDefaultState(getStateDefinition().any().setValue(LEVEL, 7));
        }

        @Override
        protected void createFluidStateDefinition(StateDefinition.Builder<Fluid, FluidState> builder) {
            super.createFluidStateDefinition(builder);
            builder.add(LEVEL);
        }

        @Override
        public int getAmount(FluidState state) {
            return state.getValue(LEVEL);
        }

        @Override
        public boolean isSource(FluidState state) {
            return false;
        }
    }

    public static class Source extends MoltenFluid {
        public Source(Properties properties) {
            super(properties);
        }

        @Override
        public int getAmount(FluidState state) {
            return 8;
        }

        @Override
        public boolean isSource(FluidState state) {
            return true;
        }
    }
}
