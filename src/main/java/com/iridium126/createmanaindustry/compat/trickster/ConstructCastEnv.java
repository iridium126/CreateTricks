package com.iridium126.createmanaindustry.compat.trickster;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Predicate;

import at.petrak.hexcasting.api.casting.ParticleSpray;
import at.petrak.hexcasting.api.casting.eval.CastResult;
import at.petrak.hexcasting.api.casting.eval.CastingEnvironment;
import at.petrak.hexcasting.api.casting.eval.MishapEnvironment;
import at.petrak.hexcasting.api.casting.eval.env.PlayerBasedCastEnv;
import at.petrak.hexcasting.api.pigment.FrozenPigment;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.Nullable;

/**
 * Hex casting environment for spells executed from a Trickster spell construct
 * block via {@code eval_iota}. Media is drawn from
 * {@link ConstructMediaStorage}; the ambit matches the player default
 * (32 blocks) centered on the construct.
 */
public final class ConstructCastEnv extends CastingEnvironment {

    /** Same as {@link PlayerBasedCastEnv#DEFAULT_AMBIT_RADIUS}. */
    public static final double AMBIT_RADIUS = PlayerBasedCastEnv.DEFAULT_AMBIT_RADIUS;

    private final BlockEntity construct;
    private final Vec3 castCenter;

    public ConstructCastEnv(ServerLevel world, BlockEntity construct) {
        super(world);
        this.construct = construct;
        this.castCenter = Vec3.atCenterOf(construct.getBlockPos());
    }

    public BlockEntity construct() {
        return construct;
    }

    public Vec3 castCenter() {
        return castCenter;
    }

    @Override
    public @Nullable LivingEntity getCastingEntity() {
        return null;
    }

    @Override
    public MishapEnvironment getMishapEnvironment() {
        return new ConstructMishapEnv(this.world);
    }

    @Override
    public void postExecution(CastResult result) {
        super.postExecution(result);

        var sound = result.getSound().sound();
        if (sound != null) {
            this.world.playSound(null, castCenter.x, castCenter.y, castCenter.z, sound, SoundSource.BLOCKS, 1f, 1f);
        }
    }

    @Override
    public Vec3 mishapSprayPos() {
        return castCenter;
    }

    @Override
    public long extractMediaEnvironment(long cost, boolean simulate) {
        long mediaAvailable = ConstructMediaStorage.getMedia(construct);
        if (mediaAvailable < 0) {
            return 0;
        }
        if (mediaAvailable == 0) {
            return cost;
        }

        long mediaToTake = Math.min(cost, mediaAvailable);
        cost -= mediaToTake;
        if (!simulate) {
            ConstructMediaStorage.setStoredMedia(construct, mediaAvailable - mediaToTake);
            ConstructMediaStorage.syncMedia(construct);
        }
        return cost;
    }

    @Override
    public boolean isVecInRangeEnvironment(Vec3 vec) {
        return vec.distanceToSqr(castCenter) <= AMBIT_RADIUS * AMBIT_RADIUS + 0.00000000001;
    }

    @Override
    public boolean isEnlightened() {
        return true;
    }

    @Override
    public boolean hasEditPermissionsAtEnvironment(BlockPos pos) {
        return true;
    }

    @Override
    public InteractionHand getCastingHand() {
        return InteractionHand.MAIN_HAND;
    }

    @Override
    public List<ItemStack> getUsableStacks(StackDiscoveryMode mode) {
        return new ArrayList<>();
    }

    @Override
    public List<HeldItemInfo> getPrimaryStacks() {
        return List.of();
    }

    @Override
    public boolean replaceItem(Predicate<ItemStack> stackOk, ItemStack replaceWith, @Nullable InteractionHand hand) {
        return false;
    }

    @Override
    public FrozenPigment getPigment() {
        return FrozenPigment.DEFAULT.get();
    }

    @Override
    public @Nullable FrozenPigment setPigment(@Nullable FrozenPigment pigment) {
        return null;
    }

    @Override
    public void produceParticles(ParticleSpray particles, FrozenPigment pigment) {
        particles.sprayParticles(this.world, pigment);
    }

    @Override
    public void printMessage(Component message) {
    }
}
