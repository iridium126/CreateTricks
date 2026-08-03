package com.iridium126.createmanaindustry.content.burner;

import java.util.Map;

import com.iridium126.createmanaindustry.mixin.LevelRendererAccessor;
import com.simibubi.create.api.behaviour.movement.MovementBehaviour;
import com.simibubi.create.content.contraptions.behaviour.MovementContext;
import com.simibubi.create.content.contraptions.render.ContraptionMatrices;
import com.simibubi.create.foundation.virtualWorld.VirtualRenderWorld;

import net.createmod.catnip.animation.LerpedFloat;
import net.createmod.catnip.animation.LerpedFloat.Chaser;
import net.createmod.catnip.math.AngleHelper;
import net.createmod.catnip.math.VecHelper;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.resources.sounds.AbstractSoundInstance;
import net.minecraft.client.resources.sounds.SoundInstance;
import net.minecraft.client.resources.sounds.TickableSoundInstance;
import net.minecraft.core.BlockPos;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundSource;
import net.minecraft.util.Mth;
import net.minecraft.util.RandomSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;

import net.neoforged.api.distmarker.Dist;
import net.neoforged.api.distmarker.OnlyIn;

/**
 * Animates and renders the Allay Burner on contraptions, mirroring Create's
 * {@code BlazeBurnerMovementBehaviour}. The block state (HEAT_LEVEL) is a
 * static snapshot captured at assembly time, so heat provided to basins above
 * keeps working via {@code BasinBlockEntityHeatMixin} without any dynamic
 * sync. This behaviour only handles the client-side look: smoke particles,
 * player-facing head angle, and the dancing allay with rods/flame.
 */
public class AllayBurnerMovementBehaviour implements MovementBehaviour {

    @Override
    public ItemStack canBeDisabledVia(MovementContext context) {
        return null;
    }

    @Override
    public void tick(MovementContext context) {
        if (!context.world.isClientSide())
            return;
        if (!shouldRender(context))
            return;

        RandomSource r = context.world.getRandom();
        Vec3 c = context.position;
        Vec3 v = c.add(VecHelper.offsetRandomly(Vec3.ZERO, r, .125f)
            .multiply(1, 0, 1));
        if (r.nextInt(3) == 0 && context.motion.length() < 1 / 64f)
            context.world.addParticle(ParticleTypes.LARGE_SMOKE, v.x, v.y, v.z, 0, 0, 0);

        ContraptionData data = getContraptionData(context);
        data.headAngle.chase(
            data.headAngle.getValue() + AngleHelper.getShortestAngleDiff(data.headAngle.getValue(), getTargetAngle(context)),
            .5f, Chaser.exp(5));
        data.headAngle.tickChaser();

        followMusic(context, data);
    }

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
    private void followMusic(MovementContext context, ContraptionData data) {
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
        } else if (Minecraft.getInstance().getSoundManager().isActive(data.adoptedSound)) {
            data.adoptedSound.setTargetPosition(context.position);
            // Re-key the map entry so it follows the contraption.
            BlockPos here = BlockPos.containing(context.position);
            if (!here.equals(data.soundMapKey)) {
                Map<BlockPos, SoundInstance> songs =
                    ((LevelRendererAccessor) Minecraft.getInstance().levelRenderer)
                        .createmanaindustry$getPlayingJukeboxSongs();
                songs.remove(data.soundMapKey);
                songs.put(here, data.adoptedSound);
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

    @Override
    public void stopMoving(MovementContext context) {
        if (context.world.isClientSide && context.temporaryData instanceof ContraptionData data
                && data.adoptedSound != null) {
            Minecraft.getInstance().getSoundManager().stop(data.adoptedSound);
            if (data.soundMapKey != null) {
                ((LevelRendererAccessor) Minecraft.getInstance().levelRenderer)
                    .createmanaindustry$getPlayingJukeboxSongs().remove(data.soundMapKey);
                data.soundMapKey = null;
            }
            data.adoptedSound = null;
        }
    }

    private boolean shouldRender(MovementContext context) {
        return context.state.getOptionalValue(AllayBurnerBlock.HEAT_LEVEL)
            .orElse(AllayBurnerBlock.HeatLevel.NONE) != AllayBurnerBlock.HeatLevel.NONE;
    }

    /** Per-burner state on a contraption; stored in the transient, never-serialized object slot. */
    public static ContraptionData getContraptionData(MovementContext context) {
        if (!(context.temporaryData instanceof ContraptionData)) {
            ContraptionData data = new ContraptionData(context.world);
            data.headAngle.startWithValue(getTargetAngle(context));
            context.temporaryData = data;
        }
        return (ContraptionData) context.temporaryData;
    }

    private static float getTargetAngle(MovementContext context) {
        Entity player = Minecraft.getInstance().cameraEntity;
        if (player != null && !player.isInvisible() && context.position != null) {
            Vec3 applyRotation =
                context.contraption.entity.reverseRotation(player.position().subtract(context.position), 1);
            double dx = applyRotation.x;
            double dz = applyRotation.z;
            return AngleHelper.deg(-Mth.atan2(dz, dx)) - 90;
        }
        return 0;
    }

    @Override
    public boolean disableBlockEntityRendering() {
        return true;
    }

    @Override
    @OnlyIn(Dist.CLIENT)
    public void renderInContraption(MovementContext context, VirtualRenderWorld renderWorld,
            ContraptionMatrices matrices, MultiBufferSource buffer) {
        if (!shouldRender(context))
            return;
        AllayBurnerRenderer.renderInContraption(context, renderWorld, matrices, buffer, getContraptionData(context));
    }

    public static class ContraptionData {
        public final LerpedFloat headAngle;
        public final RenderedAllay allay;

        /** The adopted jukebox sound while this burner rides a contraption. */
        public AdoptedJukeboxSound adoptedSound;

        /** Current key of the adopted sound in LevelRenderer.playingJukeboxSongs (client). */
        public BlockPos soundMapKey;

        ContraptionData(Level level) {
            headAngle = LerpedFloat.angular();
            allay = new RenderedAllay(level);
        }
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
