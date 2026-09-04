package com.iridium126.createmanaindustry;

import com.iridium126.createmanaindustry.client.particles.engine.CMIParticleEngine;
import com.iridium126.createmanaindustry.client.render.fuelrod.FuelRodBloomHandler;
import com.iridium126.createmanaindustry.client.render.mist.MistClientHandler;
import com.iridium126.createmanaindustry.client.render.InlineTrickRenderer;
import com.iridium126.createmanaindustry.ponder.CMIPonderPlugin;
import com.samsthenerd.inline.api.client.InlineClientAPI;
import com.simibubi.create.content.decoration.copycat.CopycatBlock;

import net.createmod.ponder.foundation.PonderIndex;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.ItemBlockRenderTypes;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.world.phys.EntityHitResult;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.ModContainer;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.fml.common.Mod;
import net.neoforged.fml.event.lifecycle.FMLClientSetupEvent;
import net.neoforged.neoforge.client.event.RegisterColorHandlersEvent;
import net.neoforged.neoforge.client.event.RenderLevelStageEvent;
import net.neoforged.neoforge.client.event.InputEvent;
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

        // Bridge the client cube cache into the common-side collision mixin —
        // the common bytecode must not reference client classes (dedicated
        // servers never load them), so the resolver is injected here instead.
        com.iridium126.createmanaindustry.dimension.AllvrClientBlockHook.setResolver(
            com.iridium126.createmanaindustry.client.dimension.AllvrClientCubeCache::getBlockState);

        // Register the Veil post-processing uniform injection listeners.
        // The mist/fuel-rod-glow pipelines are added/removed on demand when
        // atomizers activate / rods form — see MistClientHandler.setActive()
        // and FuelRodBloomHandler.onRodSync(). The Veil pipelines and the iris
        // gbuffer hooks are all initialised inside the handlers' init().
        if (CreateManaIndustry.VEIL_ACTIVE) {
            MistClientHandler.init();
            FuelRodBloomHandler.init();
        }

        // The shader-pack MODEL-particle path needs no client init here: the
        // pack entity merge is reached through its renderLevel mixin, which
        // checks IRISVEIL_ACTIVE before touching irisveil-typed classes, and a
        // merge failure simply falls back to the engine's plain AFTER_LEVEL path.

        if (CreateManaIndustry.HEX_ACTIVE && CreateManaIndustry.TRICKSTER_ACTIVE)
            InlineClientAPI.INSTANCE.addRenderer(InlineTrickRenderer.INSTANCE);
    }

    /**
     * GPU particle engine split-frame hooks. The compute half runs at
     * AFTER_SKY — the earliest level-stage event of the pass — so its keygen
     * culls against THIS frame's camera before anything else renders; the
     * shader-pack merge hook fires mid-renderLevel, between AFTER_SKY and
     * AFTER_LEVEL, and thereby consumes a current-frame permutation (fixes the
     * one-frame entry lag of MODEL particles under fast view rotation). Every
     * draw call stays at AFTER_LEVEL reading the just-committed pool.
     */
    @SubscribeEvent
    private static void onRenderLevelStage(RenderLevelStageEvent event) {
        // NB: Stage is a plain class of constants (not an enum) in this NeoForge
        // version, so stage dispatch must use identity comparison, not switch.
        var stage = event.getStage();
        if (stage == RenderLevelStageEvent.Stage.AFTER_SKY) {
            CMIParticleEngine.INSTANCE.beginFrame(event.getCamera(),
                    event.getModelViewMatrix(), event.getProjectionMatrix(), event.getPartialTick());
        } else if (stage == RenderLevelStageEvent.Stage.AFTER_LEVEL) {
            CMIParticleEngine.INSTANCE.endFrame(event.getCamera(),
                    event.getModelViewMatrix(), event.getProjectionMatrix());
        }
    }

    /** Releases the GPU particle engine's resources when the game shuts down. */
    @SubscribeEvent
    private static void onGameShuttingDown(GameShuttingDownEvent event) {
        CMIParticleEngine.INSTANCE.close();
    }

    /**
     * Melee attack against MODEL (allay) particles: when the engine's
     * per-frame GPU hit query says an allay is under the crosshair (and the
     * CPU block-occlusion check agrees), the vanilla handling is cancelled and
     * replaced with a fully local synthetic attack ({@code
     * CMIParticleEngine.handlePlayerAttack}) -- cooldown scaling, crits,
     * enchantments and knockback all mirror {@code Player.attack}, but no
     * interact packet is sent. A miss falls through to the vanilla miss swing.
     */
    @SubscribeEvent
    private static void onAttackKey(InputEvent.InteractionKeyMappingTriggered event) {
        if (event.isAttack() && CMIParticleEngine.INSTANCE.handlePlayerAttack())
            event.setCanceled(true);
    }

    /**
     * Use-key fall-through for the synthetic crosshair pick: {@code
     * CMIParticleEngine.injectCrosshairPick} surfaces an ENTITY hit on the
     * client-side proxy ({@code proxyFor}), and vanilla {@code startUseItem}
     * would run the proxy's {@code Allay.mobInteract} as client-side
     * prediction — handing the held item to a throwaway object (count
     * desyncs, ITEM_GIVEN plays, the server never sees an entity). The storm
     * allay is not an entity: the click is cancelled and replayed against
     * the pre-injection vanilla pick result ({@code
     * CMIParticleEngine.replayVanillaUse}) exactly as if the particle weren't
     * there — items stay in hand, real entities and blocks behind the allay
     * respond normally. Real allay entities never match this gate (plain
     * engine object identity, and vanilla picking always outranks the
     * injected proxy when one is nearer).
     */
    @SubscribeEvent
    private static void onUseKey(InputEvent.InteractionKeyMappingTriggered event) {
        if (!event.isUseItem())
            return;
        Minecraft mc = Minecraft.getInstance();
        if (!(mc.hitResult instanceof EntityHitResult hit)
                || !CMIParticleEngine.INSTANCE.isSyntheticPickTarget(hit.getEntity()))
            return;
        event.setCanceled(true);
        event.setSwingHand(false);
        CMIParticleEngine.INSTANCE.replayVanillaUse(mc);
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
            // Allay-dimension streamed cubes die with the level (dimension
            // switch or logout); the server restarts the stream on re-entry.
            com.iridium126.createmanaindustry.client.dimension.AllvrClientCubeCache.clear();
            // The particle engine is self-hosted GL — reset regardless of Veil.
            // The reset must be SYNCHRONOUS here (NeoForge posts Unload inside
            // setLevel, BEFORE the dimension loading screen): the old queued
            // clear() was only drained at the new level's first compute frame,
            // so a new-dimension storm ACTIVATE applied on the loading screen
            // in between was wiped right after (the server never re-sends an
            // ACTIVATE until the player re-enters the activation range).
            CMIParticleEngine.INSTANCE.onLevelChanged();
        }
    }

    /**
     * Clears GPU particles on world join as well (the engine pool is world
     * anchored; entering a new world must not keep the previous one's particles).
     * Synchronous for the same reason as {@link #onLevelUnload}.
     */
    @SubscribeEvent
    private static void onLevelLoad(LevelEvent.Load event) {
        if (event.getLevel() instanceof net.minecraft.client.multiplayer.ClientLevel clientLevel) {
            CMIParticleEngine.INSTANCE.onLevelChanged();
            // Bind the cube cache to the new client level (allay dimension only).
            com.iridium126.createmanaindustry.client.dimension.AllvrClientCubeCache.onLevelChanged(clientLevel);
        }
    }
}