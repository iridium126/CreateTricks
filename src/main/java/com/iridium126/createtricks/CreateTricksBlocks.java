package com.iridium126.createtricks;

import static com.simibubi.create.foundation.data.ModelGen.customItemModel;
import static com.simibubi.create.foundation.data.TagGen.pickaxeOnly;

import com.iridium126.createtricks.content.kinetics.StressManaConverterBlock;
import com.simibubi.create.Create;
import com.simibubi.create.foundation.data.SharedProperties;
import com.tterrag.registrate.util.entry.BlockEntry;

import net.minecraft.world.level.material.MapColor;

public final class CreateTricksBlocks {
	public static final BlockEntry<StressManaConverterBlock> STRESS_MANA_CONVERTER =
			CreateTricksRegistrate.REGISTRATE.block("stress_mana_converter", StressManaConverterBlock::new)
					.initialProperties(SharedProperties::stone)
					.properties(p -> p.mapColor(MapColor.METAL))
					.transform(pickaxeOnly())
					.blockstate((ctx, prov) -> prov.simpleBlock(ctx.get(), prov.models()
							.withExistingParent(ctx.getName(), prov.mcLoc("block/cube_all"))
							.texture("all", Create.asResource("block/andesite_casing"))))
					.item()
					.transform(customItemModel())
					.register();

	private CreateTricksBlocks() {}

	public static void register() {}
}
