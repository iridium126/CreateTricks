package com.iridium126.createmanaindustry;

import com.iridium126.createmanaindustry.client.render.MistClientHandler;
import com.iridium126.createmanaindustry.client.render.InlineTrickRenderer;
import com.iridium126.createmanaindustry.ponder.CMIPonderPlugin;
import com.samsthenerd.inline.api.client.InlineClientAPI;

import net.createmod.ponder.foundation.PonderIndex;
import net.minecraft.client.renderer.ItemBlockRenderTypes;
import net.minecraft.client.renderer.RenderType;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.ModContainer;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.fml.common.Mod;
import net.neoforged.fml.event.lifecycle.FMLClientSetupEvent;
import net.neoforged.neoforge.client.gui.ConfigurationScreen;
import net.neoforged.neoforge.client.gui.IConfigScreenFactory;
import net.neoforged.neoforge.event.level.LevelEvent;

// This class will not load on dedicated servers. Accessing client side code from here is safe.
@Mod(value = CreateManaIndustry.MODID, dist = Dist.CLIENT)
@EventBusSubscriber(modid = CreateManaIndustry.MODID, value = Dist.CLIENT)
public class CreateManaIndustryClient {
    public CreateManaIndustryClient(ModContainer container) {
        container.registerExtensionPoint(IConfigScreenFactory.class, ConfigurationScreen::new);

        // Register the Veil post-processing uniform injection listener.
        // The mist pipeline is added/removed on demand when atomizers
        // activate/deactivate — see MistClientHandler.setActive().
        // The Veil mist pipeline and the iris gbuffer hook are both initialised
        // inside MistClientHandler.init().
        if (CreateManaIndustry.VEIL_ACTIVE)
            MistClientHandler.init();

        if (CreateManaIndustry.HEX_ACTIVE && CreateManaIndustry.TRICKSTER_ACTIVE)
            InlineClientAPI.INSTANCE.addRenderer(InlineTrickRenderer.INSTANCE);
    }

    @SubscribeEvent
    private static void onClientSetup(FMLClientSetupEvent event) {
        event.enqueueWork(() -> {
            PonderIndex.addPlugin(new CMIPonderPlugin());

            // Render Coolant through the translucent pass like Minecraft water.
            // Registered here (not via FluidBuilder.renderType) because
            // CMIFluids loads on dedicated servers, where a RenderType lambda
            // would trip the RuntimeDistCleaner. Both variants are covered.
            ItemBlockRenderTypes.setRenderLayer(CMIFluids.COOLANT.get(), RenderType.translucent());
            ItemBlockRenderTypes.setRenderLayer(CMIFluids.COOLANT.getSource(), RenderType.translucent());
        });
    }

    /** Clears client mist sources when the client's level/dimension changes. */
    @SubscribeEvent
    private static void onLevelUnload(LevelEvent.Unload event) {
        // In single-player the integrated server posts Unload for its ServerLevels
        // on the same bus — only react to the CLIENT's own level so a server
        // dimension unload (e.g. the nether timing out) doesn't clear the
        // overworld's mist. MistClientHandler sources are keyed by BlockPos only
        // (no dimension), so without a clear on dimension switch they'd linger at
        // the same absolute coordinates in the new dimension.
        if (event.getLevel() instanceof net.minecraft.client.multiplayer.ClientLevel
                && CreateManaIndustry.VEIL_ACTIVE)
            MistClientHandler.clearAll();
    }
}
