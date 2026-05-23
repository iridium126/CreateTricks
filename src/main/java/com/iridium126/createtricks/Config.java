package com.iridium126.createtricks;

import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.fml.event.config.ModConfigEvent;
import net.neoforged.neoforge.common.ModConfigSpec;

@EventBusSubscriber(modid = CreateTricks.MODID)
public final class Config {
	private static final ModConfigSpec.Builder BUILDER = new ModConfigSpec.Builder();

	private static final ModConfigSpec.DoubleValue MANA_PER_STRESS = BUILDER
			.comment("Mana added per stress unit consumed each tick when converting kinetic stress into knot mana.")
			.defineInRange("manaPerStress", 1.0, 0.0, 1000000.0);

	public static final ModConfigSpec SPEC = BUILDER.build();

	public static double manaPerStress = 1.0;

	private Config() {}

	@SubscribeEvent
	static void onLoad(ModConfigEvent event) {
		if (event.getConfig().getSpec() == SPEC)
			manaPerStress = MANA_PER_STRESS.get();
	}
}
