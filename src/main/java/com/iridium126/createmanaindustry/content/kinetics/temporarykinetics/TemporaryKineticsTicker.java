package com.iridium126.createmanaindustry.content.kinetics.temporarykinetics;

import com.iridium126.createmanaindustry.CMIAttachments;
import com.iridium126.createmanaindustry.CreateManaIndustry;

import net.minecraft.server.level.ServerLevel;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.level.ChunkEvent;
import net.neoforged.neoforge.event.tick.LevelTickEvent;

@EventBusSubscriber(modid = CreateManaIndustry.MODID)
public final class TemporaryKineticsTicker {
    private TemporaryKineticsTicker() {}

    @SubscribeEvent
    public static void tick(LevelTickEvent.Post event) {
        if (event.getLevel() instanceof ServerLevel level
                && level.hasData(CMIAttachments.TEMPORARY_KINETICS.get()))
            TemporaryKinetics.tick(level);
    }

    /**
     * Same-tick finalization of deferred expiries: a temporary state whose
     * countdown reached zero while its chunk was unloaded is resolved here,
     * immediately after that chunk's block entities registered. The level tick
     * remains as the fallback sweep; {@code hasData} keeps idle levels from
     * lazily instantiating an empty store on every chunk load.
     */
    @SubscribeEvent
    public static void onChunkLoad(ChunkEvent.Load event) {
        if (!(event.getLevel() instanceof ServerLevel level)
                || !level.hasData(CMIAttachments.TEMPORARY_KINETICS.get()))
            return;
        level.getData(CMIAttachments.TEMPORARY_KINETICS.get())
            .drainPending(level, event.getChunk().getPos());
    }
}
