package com.iridium126.createmanaindustry.compat.hexcasting;

import java.util.List;

import at.petrak.hexcasting.api.casting.castables.ConstMediaAction;
import at.petrak.hexcasting.api.casting.eval.CastingEnvironment;
import at.petrak.hexcasting.api.casting.eval.env.CircleCastEnv;
import at.petrak.hexcasting.api.casting.iota.EntityIota;
import at.petrak.hexcasting.api.casting.iota.Iota;
import at.petrak.hexcasting.api.casting.mishaps.Mishap;
import at.petrak.hexcasting.api.casting.mishaps.circle.MishapNoSpellCircle;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.phys.AABB;

/**
 * Hexcasting Action: pushes a {@link List} of {@link EntityIota} for every
 * {@link Player} whose position lies inside the executing spell circle's
 * bounds ({@code BlockBox#aabb()}, the same AABB reported by Lesser/Greater
 * Fold Reflection). Dead and spectator players are filtered out, mirroring
 * Hexcasting's {@code isReasonablySelectable}. * <p>
 * Takes no arguments and costs no media. Throws {@link MishapNoSpellCircle}
 * when executed outside a spell circle (e.g. cast from a casting item).
 */
public class OpPlayersInCircle implements ConstMediaAction {

    public static final OpPlayersInCircle INSTANCE = new OpPlayersInCircle();

    private OpPlayersInCircle() {}

    @Override
    public int getArgc() {
        return 0;
    }

    @Override
    public long getMediaCost() {
        return 0;
    }

    @Override
    public List<Iota> execute(List<? extends Iota> args, CastingEnvironment env) throws Mishap {
        if (!(env instanceof CircleCastEnv circleEnv)) {
            throw new MishapNoSpellCircle();
        }

        // The circle always has an execution state while it is executing this.
        AABB bounds = circleEnv.circleState().bounds.aabb();

        // Match by the player's position (like Hexcasting's selectors do), not
        // by bounding-box overlap, which getEntitiesOfClass would otherwise use.
        List<Player> players = env.getWorld().getEntitiesOfClass(Player.class, bounds,
                p -> p.isAlive() && !p.isSpectator() && bounds.contains(p.position()));

        return players.stream()
                .<Iota>map(EntityIota::new)
                .toList();
    }
}
