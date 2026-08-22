package com.iridium126.createmanaindustry;

import dev.engine_room.flywheel.lib.model.baked.PartialModel;

public final class CMIPartialModels {
    public static final PartialModel KINETIC_MANA_GENERATOR_INNER = block("kinetic_mana_generator/inner");
    public static final PartialModel KINETIC_MANA_GENERATOR_OUTER = block("kinetic_mana_generator/outer");

    public static final PartialModel KINETIC_ATOMIZER_COG = block("kinetic_atomizer/cog");

    public static final PartialModel ALLAY_BURNER_FLAME = block("allay_burner/flame");
    public static final PartialModel ALLAY_BURNER_RODS_SMALL = block("allay_burner/allayheated_rods_small");
    public static final PartialModel ALLAY_BURNER_RODS_LARGE = block("allay_burner/allayheated_rods_large");

    public static final PartialModel STRESSED_KINETIC_MANA_GENERATOR_INNER = block("kinetic_mana_generator/inner_stressed");
    public static final PartialModel STRESSED_SHAFTLESS_COGWHEEL = block("temporarykinetics/cogwheel_shaftless");
    public static final PartialModel STRESSED_SHAFTLESS_LARGE_COGWHEEL = block("temporarykinetics/large_cogwheel_shaftless");
    public static final PartialModel STRESSED_COGWHEEL_SHAFT = block("temporarykinetics/cogwheel_shaft");
    public static final PartialModel STRESSED_SHAFT_HALF = block("temporarykinetics/shaft_half");
    public static final PartialModel STRESSED_SHAFT = block("temporarykinetics/shaft");
    public static final PartialModel STRESSED_COGWHEEL = block("temporarykinetics/cogwheel");
    public static final PartialModel MANA_COGWHEEL = block("mana_cogwheel");
    public static final PartialModel MANA_COGWHEEL_SHAFTLESS = block("mana_cogwheel_shaftless");

    private CMIPartialModels() {}

    private static PartialModel block(String path) {
        return PartialModel.of(CreateManaIndustry.modLoc("block/" + path));
    }

    public static void register() {}
}
