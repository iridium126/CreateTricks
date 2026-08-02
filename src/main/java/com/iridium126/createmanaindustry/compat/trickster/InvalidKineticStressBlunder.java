package com.iridium126.createmanaindustry.compat.trickster;

import dev.enjarai.trickster.spell.blunder.TrickBlunderException;
import dev.enjarai.trickster.spell.trick.Trick;
import net.minecraft.network.chat.MutableComponent;

public class InvalidKineticStressBlunder extends TrickBlunderException {
    private final float stressMagnitude;
    private final int durationTicks;

    public InvalidKineticStressBlunder(Trick<?> source, float stressMagnitude, int durationTicks) {
        super(source);
        this.stressMagnitude = stressMagnitude;
        this.durationTicks = durationTicks;
    }

    @Override
    public MutableComponent createMessage() {
        return super.createMessage()
                .append("Invalid kinetic stress parameters: stress=")
                .append(String.format("%.1f", stressMagnitude))
                .append(", duration=")
                .append(Integer.toString(durationTicks));
    }
}
