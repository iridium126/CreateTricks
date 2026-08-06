package com.iridium126.createmanaindustry.compat.trickster;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import com.iridium126.createmanaindustry.compat.hexcasting.FragmentConverter;

import at.petrak.hexcasting.api.casting.eval.CastingEnvironment;
import at.petrak.hexcasting.api.casting.eval.ExecutionClientView;
import at.petrak.hexcasting.api.casting.eval.env.StaffCastEnv;
import at.petrak.hexcasting.api.casting.eval.vm.CastingImage;
import at.petrak.hexcasting.api.casting.eval.vm.CastingVM;
import at.petrak.hexcasting.api.casting.iota.Iota;
import at.petrak.hexcasting.api.casting.iota.ListIota;
import at.petrak.hexcasting.api.casting.iota.NullIota;
import at.petrak.hexcasting.api.casting.iota.PatternIota;
import at.petrak.hexcasting.api.utils.TreeList;
import dev.enjarai.trickster.spell.Fragment;
import dev.enjarai.trickster.spell.Pattern;
import dev.enjarai.trickster.spell.SpellContext;
import dev.enjarai.trickster.spell.blunder.InvalidInputsBlunder;
import dev.enjarai.trickster.spell.blunder.NoPlayerBlunder;
import dev.enjarai.trickster.spell.execution.source.BlockSpellSource;
import dev.enjarai.trickster.spell.trick.Trick;
import dev.enjarai.trickster.spell.type.ArgType;
import dev.enjarai.trickster.spell.type.RetType;
import dev.enjarai.trickster.spell.type.Signature;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;

/**
 * Trickster trick: runs the Hexcasting spell stored in an {@link IotaFragment}
 * (a {@code PatternIota} for a single op, or a {@code ListIota} for a full
 * spell) in a hex {@link CastingVM}, with the remaining arguments pushed as
 * the initial stack. The top of the resulting stack is pushed back as a
 * fragment.
 * <p>
 * Environment: construct-cast (spell constructs) draws media from
 * {@link ConstructMediaStorage}; player-cast draws media from the player's
 * inventory hex media items (no staff needed).
 * <p>
 * PLACEHOLDER pattern — verify in-game that it doesn't collide with other
 * tricks.
 */
public class EvalIotaTrick extends Trick<EvalIotaTrick> {

    public EvalIotaTrick() {
        super(Pattern.of(3, 6, 7, 8, 5, 4, 3, 0, 1, 4, 7, 3),
                Signature.of(CMITricksterIotaRegister.iotaFragmentType(), ArgType.ANY.variadicOfArg(),
                        EvalIotaTrick::run, RetType.ANY.optionalOfRet()));
    }

    public Optional<Fragment> run(SpellContext ctx, IotaFragment spell, List<Fragment> args) {
        Iota iota = spell.getIota();

        // Unwrap a single-element list (a spell stored as a one-element list).
        if (iota instanceof ListIota list) {
            List<Iota> entries = listEntries(list);
            if (entries.size() == 1 && entries.getFirst() instanceof ListIota nested) {
                iota = nested;
            }
        }
        if (!(iota instanceof PatternIota) && !(iota instanceof ListIota)) {
            throw new InvalidInputsBlunder(this, args);
        }

        // Environment: construct-cast vs player-cast.
        CastingEnvironment env;
        ServerLevel level;
        if (ctx.source() instanceof BlockSpellSource<?> bs && ConstructMediaStorage.isConstruct(bs.blockEntity)) {
            level = bs.world;
            env = new ConstructCastEnv(level, bs.blockEntity);
        } else {
            ServerPlayer player = ctx.source().getPlayer().orElseThrow(() -> new NoPlayerBlunder(this));
            level = player.serverLevel();
            env = new StaffCastEnv(player, InteractionHand.MAIN_HAND);
        }

        // Remaining arguments become the initial hex stack.
        List<Iota> initialStack = new ArrayList<>();
        for (Fragment arg : args) {
            Iota converted = FragmentConverter.fragmentToIota(arg);
            initialStack.add(converted != null ? converted : new NullIota());
        }

        CastingVM vm = new CastingVM(
                new CastingImage(TreeList.from(initialStack), 0, TreeList.empty(), false, false, 0L, new CompoundTag()),
                env);
        ExecutionClientView result = iota instanceof ListIota list
                ? vm.queueExecuteAndWrapIotas(listEntries(list), level)
                : vm.queueExecuteAndWrapIota(iota, level);

        if (!result.getResolutionType().getSuccess()) {
            return Optional.empty();
        }
        List<Iota> stack = result.getStackDescs();
        Iota top = stack.isEmpty() ? new NullIota() : stack.getLast();
        return Optional.of(FragmentConverter.iotaToFragment(top));
    }

    private static List<Iota> listEntries(ListIota list) {
        List<Iota> entries = new ArrayList<>();
        Iterable<Iota> subIotas = list.subIotas();
        if (subIotas != null) {
            subIotas.forEach(entries::add);
        }
        return entries;
    }
}
