package com.iridium126.createmanaindustry.mixin.allvr;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;

import com.iridium126.createmanaindustry.dimension.AllvrDimensions;
import com.iridium126.createmanaindustry.dimension.cube.AllvrCubeMap;
import com.iridium126.createmanaindustry.dimension.cube.AllvrServerLevelDuck;

import net.minecraft.server.level.ServerLevel;

/**
 * Attaches the per-level {@link AllvrCubeMap} to the allay dimension's
 * {@link ServerLevel}. The map is created lazily on first block access or
 * tick (server thread only), so nothing runs for other dimensions and no
 * work happens before the dimension is actually entered.
 */
@Mixin(ServerLevel.class)
public abstract class AllvrServerLevelMixin implements AllvrServerLevelDuck {

    @Unique
    private AllvrCubeMap allvr$cubeMap;

    @Override
    public AllvrCubeMap allvr$getCubeMap() {
        ServerLevel self = (ServerLevel) (Object) this;
        if (self.dimension() != AllvrDimensions.ALLAY_LEVEL) {
            return null;
        }
        if (allvr$cubeMap == null) {
            allvr$cubeMap = new AllvrCubeMap(self);
        }
        return allvr$cubeMap;
    }
}
