package com.iridium126.createtricks;

import static com.iridium126.createtricks.CreateTricks.REGISTRATE;

import com.iridium126.createtricks.content.kinetics.StressManaConverterBlockEntity;
import com.iridium126.createtricks.content.kinetics.StressManaConverterRenderer;
import com.iridium126.createtricks.content.kinetics.StressManaConverterVisual;
import com.tterrag.registrate.util.entry.BlockEntityEntry;

public final class CreateTricksBlockEntitys {
	public static final BlockEntityEntry<StressManaConverterBlockEntity> STRESS_MANA_CONVERTER = REGISTRATE
			.blockEntity("stress_mana_converter", StressManaConverterBlockEntity::new)
			.visual(() -> StressManaConverterVisual::new, false)
			.validBlocks(CreateTricksBlocks.STRESS_MANA_CONVERTER)
			.renderer(() -> StressManaConverterRenderer::new)
			.register();

	private CreateTricksBlockEntitys() {}

	public static void register() {}
}
