package com.iridium126.createmanaindustry;

import com.iridium126.createmanaindustry.client.render.ClientMistHandler;
import com.iridium126.createmanaindustry.client.render.InlineTrickRenderer;
import com.iridium126.createmanaindustry.ponder.CMIPonderPlugin;
import com.samsthenerd.inline.api.client.InlineClientAPI;

import net.createmod.ponder.foundation.PonderIndex;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.ModContainer;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.fml.common.Mod;
import net.neoforged.fml.event.lifecycle.FMLClientSetupEvent;
import net.neoforged.neoforge.client.gui.ConfigurationScreen;
import net.neoforged.neoforge.client.gui.IConfigScreenFactory;

// This class will not load on dedicated servers. Accessing client side code from here is safe.
@Mod(value = CreateManaIndustry.MODID, dist = Dist.CLIENT)
@EventBusSubscriber(modid = CreateManaIndustry.MODID, value = Dist.CLIENT)
public class CreateManaIndustryClient {
    public CreateManaIndustryClient(ModContainer container) {
        container.registerExtensionPoint(IConfigScreenFactory.class, ConfigurationScreen::new);

        // Register the Veil post-processing uniform injection listener.
        // The mist pipeline is added/removed on demand when atomizers
        // activate/deactivate — see ClientMistHandler.setActive().
        // The Veil mist pipeline and the iris gbuffer hook are both initialised
        // inside ClientMistHandler.init().
        if (CreateManaIndustry.VEIL_ACTIVE)
            ClientMistHandler.init();

        if (CreateManaIndustry.HEX_ACTIVE && CreateManaIndustry.TRICKSTER_ACTIVE)
            InlineClientAPI.INSTANCE.addRenderer(InlineTrickRenderer.INSTANCE);
    }

    @SubscribeEvent
    private static void onClientSetup(FMLClientSetupEvent event) {
        event.enqueueWork(() -> {
            PonderIndex.addPlugin(new CMIPonderPlugin());
        });
    }
}
