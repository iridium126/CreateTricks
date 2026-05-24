package com.iridium126.createtricks;

import com.iridium126.createtricks.CreateTricksPartialModels;
import com.iridium126.createtricks.ponder.CreateTricksPonderPlugin;

import net.createmod.ponder.foundation.PonderIndex;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.common.Mod;
import net.neoforged.fml.event.lifecycle.FMLClientSetupEvent;

@Mod(value = CreateTricks.MODID, dist = Dist.CLIENT)
public class CreateTricksClient {
	public CreateTricksClient(IEventBus modEventBus) {
		modEventBus.addListener(CreateTricksClient::onClientSetup);
	}

	private static void onClientSetup(FMLClientSetupEvent event) {
		event.enqueueWork(() -> {
			CreateTricksPartialModels.init();
			PonderIndex.addPlugin(new CreateTricksPonderPlugin());
		});
	}
}
