package com.iridium126.createmanaindustry.compat.hexcasting;

import java.util.List;

import at.petrak.hexcasting.api.casting.castables.ConstMediaAction;
import at.petrak.hexcasting.api.casting.eval.CastingEnvironment;
import at.petrak.hexcasting.api.casting.iota.Iota;
import at.petrak.hexcasting.api.casting.mishaps.Mishap;
import at.petrak.hexcasting.api.casting.mishaps.MishapBadOffhandItem;
import dev.enjarai.trickster.item.component.FragmentComponent;
import dev.enjarai.trickster.spell.SpellPart;

/**
 * Hexcasting Action: reads the {@link SpellPart} from a Trickster spell item
 * (knot etc.) held by the caster — offhand preferred, mirroring
 * {@code OpRead} — and pushes a {@link TrickIota} wrapping it.
 * <p>
 * Throws {@link MishapBadOffhandItem} when no readable spell item is held.
 */
public class OpReadTrickFromItem implements ConstMediaAction {

    public static final OpReadTrickFromItem INSTANCE = new OpReadTrickFromItem();

    private OpReadTrickFromItem() {}

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
        CastingEnvironment.HeldItemInfo held = env.getHeldItemToOperateOn(
                stack -> FragmentComponent.getSpellPart(stack).isPresent());
        if (held == null) {
            throw MishapBadOffhandItem.of(null, "trick");
        }

        SpellPart spell = FragmentComponent.getSpellPart(held.stack())
                .orElseThrow(() -> MishapBadOffhandItem.of(held.stack(), "trick"));

        return List.of(new TrickIota(spell));
    }
}
