package com.iridium126.createtricks;

import com.iridium126.createtricks.ponder.CreateTricksPonderPlugin;

import net.createmod.ponder.foundation.PonderIndex;
//import net.minecraft.client.renderer.ItemBlockRenderTypes;
//import net.minecraft.client.renderer.RenderType;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.ModContainer;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.fml.common.Mod;
import net.neoforged.fml.event.lifecycle.FMLClientSetupEvent;
import net.neoforged.neoforge.client.gui.ConfigurationScreen;
import net.neoforged.neoforge.client.gui.IConfigScreenFactory;

// This class will not load on dedicated servers. Accessing client side code from here is safe.
@Mod(value = CreateTricks.MODID, dist = Dist.CLIENT)
// You can use EventBusSubscriber to automatically register all static methods in the class annotated with @SubscribeEvent
@EventBusSubscriber(modid = CreateTricks.MODID, value = Dist.CLIENT)
public class CreateTricksClient {
	public CreateTricksClient(ModContainer container) {
        // Allows NeoForge to create a config screen for this mod's configs.
        // The config screen is accessed by going to the Mods screen > clicking on your mod > clicking on config.
        // Do not forget to add translations for your config options to the en_us.json file.
        container.registerExtensionPoint(IConfigScreenFactory.class, ConfigurationScreen::new);
    }

    @SubscribeEvent
	private static void onClientSetup(FMLClientSetupEvent event) {
		event.enqueueWork(() -> {
			CreateTricksPartialModels.init();
			//ItemBlockRenderTypes.setRenderLayer(CreateTricksFluids.LIQUID_MANA.get(), RenderType.translucent());
			//ItemBlockRenderTypes.setRenderLayer(CreateTricksFluids.LIQUID_MANA.getSource(), RenderType.translucent());
			PonderIndex.addPlugin(new CreateTricksPonderPlugin());
		});
	}
}
