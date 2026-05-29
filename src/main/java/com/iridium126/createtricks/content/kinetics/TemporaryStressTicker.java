package com.iridium126.createtricks.content.kinetics;

import com.iridium126.createtricks.CreateTricks;

import net.minecraft.server.level.ServerLevel;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.tick.LevelTickEvent;

@EventBusSubscriber(modid = CreateTricks.MODID)
public final class TemporaryStressTicker {
	private TemporaryStressTicker() {}

	@SubscribeEvent
	public static void tick(LevelTickEvent.Post event) {
		if (event.getLevel() instanceof ServerLevel level)
			TemporaryStress.tick(level);
	}
}
