package com.iridium126.createtricks.trickster;

import dev.enjarai.trickster.spell.blunder.TrickBlunderException;
import dev.enjarai.trickster.spell.trick.Trick;

public class InvalidKineticStressBlunder extends TrickBlunderException {
	public InvalidKineticStressBlunder(Trick<?> source) {
		super(source);
	}
}
