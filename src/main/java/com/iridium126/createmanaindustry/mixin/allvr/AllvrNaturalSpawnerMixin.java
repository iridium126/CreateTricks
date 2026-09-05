package com.iridium126.createmanaindustry.mixin.allvr;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import com.iridium126.createmanaindustry.dimension.AllvrDimensions;

import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.NaturalSpawner;

/**
 * Cancels vanilla mob spawning inside the allay dimension (1.21.1
 * {@code NaturalSpawner#spawnForChunk}, driven per column per tick from
 * {@code ServerChunkCache.tickChunks} within the natural-spawn radius).
 * <p>
 * The columns are deterministic air shells: the sampled positions come from
 * the column heightmap (the formal window's bottom edge), so with players at
 * cube Y nothing passes the 128-block player-distance gate and the whole
 * 7-category loop is pure overhead — while its block probes still reach the
 * cube layer through the Level routing, i.e. it is not only wasted but also
 * probing the wrong Y band. Phase 7 replaces this with cube-side spawning
 * rules (doc §13); until then gameplay spawning is intentionally absent.
 * Note the caller also runs {@code NaturalSpawner.createState} per tick
 * (O(all entities)) and {@code tickCustomSpawners} — neither is cancelled
 * here; this mixin only removes the per-column loop body.
 */
@Mixin(NaturalSpawner.class)
public abstract class AllvrNaturalSpawnerMixin {

    @Inject(method = "spawnForChunk", at = @At("HEAD"), cancellable = true)
    private static void allvr$skipSpawnForChunk(ServerLevel level, net.minecraft.world.level.chunk.LevelChunk chunk,
                                                NaturalSpawner.SpawnState spawnState, boolean spawnFriendlies,
                                                boolean spawnMonsters, boolean forcedDespawn, CallbackInfo ci) {
        if (level.dimension() == AllvrDimensions.ALLAY_LEVEL) {
            ci.cancel();
        }
    }
}
