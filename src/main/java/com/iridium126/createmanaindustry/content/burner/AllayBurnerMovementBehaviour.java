package com.iridium126.createmanaindustry.content.burner;

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
import net.minecraft.core.BlockPos;
import net.minecraft.core.particles.ParticleTypes;
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
 * keeps working via {@code BasinBlockEntityMixin} without any dynamic
 * sync. This behaviour only handles the client-side look: smoke particles,
 * player-facing head angle, the dancing allay with rods/flame, and the
 * adopted jukebox sound (see {@link AllayBurnerClientSounds}).
 * <p>
 * This class is loaded on dedicated servers, so every reference to a
 * {@code net.minecraft.client.*} class must live inside an
 * {@code @OnlyIn(Dist.CLIENT)} method — the dist cleaner strips those (and
 * NOPs the call sites) on the server. The interface methods themselves stay
 * unannotated.
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
        clientTick(context);
    }

    @OnlyIn(Dist.CLIENT)
    private static void clientTick(MovementContext context) {
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

        AllayBurnerClientSounds.followMusic(context, data);
    }

    @Override
    public void stopMoving(MovementContext context) {
        if (context.world.isClientSide && context.temporaryData instanceof ContraptionData data)
            clientStopSound(data);
    }

    @OnlyIn(Dist.CLIENT)
    private static void clientStopSound(ContraptionData data) {
        AllayBurnerClientSounds.stopSound(data);
    }

    private static boolean shouldRender(MovementContext context) {
        return context.state.getOptionalValue(AllayBurnerBlock.HEAT_LEVEL)
            .orElse(AllayBurnerBlock.HeatLevel.NONE) != AllayBurnerBlock.HeatLevel.NONE;
    }

    /** Per-burner state on a contraption; stored in the transient, never-serialized object slot. */
    public static ContraptionData getContraptionData(MovementContext context) {
        if (!(context.temporaryData instanceof ContraptionData)) {
            ContraptionData data = new ContraptionData(context.world);
            initHeadAngle(context, data);
            context.temporaryData = data;
        }
        return (ContraptionData) context.temporaryData;
    }

    @OnlyIn(Dist.CLIENT)
    private static void initHeadAngle(MovementContext context, ContraptionData data) {
        data.headAngle.startWithValue(getTargetAngle(context));
    }

    @OnlyIn(Dist.CLIENT)
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

        /**
         * The adopted jukebox sound while this burner rides a contraption.
         * Client-only: held as {@code Object} so this class keeps no reference
         * to client sound classes on dedicated servers; cast to
         * {@link AllayBurnerClientSounds.AdoptedJukeboxSound} where used.
         */
        public Object adoptedSound;

        /** Current key of the adopted sound in LevelRenderer.playingJukeboxSongs (client). */
        public BlockPos soundMapKey;

        ContraptionData(Level level) {
            headAngle = LerpedFloat.angular();
            allay = new RenderedAllay(level);
        }
    }
}
