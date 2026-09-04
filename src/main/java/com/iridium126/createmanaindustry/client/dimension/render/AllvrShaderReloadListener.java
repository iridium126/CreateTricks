package com.iridium126.createmanaindustry.client.dimension.render;

import com.iridium126.createmanaindustry.CreateManaIndustry;

import net.minecraft.server.packs.resources.ResourceManager;
import net.minecraft.server.packs.resources.SimplePreparableReloadListener;
import net.minecraft.util.profiling.ProfilerFiller;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.RegisterClientReloadListenersEvent;

/**
 * Rebuilds the ALLVR terrain program on resource reload (F3+T), mirroring the
 * particle engine's {@code ParticleShaderReloadListener}.
 */
@EventBusSubscriber(modid = CreateManaIndustry.MODID, value = Dist.CLIENT)
public final class AllvrShaderReloadListener {

    private AllvrShaderReloadListener() {}

    @SubscribeEvent
    public static void onRegisterReloadListeners(RegisterClientReloadListenersEvent event) {
        event.registerReloadListener(new SimplePreparableReloadListener<Void>() {
            @Override
            protected Void prepare(ResourceManager resourceManager, ProfilerFiller profilerFiller) {
                return null;
            }

            @Override
            protected void apply(Void ignored, ResourceManager resourceManager, ProfilerFiller profilerFiller) {
                AllvrRenderer.INSTANCE.requestShaderRebuild();
            }
        });
    }
}
