package com.iridium126.createmanaindustry.dimension;

import com.iridium126.createmanaindustry.dimension.cube.AllvrCubeMap;
import com.iridium126.createmanaindustry.dimension.cube.AllvrServerLevelDuck;

import net.minecraft.server.level.ServerLevel;
import net.neoforged.neoforge.event.tick.LevelTickEvent;

/**
 * Server event wiring for the allay dimension: drives cube loading on the
 * dimension's tick. Registered from the mod constructor on
 * {@code NeoForge.EVENT_BUS}.
 */
public final class AllvrServerHandler {

    public static void onLevelTick(LevelTickEvent.Post event) {
        if (event.getLevel() instanceof ServerLevel serverLevel
            && serverLevel.dimension() == AllvrDimensions.ALLAY_LEVEL) {
            AllvrCubeMap map = ((AllvrServerLevelDuck) serverLevel).allvr$getCubeMap();
            if (map != null) {
                map.tick();
            }
        }
    }

    private AllvrServerHandler() {}
}
