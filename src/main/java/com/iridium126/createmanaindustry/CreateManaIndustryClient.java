package com.iridium126.createmanaindustry;

import com.iridium126.createmanaindustry.client.particles.engine.CMIParticleEngine;
import com.iridium126.createmanaindustry.client.render.FuelRodBloomHandler;
import com.iridium126.createmanaindustry.client.render.MistClientHandler;
import com.iridium126.createmanaindustry.client.render.InlineTrickRenderer;
import com.iridium126.createmanaindustry.ponder.CMIPonderPlugin;
import com.samsthenerd.inline.api.client.InlineClientAPI;
import com.simibubi.create.content.decoration.copycat.CopycatBlock;

import net.createmod.ponder.foundation.PonderIndex;
import net.minecraft.client.renderer.ItemBlockRenderTypes;
import net.minecraft.client.renderer.RenderType;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.ModContainer;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.fml.common.Mod;
import net.neoforged.fml.event.lifecycle.FMLClientSetupEvent;
import net.neoforged.neoforge.client.event.RegisterColorHandlersEvent;
import net.neoforged.neoforge.client.event.RenderLevelStageEvent;
import net.neoforged.neoforge.client.gui.ConfigurationScreen;
import net.neoforged.neoforge.client.gui.IConfigScreenFactory;
import net.neoforged.neoforge.event.GameShuttingDownEvent;
import net.neoforged.neoforge.event.level.LevelEvent;

// This class will not load on dedicated servers. Accessing client side code from here is safe.
@Mod(value = CreateManaIndustry.MODID, dist = Dist.CLIENT)
@EventBusSubscriber(modid = CreateManaIndustry.MODID, value = Dist.CLIENT)
public class CreateManaIndustryClient {
    public CreateManaIndustryClient(ModContainer container) {
        container.registerExtensionPoint(IConfigScreenFactory.class, ConfigurationScreen::new);

        // Register the Veil post-processing uniform injection listeners.
        // The mist/fuel-rod-glow pipelines are added/removed on demand when
        // atomizers activate / rods form — see MistClientHandler.setActive()
        // and FuelRodBloomHandler.onRodSync(). The Veil pipelines and the iris
        // gbuffer hooks are all initialised inside the handlers' init().
        if (CreateManaIndustry.VEIL_ACTIVE) {
            MistClientHandler.init();
            FuelRodBloomHandler.init();
        }

        if (CreateManaIndustry.HEX_ACTIVE && CreateManaIndustry.TRICKSTER_ACTIVE)
            InlineClientAPI.INSTANCE.addRenderer(InlineTrickRenderer.INSTANCE);
    }

    /**
     * GPU particle engine frame hook. Uses the native NeoForge level-stage
     * event (the same AFTER_LEVEL slot the mod's mist/glow pipelines use, but
     * served through the platform event so the engine works with or without Veil).
     */
    @SubscribeEvent
    private static void onRenderLevelStage(RenderLevelStageEvent event) {
        if (event.getStage() == RenderLevelStageEvent.Stage.AFTER_LEVEL) {
            CMIParticleEngine.INSTANCE.renderFrame(event.getCamera(),
                    event.getModelViewMatrix(), event.getProjectionMatrix(), event.getPartialTick());
        }
    }

    /** Releases the GPU particle engine's resources when the game shuts down. */
    @SubscribeEvent
    private static void onGameShuttingDown(GameShuttingDownEvent event) {
        CMIParticleEngine.INSTANCE.close();
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

    /**
     * Tints the copycat shell of the fuel tank with the material's biome color
     * (grass, leaves…): the tank's own shell quads carry the material's tint
     * index (see {@code FuelTankModel}, which re-textures those quads), and
     * Create's wrapped block color resolves that index against the material.
     */
    @SubscribeEvent
    private static void onRegisterBlockColors(RegisterColorHandlersEvent.Block event) {
		event.register(CopycatBlock.wrappedColor(), CMIBlocks.MOLTEN_SALT_FUEL_TANK.get());
	}

    /**
     * Clears client mist sources, fuel rod glows and GPU particles when the
     * client's level/dimension changes.
     */
    @SubscribeEvent
    private static void onLevelUnload(LevelEvent.Unload event) {
        // In single-player the integrated server posts Unload for its ServerLevels
        // on the same bus — only react to the CLIENT's own level so a server
        // dimension unload (e.g. the nether timing out) doesn't clear the
        // overworld's effects. Both handlers key their sources by BlockPos only
        // (no dimension), so without a clear on dimension switch they'd linger at
        // the same absolute coordinates in the new dimension.
        if (event.getLevel() instanceof net.minecraft.client.multiplayer.ClientLevel) {
            if (CreateManaIndustry.VEIL_ACTIVE) {
                MistClientHandler.clearAll();
                FuelRodBloomHandler.clearAll();
            }
            // The particle engine is self-hosted GL — clear regardless of Veil.
            CMIParticleEngine.INSTANCE.clear();
        }
    }

    /**
     * Clears GPU particles on world join as well (the engine pool is world
     * anchored; entering a new world must not keep the previous one's particles).
     */
    @SubscribeEvent
    private static void onLevelLoad(LevelEvent.Load event) {
        if (event.getLevel() instanceof net.minecraft.client.multiplayer.ClientLevel) {
            CMIParticleEngine.INSTANCE.clear();
        }
    }
}
