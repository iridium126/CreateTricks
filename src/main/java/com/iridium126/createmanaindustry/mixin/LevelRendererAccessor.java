package com.iridium126.createmanaindustry.mixin;

import java.util.Map;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.client.resources.sounds.SoundInstance;
import net.minecraft.core.BlockPos;

import net.neoforged.api.distmarker.Dist;
import net.neoforged.api.distmarker.OnlyIn;

/**
 * Exposes the client's per-position jukebox sound map so the
 * {@code AllayBurnerMovementBehaviour} can adopt the vanilla sound instance
 * when a burning Allay Burner with a record rides a contraption (the vanilla
 * instance is not tickable, so it would stay behind at the old position).
 */
@OnlyIn(Dist.CLIENT)
@Mixin(LevelRenderer.class)
public interface LevelRendererAccessor {
    @Accessor("playingJukeboxSongs")
    Map<BlockPos, SoundInstance> createmanaindustry$getPlayingJukeboxSongs();
}
