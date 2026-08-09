package com.iridium126.createmanaindustry.compat.hexcasting;

import java.util.List;

import com.iridium126.createmanaindustry.config.ServerConfig;
import com.iridium126.createmanaindustry.content.burner.AllayBurnerBlock;
import com.iridium126.createmanaindustry.content.burner.AllayBurnerBlockEntity;
import com.iridium126.createmanaindustry.mixin.burner.BlazeBurnerBlockEntityAccessor;
import com.simibubi.create.content.processing.burner.BlazeBurnerBlock;
import com.simibubi.create.content.processing.burner.BlazeBurnerBlockEntity;

import at.petrak.hexcasting.api.casting.ParticleSpray;
import at.petrak.hexcasting.api.casting.RenderedSpell;
import at.petrak.hexcasting.api.casting.castables.SpellAction;
import at.petrak.hexcasting.api.casting.eval.CastingEnvironment;
import at.petrak.hexcasting.api.casting.iota.DoubleIota;
import at.petrak.hexcasting.api.casting.iota.Iota;
import at.petrak.hexcasting.api.casting.iota.Vec3Iota;
import at.petrak.hexcasting.api.casting.mishaps.Mishap;
import at.petrak.hexcasting.api.casting.mishaps.MishapInvalidIota;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;

/**
 * Hexcasting Action: pops a {@link Vec3Iota} (position) and a {@link DoubleIota}
 * (burn time in seconds) from the stack, lights the Create Blaze Burner or the
 * Allay Burner at that position and adds the given burn time to it.
 * <p>
 * Uses a deferred {@link RenderedSpell} so the world is only modified after the
 * spell's media cost has been confirmed — an immediate modification would let
 * media-less casters light burners for free.
 * <p>
 * Throws {@link MishapInvalidBurner} if the block at the position is not a
 * lit Blaze Burner or an Allay Burner (empty burners without a block entity,
 * e.g. {@code create:lit_blaze_burner}, are not valid targets).
 */
public class OpLightBurner implements SpellAction {

    public static final OpLightBurner INSTANCE = new OpLightBurner();

    /** Empty effect for creative/SEETHING burners: nothing happens, nothing is paid. */
    private static final RenderedSpell NOOP = env -> {};

    private static final Result NOOP_RESULT = new Result(NOOP, 0L, List.of(), 1L);

    private OpLightBurner() {}

    @Override
    public int getArgc() {
        return 2;
    }

    @Override
    public Result execute(List<? extends Iota> args, CastingEnvironment env) throws Mishap {
        Iota arg = args.get(0);
        if (!(arg instanceof Vec3Iota vecIota)) {
            throw MishapInvalidIota.ofType(arg, 0, "vector");
        }

        Vec3 vec = vecIota.getVec3();
        BlockPos pos = BlockPos.containing(vec);
        env.assertPosInRangeForEditing(pos);

        Iota timeArg = args.get(1);
        if (!(timeArg instanceof DoubleIota doubleIota) || doubleIota.getDouble() <= 0) {
            throw MishapInvalidIota.of(timeArg, 1, "double.positive");
        }

        int ticks = Math.max(1, (int) (doubleIota.getDouble() * 20.0));
        // Media is charged per second at the Allay Burner's burn rate, so the
        // spell is economically equivalent to pouring in fuel.
        long cost = ServerConfig.mediaConsumedPerTick * ticks;

        BlockState state = env.getWorld().getBlockState(pos);
        BlockEntity be = env.getWorld().getBlockEntity(pos);

        if (state.getBlock() instanceof BlazeBurnerBlock) {
            if (!(be instanceof BlazeBurnerBlockEntity burner)) {
                // Empty burners (no blaze inside, no block entity) cannot be lit by magic.
                throw new MishapInvalidBurner(vec);
            }
            // Creative and SEETHING burners burn forever already: nothing to do.
            BlazeBurnerBlockEntityAccessor acc = (BlazeBurnerBlockEntityAccessor) burner;
            if (burner.isCreative() || acc.createmanaindustry$getActiveFuel()
                == BlazeBurnerBlockEntity.FuelType.SPECIAL) {
                return NOOP_RESULT;
            }
            return new Result(new LightBlazeBurnerSpell(pos, ticks), cost,
                List.of(ParticleSpray.cloud(Vec3.atCenterOf(pos), 1.0, 20)), 1L);
        }

        if (state.getBlock() instanceof AllayBurnerBlock) {
            if (!(be instanceof AllayBurnerBlockEntity burner)) {
                throw new MishapInvalidBurner(vec);
            }
            if (burner.isCreative()) {
                return NOOP_RESULT;
            }
            return new Result(new LightAllayBurnerSpell(pos, ticks), cost,
                List.of(ParticleSpray.cloud(Vec3.atCenterOf(pos), 1.0, 20)), 1L);
        }

        throw new MishapInvalidBurner(vec);
    }

    /**
     * Deferred effect for a Blaze Burner: adds burn time unconditionally —
     * {@code MAX_HEAT_CAPACITY} and {@code INSERTION_THRESHOLD} are ignored,
     * matching the Allay Burner's fuel behaviour.
     */
    private record LightBlazeBurnerSpell(BlockPos pos, int ticks) implements RenderedSpell {

        @Override
        public void cast(CastingEnvironment env) {
            ServerLevel level = env.getWorld();
            BlockState state = level.getBlockState(pos);
            if (!(state.getBlock() instanceof BlazeBurnerBlock))
                return;
            if (!(level.getBlockEntity(pos) instanceof BlazeBurnerBlockEntity burner))
                return;
            BlazeBurnerBlockEntityAccessor acc = (BlazeBurnerBlockEntityAccessor) burner;
            // The burner may have turned creative/SEETHING since the cast was queued.
            if (burner.isCreative() || acc.createmanaindustry$getActiveFuel()
                == BlazeBurnerBlockEntity.FuelType.SPECIAL)
                return;

            acc.createmanaindustry$setRemainingBurnTime(acc.createmanaindustry$getRemainingBurnTime() + ticks);
            if (acc.createmanaindustry$getActiveFuel() == BlazeBurnerBlockEntity.FuelType.NONE)
                acc.createmanaindustry$setActiveFuel(BlazeBurnerBlockEntity.FuelType.NORMAL);

            // Mirrors BlazeBurnerBlockEntity.tryUpdateFuel's feedback chain.
            if (level.isClientSide) {
                acc.createmanaindustry$spawnParticleBurst(false);
                return;
            }

            BlazeBurnerBlock.HeatLevel prev = burner.getHeatLevelFromBlock();
            acc.createmanaindustry$playSound();
            burner.updateBlockState();
            if (prev != burner.getHeatLevelFromBlock())
                level.playSound(null, pos, SoundEvents.BLAZE_AMBIENT, SoundSource.BLOCKS,
                    .125f + level.random.nextFloat() * .125f, 1.15f - level.random.nextFloat() * .25f);
        }
    }

    /** Deferred effect for the Allay Burner: delegates to the block entity's fuel logic. */
    private record LightAllayBurnerSpell(BlockPos pos, int ticks) implements RenderedSpell {

        @Override
        public void cast(CastingEnvironment env) {
            ServerLevel level = env.getWorld();
            if (!(level.getBlockState(pos).getBlock() instanceof AllayBurnerBlock))
                return;
            if (!(level.getBlockEntity(pos) instanceof AllayBurnerBlockEntity burner))
                return;
            if (burner.isCreative())
                return;
            burner.addBurnTime(ticks);
        }
    }
}
