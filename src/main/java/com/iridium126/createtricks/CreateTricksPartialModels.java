package com.iridium126.createtricks;

import dev.engine_room.flywheel.lib.model.baked.PartialModel;

public final class CreateTricksPartialModels {
	public static final PartialModel STRESS_MANA_CONVERTER_INNER = block("stress_mana_converter/inner");
	public static final PartialModel STRESSED_STRESS_MANA_CONVERTER_INNER = block("stress_mana_converter/inner_stressed");
	public static final PartialModel STRESSED_SHAFTLESS_COGWHEEL = block("temporary_stress/cogwheel_shaftless");
	public static final PartialModel STRESSED_SHAFTLESS_LARGE_COGWHEEL = block("temporary_stress/large_cogwheel_shaftless");
	public static final PartialModel STRESSED_COGWHEEL_SHAFT = block("temporary_stress/cogwheel_shaft");
	public static final PartialModel STRESSED_SHAFT_HALF = block("temporary_stress/shaft_half");
	public static final PartialModel STRESSED_SHAFT = block("temporary_stress/shaft");
	public static final PartialModel STRESSED_COGWHEEL = block("temporary_stress/cogwheel");

	private CreateTricksPartialModels() {}

	private static PartialModel block(String path) {
		return PartialModel.of(CreateTricks.modLoc("block/" + path));
	}

	public static void init() {}
}
