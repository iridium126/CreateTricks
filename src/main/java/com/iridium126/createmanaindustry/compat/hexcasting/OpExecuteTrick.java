package com.iridium126.createmanaindustry.compat.hexcasting;

import java.util.ArrayList;
import java.util.List;

import com.iridium126.createmanaindustry.CreateManaIndustry;

import at.petrak.hexcasting.api.casting.castables.Action;
import at.petrak.hexcasting.api.casting.eval.CastingEnvironment;
import at.petrak.hexcasting.api.casting.eval.OperationResult;
import at.petrak.hexcasting.api.casting.eval.vm.CastingImage;
import at.petrak.hexcasting.api.casting.eval.vm.SpellContinuation;
import at.petrak.hexcasting.api.casting.iota.Iota;
import at.petrak.hexcasting.api.casting.iota.ListIota;
import at.petrak.hexcasting.api.casting.mishaps.Mishap;
import at.petrak.hexcasting.api.casting.mishaps.MishapInvalidIota;
import at.petrak.hexcasting.api.casting.mishaps.MishapNotEnoughArgs;
import at.petrak.hexcasting.api.utils.TreeList;
import at.petrak.hexcasting.common.lib.hex.HexEvalSounds;
import net.minecraft.server.level.ServerPlayer;

/**
 * Hexcasting Action: pops a {@link TrickIota} (optionally with a
 * {@link ListIota} of arguments on top) and executes the Trickster spell it
 * holds via {@link SpellExecutionBridge}. The result iota is pushed back
 * (nothing is pushed for HANDED_OFF — the spell continues in the background —
 * or FAILED).
 */
public class OpExecuteTrick implements Action {

    public static final OpExecuteTrick INSTANCE = new OpExecuteTrick();

    private OpExecuteTrick() {
    }

    @Override
    public OperationResult operate(CastingEnvironment env, CastingImage image, SpellContinuation continuation)
            throws Mishap {
        TreeList<Iota> stack = image.getStack();
        if (stack.isEmpty()) {
            throw new MishapNotEnoughArgs(1, 0);
        }

        List<Iota> argsToPass = new ArrayList<>();
        Iota top = stack.last();
        stack = stack.init();

        if (top instanceof ListIota list) {
            if (stack.isEmpty()) {
                throw new MishapNotEnoughArgs(2, 1);
            }
            Iota next = stack.last();
            stack = stack.init();
            if (next instanceof TrickIota trick) {
                argsToPass.add(trick);
                argsToPass.add(list);
            } else {
                throw MishapInvalidIota.of(next, stack.size(), "class.createmanaindustry.trick");
            }
        } else if (top instanceof TrickIota trick) {
            argsToPass.add(trick);
        } else {
            throw MishapInvalidIota.of(top, stack.size(), "class.createmanaindustry.trick");
        }

        stack = stack.appendedAll(execute(argsToPass, env));

        CastingImage nextImage = image.copy(stack, image.getParenCount(), image.getParenthesized(),
                image.getEscapeNext(), image.getSimulateNext(), image.getOpsConsumed() + 1, image.getUserData());
        return new OperationResult(nextImage, List.of(), continuation, HexEvalSounds.NORMAL_EXECUTE.get());
    }

    private List<Iota> execute(List<Iota> args, CastingEnvironment env) {
        TrickIota trickIota = (TrickIota) args.get(0);
        ListIota listIota = args.size() > 1 ? (ListIota) args.get(1) : null;

        if (!(env.getCastingEntity() instanceof ServerPlayer player)) {
            return List.of();
        }

        List<Iota> params = new ArrayList<>();
        if (listIota != null) {
            Iterable<Iota> iterable = listIota.subIotas();
            if (iterable != null) {
                for (Iota iota : iterable) {
                    params.add(iota);
                }
            }
        }

        SpellExecutionBridge.SpellExecutionResult execution = SpellExecutionBridge.tryExecute(
                player, trickIota.getSpell(), params, env);
        if (execution.status() == SpellExecutionBridge.SpellExecutionStatus.COMPLETED) {
            Iota result = execution.result();
            return result != null ? List.of(result) : List.of();
        }
        if (execution.status() == SpellExecutionBridge.SpellExecutionStatus.HANDED_OFF) {
            return List.of();
        }

        CreateManaIndustry.LOGGER.warn("Failed to execute Trickster spell fragment");
        return List.of();
    }
}
