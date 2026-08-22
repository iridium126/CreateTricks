package com.iridium126.createmanaindustry.content.kinetics.temporarykinetics;

import com.iridium126.createmanaindustry.CMIAttachments;
import com.iridium126.createmanaindustry.CreateManaIndustry;

import net.minecraft.server.level.ServerLevel;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
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
}
