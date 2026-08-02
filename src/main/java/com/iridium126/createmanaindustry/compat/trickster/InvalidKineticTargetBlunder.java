package com.iridium126.createmanaindustry.compat.trickster;

import dev.enjarai.trickster.spell.blunder.TrickBlunderException;
import dev.enjarai.trickster.spell.trick.Trick;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.MutableComponent;

public class InvalidKineticTargetBlunder extends TrickBlunderException {
    private final BlockPos pos;

    public InvalidKineticTargetBlunder(Trick<?> source, BlockPos pos) {
        super(source);
        this.pos = pos;
    }

    public BlockPos getPos() {
        return pos;
    }

    @Override
    public MutableComponent createMessage() {
        return super.createMessage()
                .append("Invalid kinetic target at ")
                .append(pos.toShortString());
    }
}
