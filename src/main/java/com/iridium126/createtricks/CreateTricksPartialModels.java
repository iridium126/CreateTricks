package com.iridium126.createtricks;

import dev.engine_room.flywheel.lib.model.baked.PartialModel;

public final class CreateTricksPartialModels {
	public static final PartialModel STRESS_MANA_CONVERTER_SHAFT = block("stress_mana_converter/shaft");
	public static final PartialModel STRESS_MANA_CONVERTER_COG = block("stress_mana_converter/cog");

	private CreateTricksPartialModels() {}

	private static PartialModel block(String path) {
		return PartialModel.of(CreateTricks.modLoc("block/" + path));
	}

	public static void init() {}
}
