package com.iridium126.createmanaindustry.content.burner;

import java.util.Map;

import com.iridium126.createmanaindustry.content.burner.AllayBurnerMovementBehaviour.ContraptionData;
import com.iridium126.createmanaindustry.mixin.render.LevelRendererAccessor;
import com.simibubi.create.content.contraptions.behaviour.MovementContext;

import net.minecraft.client.Minecraft;
import net.minecraft.client.resources.sounds.AbstractSoundInstance;
import net.minecraft.client.resources.sounds.SoundInstance;
import net.minecraft.client.resources.sounds.TickableSoundInstance;
import net.minecraft.core.BlockPos;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.phys.Vec3;

import net.neoforged.api.distmarker.Dist;
import net.neoforged.api.distmarker.OnlyIn;

/**
 * Client-side jukebox sound management for Allay Burners riding contraptions.
 * Kept out of {@link AllayBurnerMovementBehaviour} because the sound classes
 * here are client-only ({@code net.minecraft.client.*}); the behaviour class
 * is loaded on dedicated servers, so all sound logic lives in this class and
 * is only ever reached from {@code @OnlyIn(Dist.CLIENT)} methods.
 */
@OnlyIn(Dist.CLIENT)
public class AllayBurnerClientSounds {

    /**
     * Adopts the vanilla jukebox sound of a burner with a record and makes it
     * follow the contraption. Vanilla's {@code SimpleSoundInstance} is not
     * tickable — its position is read once at play time, so the music would
     * stay behind at the assembly position. On the first ticks after assembly
     * (the contraption still sits at the capture site) the vanilla instance is
     * looked up in {@code LevelRenderer.playingJukeboxSongs} at the burner's
     * position, stopped, and replaced with a mod-owned tickable instance that
     * relocates itself to {@link MovementContext#position} every tick. No
     * custom packets: a late-joining player simply has no sound to adopt.
     */
    public static void followMusic(MovementContext context, ContraptionData data) {
        // Only burners with a record inserted have a jukebox sound to follow.
        if (!context.state.getOptionalValue(AllayBurnerBlock.HAS_RECORD).orElse(false))
            return;

        if (data.adoptedSound == null) {
            Map<BlockPos, SoundInstance> songs =
                ((LevelRendererAccessor) Minecraft.getInstance().levelRenderer)
                    .createmanaindustry$getPlayingJukeboxSongs();
            BlockPos here = BlockPos.containing(context.position);
            SoundInstance vanilla = songs.remove(here);
            if (vanilla != null && Minecraft.getInstance().getSoundManager().isActive(vanilla)) {
                Minecraft.getInstance().getSoundManager().stop(vanilla);
                AdoptedJukeboxSound ours = new AdoptedJukeboxSound(
                    SoundEvent.createVariableRangeEvent(vanilla.getLocation()), context.position);
                Minecraft.getInstance().getSoundManager().play(ours);
                // Register in the vanilla jukebox sound map so the eject's
                // levelEvent 1011 at the burner's position can stop this sound
                // after the contraption is gone — the client gets no stopMoving
                // hook on disassembly, so an unregistered sound would play on.
                songs.put(here, ours);
                data.adoptedSound = ours;
                data.soundMapKey = here;
            }
        } else {
            AdoptedJukeboxSound ours = (AdoptedJukeboxSound) data.adoptedSound;
            if (Minecraft.getInstance().getSoundManager().isActive(ours)) {
                ours.setTargetPosition(context.position);
                // Re-key the map entry so it follows the contraption.
                BlockPos here = BlockPos.containing(context.position);
                if (!here.equals(data.soundMapKey)) {
                    Map<BlockPos, SoundInstance> songs =
                        ((LevelRendererAccessor) Minecraft.getInstance().levelRenderer)
                            .createmanaindustry$getPlayingJukeboxSongs();
                    songs.remove(data.soundMapKey);
                    songs.put(here, ours);
                    data.soundMapKey = here;
                }
            } else {
                // Audio ended naturally: drop the map entry as well.
                if (data.soundMapKey != null) {
                    ((LevelRendererAccessor) Minecraft.getInstance().levelRenderer)
                        .createmanaindustry$getPlayingJukeboxSongs().remove(data.soundMapKey);
                    data.soundMapKey = null;
                }
                data.adoptedSound = null;
            }
        }
    }

    /** Stops the adopted sound and unregisters it from the jukebox sound map. */
    public static void stopSound(ContraptionData data) {
        if (data.adoptedSound == null)
            return;
        AdoptedJukeboxSound ours = (AdoptedJukeboxSound) data.adoptedSound;
        Minecraft.getInstance().getSoundManager().stop(ours);
        if (data.soundMapKey != null) {
            ((LevelRendererAccessor) Minecraft.getInstance().levelRenderer)
                .createmanaindustry$getPlayingJukeboxSongs().remove(data.soundMapKey);
            data.soundMapKey = null;
        }
        data.adoptedSound = null;
    }

    /**
     * Client-side replacement for the vanilla jukebox sound instance once a
     * burner rides a contraption. Mirrors {@code SimpleSoundInstance.forJukeboxSong}
     * (volume 4.0, linear attenuation — that volume is what gives jukeboxes
     * their ~65-block range) but implements {@link TickableSoundInstance} so
     * the sound engine re-reads its position every tick.
     */
    public static class AdoptedJukeboxSound extends AbstractSoundInstance implements TickableSoundInstance {
        private Vec3 targetPosition;

        public AdoptedJukeboxSound(SoundEvent sound, Vec3 pos) {
            super(sound, SoundSource.RECORDS, SoundInstance.createUnseededRandom());
            this.volume = 4.0F;
            this.attenuation = SoundInstance.Attenuation.LINEAR;
            this.x = pos.x;
            this.y = pos.y;
            this.z = pos.z;
        }

        public void setTargetPosition(Vec3 pos) {
            this.targetPosition = pos;
        }

        @Override
        public void tick() {
            if (targetPosition != null) {
                this.x = targetPosition.x;
                this.y = targetPosition.y;
                this.z = targetPosition.z;
            }
        }

        /**
         * The audio is a one-shot; when its channel finishes the engine removes
         * the instance from its ticking list via the channel-stop callback, so
         * the instance never needs to self-terminate.
         */
        @Override
        public boolean isStopped() {
            return false;
        }
    }
}
