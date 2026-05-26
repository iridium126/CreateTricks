package com.iridium126.createtricks;

import static com.iridium126.createtricks.CreateTricks.REGISTRATE;

import com.iridium126.createtricks.content.kinetics.StressManaConverterBlockEntity;
import com.iridium126.createtricks.content.kinetics.StressManaConverterRenderer;
import com.simibubi.create.content.kinetics.base.OrientedRotatingVisual;
import com.tterrag.registrate.util.entry.BlockEntityEntry;

public final class CreateTricksBlockEntityTypes {
	public static final BlockEntityEntry<StressManaConverterBlockEntity> STRESS_MANA_CONVERTER = REGISTRATE
			.blockEntity("stress_mana_converter", StressManaConverterBlockEntity::new)
			.visual(() -> OrientedRotatingVisual.of(CreateTricksPartialModels.STRESS_MANA_CONVERTER_INNER), false)
			.validBlocks(CreateTricksBlocks.STRESS_MANA_CONVERTER)
			.renderer(() -> StressManaConverterRenderer::new)
			.register();

	private CreateTricksBlockEntityTypes() {}

	public static void register() {}
}
