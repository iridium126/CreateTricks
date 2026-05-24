package com.iridium126.createtricks;

import static com.iridium126.createtricks.CreateTricks.REGISTRATE;

import com.iridium126.createtricks.content.kinetics.StressManaConverterBlock;
import com.simibubi.create.foundation.data.ModelGen;
import com.simibubi.create.foundation.data.SharedProperties;
import com.simibubi.create.foundation.data.TagGen;
import com.tterrag.registrate.util.entry.BlockEntry;

import net.minecraft.world.level.material.MapColor;

public final class CreateTricksBlocks {
	static {
		REGISTRATE.defaultCreativeTab(CreateTricksCreativeModeTabs.MAIN_TAB.getKey());
	}

	public static final BlockEntry<StressManaConverterBlock> STRESS_MANA_CONVERTER = REGISTRATE
			.block("stress_mana_converter", StressManaConverterBlock::new)
			.initialProperties(SharedProperties::stone)
			.properties(p -> p.mapColor(MapColor.METAL))
			.blockstate((ctx, prov) -> prov.simpleBlock(ctx.get(), prov.models()
					.getExistingFile(prov.modLoc("block/stress_mana_converter"))))
			.transform(TagGen.pickaxeOnly())
			.lang("Stress Mana Converter")
			.item()
			.transform(ModelGen.customItemModel())
			.register();

	private CreateTricksBlocks() {}

	public static void register() {}
}
