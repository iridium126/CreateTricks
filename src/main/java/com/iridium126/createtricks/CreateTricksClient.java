package com.iridium126.createtricks;

import com.iridium126.createtricks.ponder.CreateTricksPonderPlugin;

import net.createmod.ponder.foundation.PonderIndex;
//import net.minecraft.client.renderer.ItemBlockRenderTypes;
//import net.minecraft.client.renderer.RenderType;
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
			//ItemBlockRenderTypes.setRenderLayer(CreateTricksFluids.LIQUID_MANA.get(), RenderType.translucent());
			//ItemBlockRenderTypes.setRenderLayer(CreateTricksFluids.LIQUID_MANA.getSource(), RenderType.translucent());
			PonderIndex.addPlugin(new CreateTricksPonderPlugin());
		});
	}
}
