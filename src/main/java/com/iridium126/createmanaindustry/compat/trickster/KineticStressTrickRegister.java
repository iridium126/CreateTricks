package com.iridium126.createmanaindustry.compat.trickster;

import java.util.List;

import com.iridium126.createmanaindustry.CreateManaIndustry;
import com.iridium126.createmanaindustry.config.Config;
import com.iridium126.createmanaindustry.content.kinetics.TemporaryStress;
import com.simibubi.create.content.kinetics.base.KineticBlockEntity;

import dev.enjarai.trickster.spell.EvaluationResult;
import dev.enjarai.trickster.spell.Fragment;
import dev.enjarai.trickster.spell.Pattern;
import dev.enjarai.trickster.spell.SpellContext;
import dev.enjarai.trickster.spell.blunder.BlunderException;
import dev.enjarai.trickster.spell.fragment.NumberFragment;
import dev.enjarai.trickster.spell.fragment.VectorFragment;
import dev.enjarai.trickster.spell.trick.Tricks;
import dev.enjarai.trickster.spell.trick.func.LoadArgumentTrick;
import dev.enjarai.trickster.spell.type.Signature;

import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.entity.BlockEntity;

/**
 * Registers the {@code temporary_kinetic_stress} Trickster trick.
 */
public final class KineticStressTrickRegister {

    private static final float STRESS_PER_SPEED = 4;
    private static boolean registered;

    private KineticStressTrickRegister() {}

    public static void register() {
        if (registered)
            return;

        if (!CreateManaIndustry.TRICKSTER_ACTIVE) {
            CreateManaIndustry.LOGGER.warn("Trickster not active — skipping temporary kinetic stress trick registration");
            return;
        }

        try {
            Pattern pattern = Pattern.of(1, 0, 2, 3, 4, 5, 6, 8, 7);
            LoadArgumentTrick trick = new LoadArgumentTrick(pattern, 0);

            trick.getSignatures().clear();
            trick.getSignatures().add(new KineticStressSignature());

            Tricks.register("temporary_kinetic_stress", trick);
            registered = true;
            CreateManaIndustry.LOGGER.info("Registered Trickster trick: temporary_kinetic_stress");
        } catch (Throwable t) {
            CreateManaIndustry.LOGGER.warn("Failed to register temporary kinetic stress trick", t);
        }
    }

    // ---- signature implementation --------------------------------------------

    private static final class KineticStressSignature implements Signature<LoadArgumentTrick> {

        @Override
        public boolean match(List<Fragment> fragments) {
            return fragments.size() == 3
                    && fragments.get(0) instanceof VectorFragment
                    && fragments.get(1) instanceof NumberFragment
                    && fragments.get(2) instanceof NumberFragment;
        }

        @Override
        public EvaluationResult run(LoadArgumentTrick trick, SpellContext ctx, List<Fragment> fragments)
                throws BlunderException {
            VectorFragment vectorFragment = (VectorFragment) fragments.get(0);
            double speedInput = ((NumberFragment) fragments.get(1)).number();
            double durationInput = ((NumberFragment) fragments.get(2)).number();

            float stressMagnitude = (float) Math.abs(speedInput) * STRESS_PER_SPEED;
            int durationTicks = (int) Math.floor(durationInput);

            if (stressMagnitude <= 0 || durationTicks <= 0)
                throw new InvalidKineticStressBlunder(trick, stressMagnitude, durationTicks);

            BlockPos pos = vectorFragment.toBlockPos();
            ServerLevel level = ctx.source().getWorld();
            BlockEntity be = level.getBlockEntity(pos);
            if (!(be instanceof KineticBlockEntity kinetic))
                throw new InvalidKineticTargetBlunder(trick, pos);

            double manaCost = Config.manaPerStress * stressMagnitude * durationTicks
                    * Config.kineticStressTrickManaMultiplier;
            TricksterManaAccess.useTraditionalMana(ctx, trick, manaCost);

            float speed = (float) speedInput;
            TemporaryStress.apply(kinetic, speed < 0 ? -stressMagnitude : stressMagnitude, speed, durationTicks);
            return vectorFragment;
        }

        @Override
        public MutableComponent asText() {
            return Component.literal("vector, number, number -> vector");
        }
    }
}
