package com.iridium126.createtricks;

import static com.iridium126.createtricks.CreateTricks.REGISTRATE;

import com.iridium126.createtricks.content.kinetics.StressManaConverterBlock;
import com.iridium126.createtricks.content.kinetics.StressManaConverterGenerator;
import com.simibubi.create.foundation.data.ModelGen;
import com.simibubi.create.foundation.data.TagGen;
import com.tterrag.registrate.util.entry.BlockEntry;

import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.material.MapColor;

public final class CreateTricksBlocks {
	public static final BlockEntry<StressManaConverterBlock> STRESS_MANA_CONVERTER = REGISTRATE
			.block("stress_mana_converter", StressManaConverterBlock::new)
			.initialProperties(() -> Blocks.IRON_BLOCK)
			.properties(p -> p.mapColor(MapColor.TERRACOTTA_YELLOW))
			.blockstate(new StressManaConverterGenerator()::generate)
			.transform(TagGen.pickaxeOnly())
			.item()
			.transform(ModelGen.customItemModel())
			.register();

	private CreateTricksBlocks() {}

	public static void register() {}
}
