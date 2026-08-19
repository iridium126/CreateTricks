package com.iridium126.createmanaindustry.client.particles.engine;

import com.iridium126.createmanaindustry.CreateManaIndustry;

import net.minecraft.server.packs.resources.ResourceManager;
import net.minecraft.server.packs.resources.SimplePreparableReloadListener;
import net.minecraft.util.profiling.ProfilerFiller;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.RegisterClientReloadListenersEvent;

/**
 * Rebuilds the particle engine's self-hosted GLSL programs on resource reload
 * (F3+T), so shader edits take effect without a restart.
 */
@EventBusSubscriber(modid = CreateManaIndustry.MODID, value = Dist.CLIENT)
public final class ParticleShaderReloadListener {

    private ParticleShaderReloadListener() {
    }

    @SubscribeEvent
    public static void onRegisterReloadListeners(RegisterClientReloadListenersEvent event) {
        event.registerReloadListener(new SimplePreparableReloadListener<Void>() {
            @Override
            protected Void prepare(ResourceManager resourceManager, ProfilerFiller profilerFiller) {
                return null;
            }

            @Override
            protected void apply(Void ignored, ResourceManager resourceManager, ProfilerFiller profilerFiller) {
                // Runs on the game thread after a resource reload; the engine
                // recompiles lazily on the render thread's next frame.
                CMIParticleEngine.INSTANCE.requestProgramRebuild();
            }
        });
    }
}
