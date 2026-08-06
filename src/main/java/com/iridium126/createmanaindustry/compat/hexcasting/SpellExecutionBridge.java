package com.iridium126.createmanaindustry.compat.hexcasting;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import com.iridium126.createmanaindustry.CreateManaIndustry;
import com.iridium126.createmanaindustry.compat.hexcasting.circle.CircleSlateManaPool;
import com.iridium126.createmanaindustry.compat.hexcasting.circle.SlateKnotInventory;

import at.petrak.hexcasting.api.casting.eval.CastingEnvironment;
import at.petrak.hexcasting.api.casting.eval.env.CircleCastEnv;
import at.petrak.hexcasting.api.casting.iota.Iota;
import dev.enjarai.trickster.spell.Fragment;
import dev.enjarai.trickster.spell.SpellPart;
import dev.enjarai.trickster.spell.execution.ExecutionState;
import dev.enjarai.trickster.spell.execution.SpellExecutionManager;
import dev.enjarai.trickster.spell.execution.executor.DefaultSpellExecutor;
import dev.enjarai.trickster.spell.execution.source.PlayerSpellSource;
import dev.enjarai.trickster.spell.execution.source.SpellSource;
import dev.enjarai.trickster.spell.mana.MutableManaPool;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import org.jetbrains.annotations.Nullable;

/**
 * Executes a Trickster {@link SpellPart} from a Hexcasting action, mirroring
 * the author's HexTricks bridge but with direct API calls (CMI compiles
 * against the Mojang-remapped Trickster jar).
 * <p>
 * Runs synchronously up to the per-tick instruction limit; if the spell is
 * not done, it is queued into the player's execution manager (HANDED_OFF) and
 * continues ticking in the background — the final result is discarded, like
 * HexTricks. In a hex circle, mana is drawn from the slate knots via a pool
 * override that survives continuation ticks.
 */
public final class SpellExecutionBridge {

    public enum SpellExecutionStatus {
        COMPLETED, HANDED_OFF, FAILED
    }

    public record SpellExecutionResult(SpellExecutionStatus status, @Nullable Iota result) {

        static SpellExecutionResult completed(@Nullable Iota result) {
            return new SpellExecutionResult(SpellExecutionStatus.COMPLETED, result);
        }

        static SpellExecutionResult handedOff() {
            return new SpellExecutionResult(SpellExecutionStatus.HANDED_OFF, null);
        }

        static SpellExecutionResult failed() {
            return new SpellExecutionResult(SpellExecutionStatus.FAILED, null);
        }
    }

    private SpellExecutionBridge() {
    }

    public static SpellExecutionResult tryExecute(ServerPlayer player, SpellPart spellPart, List<Iota> arguments,
            CastingEnvironment env) {
        try {
            SpellSource source = new PlayerSpellSource(player);

            MutableManaPool pool = null;
            if (env instanceof CircleCastEnv circleEnv) {
                var impetus = circleEnv.getImpetus();
                if (impetus != null && impetus.getLevel() instanceof ServerLevel level) {
                    pool = new CircleSlateManaPool(
                            SlateKnotInventory.forCircle(level, circleEnv.circleState().reachedPositions), level);
                }
            }

            List<Fragment> fragments = new ArrayList<>();
            for (Iota arg : arguments) {
                Fragment fragment = FragmentConverter.iotaToFragment(arg);
                if (fragment == null) {
                    CreateManaIndustry.LOGGER.warn("Failed to convert iota argument to trickster fragment");
                    return SpellExecutionResult.failed();
                }
                fragments.add(fragment);
            }

            DefaultSpellExecutor executor = pool != null
                    ? new DefaultSpellExecutor(spellPart, new ExecutionState(fragments, pool))
                    : new DefaultSpellExecutor(spellPart, fragments);

            Optional<Fragment> result = executor.run(source);
            if (result.isPresent()) {
                return SpellExecutionResult.completed(FragmentConverter.fragmentToIota(result.get()));
            }

            Optional<SpellExecutionManager> manager = source.getExecutionManager();
            if (manager.isPresent() && manager.get().queue(executor).isPresent()) {
                return SpellExecutionResult.handedOff();
            }
            return SpellExecutionResult.failed();
        } catch (Throwable t) {
            CreateManaIndustry.LOGGER.warn("Failed to execute Trickster spell fragment", t);
            return SpellExecutionResult.failed();
        }
    }
}
