package com.iridium126.createmanaindustry.mixin.allvr;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import com.iridium126.createmanaindustry.dimension.AllvrDimensions;
import com.iridium126.createmanaindustry.dimension.cube.AllvrCubeMap;
import com.iridium126.createmanaindustry.dimension.cube.AllvrServerLevelDuck;

import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.chunk.LevelChunk;

/**
 * Attaches the per-level {@link AllvrCubeMap} to the allay dimension's
 * {@link ServerLevel}. The map is created lazily on first block access or
 * tick (server thread only), so nothing runs for other dimensions and no
 * work happens before the dimension is actually entered.
 * <p>
 * Also cancels vanilla per-column chunk ticking ({@code tickChunk}: thunder
 * target search, ice/snow RNG and the per-section random-tick loop) inside
 * the allay dimension — columns are deterministic air shells whose sections
 * all fail the {@code isRandomlyTicking()} counter check, so the body is pure
 * overhead there, and cube blocks never receive random ticks through this
 * path anyway. Phase 7 replaces it with cube-side random/scheduled ticking
 * (doc §13); until then gameplay ticking is intentionally absent.
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

    @Inject(method = "tickChunk", at = @At("HEAD"), cancellable = true)
    private void allvr$skipTickChunk(LevelChunk chunk, int randomTickSpeed, CallbackInfo ci) {
        if (((ServerLevel) (Object) this).dimension() == AllvrDimensions.ALLAY_LEVEL) {
            ci.cancel();
        }
    }
}
