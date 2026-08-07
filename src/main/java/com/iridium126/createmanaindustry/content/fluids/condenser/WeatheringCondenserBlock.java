package com.iridium126.createmanaindustry.content.fluids.condenser;

import com.mojang.serialization.MapCodec;
import com.mojang.serialization.codecs.RecordCodecBuilder;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.ChangeOverTimeBlock;
import net.minecraft.world.level.block.WeatheringCopper;
import net.minecraft.world.level.block.state.BlockState;

/**
 * Oxidizing variant of the {@link CondenserBlock}, mirroring Minecraft's
 * {@code WeatheringCopperFullBlock}: the weathering state is stored on the
 * block, random ticks advance it through the {@code OXIDIZABLES} data map
 * (registered in {@code neoforge/data_maps/block/oxidizables.json}), and the
 * scraping/waxing interactions are driven by NeoForge's data-map hooks.
 */
public class WeatheringCondenserBlock extends CondenserBlock implements WeatheringCopper {

    public static final MapCodec<WeatheringCondenserBlock> CODEC = RecordCodecBuilder.mapCodec(
        p -> p.group(
                WeatheringCopper.WeatherState.CODEC
                    .fieldOf("weathering_state")
                    .forGetter(ChangeOverTimeBlock::getAge),
                propertiesCodec())
            .apply(p, WeatheringCondenserBlock::new));

    private final WeatheringCopper.WeatherState weatherState;

    public WeatheringCondenserBlock(WeatheringCopper.WeatherState weatherState, Properties properties) {
        super(properties);
        this.weatherState = weatherState;
    }

    @Override
    protected void randomTick(BlockState state, ServerLevel level, BlockPos pos, RandomSource random) {
        this.changeOverTime(state, level, pos, random);
    }

    @Override
    protected boolean isRandomlyTicking(BlockState state) {
        return WeatheringCopper.getNext(state.getBlock()).isPresent();
    }

    @Override
    public WeatheringCopper.WeatherState getAge() {
        return this.weatherState;
    }

    @Override
    protected MapCodec<? extends Block> codec() {
        return CODEC;
    }
}
