package com.iridium126.createmanaindustry.content.kinetics.temporarykinetics;

import java.util.List;

import com.iridium126.createmanaindustry.CMIAttachments;
import com.iridium126.createmanaindustry.content.kinetics.temporarykinetics.TemporaryKineticsStore.StressState;
import com.iridium126.createmanaindustry.mixin.kinetics.KineticBlockEntityAccessor;
import com.simibubi.create.content.kinetics.KineticNetwork;
import com.simibubi.create.content.kinetics.base.KineticBlockEntity;
import com.simibubi.create.content.kinetics.base.IRotate.SpeedLevel;
import com.simibubi.create.content.kinetics.base.IRotate.StressImpact;
import com.simibubi.create.foundation.blockEntity.SyncedBlockEntity;
import com.simibubi.create.foundation.utility.CreateLang;

import dev.engine_room.flywheel.api.visualization.VisualManager;
import dev.engine_room.flywheel.api.visualization.VisualizationManager;
import net.minecraft.ChatFormatting;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.Mth;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;

/**
 * Temporary kinetics: externally applied speed / stress capacity on any
 * {@link KineticBlockEntity} for a bounded number of ticks — the attachment-backed
 * successor of Create's own {@code GeneratingKineticBlockEntity} pattern,
 * generalised to arbitrary kinetic blocks.
 * <p>
 * State lives in a per-level {@link TemporaryKineticsStore} data attachment
 * ({@code CMIAttachments#TEMPORARY_KINETICS}) rather than static maps: it is
 * scoped to the level instance server- and client-side alike, persists across
 * saves through the store serializer, and can never leak between worlds.
 * <p>
 * Client sync rides Create's existing BE payload ({@code write}/{@code read}
 * with {@code clientPacket=true}); the {@link #NBT_KEY} tag is transient by
 * design and never reaches disk.
 */
public final class TemporaryKinetics {
    public static final String NBT_KEY = "CMITemporaryKinetics";

    private TemporaryKinetics() {}

    public static void apply(KineticBlockEntity be, float stress, float speed, int durationTicks) {
        Level level = be.getLevel();
        if (!(level instanceof ServerLevel serverLevel) || durationTicks <= 0)
            return;

        StressState state = new StressState(stress, speed, durationTicks);
        store(serverLevel).put(be.getBlockPos(), state);
        updateGeneratedRotation(be);
    }

    public static void tick(ServerLevel level) {
        store(level).tick(level);
    }

    public static float getStress(KineticBlockEntity be) {
        StressState state = getState(be);
        return state == null ? 0 : state.stressCapacity();
    }

    public static float getSpeed(KineticBlockEntity be) {
        StressState state = getState(be);
        return state == null ? 0 : state.speed;
    }

    public static boolean isActive(BlockEntity be) {
        if (!(be instanceof KineticBlockEntity kinetic))
            return false;
        StressState state = getState(kinetic);
        return state != null && state.ticksRemaining > 0;
    }

    public static void writeClient(KineticBlockEntity be, CompoundTag tag) {
        Level level = be.getLevel();
        if (level == null || level.isClientSide)
            return;
        StressState state = getState(be);
        if (state == null || state.ticksRemaining <= 0)
            return;

        CompoundTag stressTag = new CompoundTag();
        stressTag.putFloat("Stress", state.stress);
        stressTag.putFloat("Speed", state.speed);
        stressTag.putInt("Ticks", state.ticksRemaining);
        tag.put(NBT_KEY, stressTag);
    }

    public static void readClient(KineticBlockEntity be, CompoundTag tag) {
        Level level = be.getLevel();
        if (level == null || !level.isClientSide)
            return;
        boolean wasActive = isActive(be);
        if (tag.contains(NBT_KEY)) {
            CompoundTag stressTag = tag.getCompound(NBT_KEY);
            store(level).put(be.getBlockPos(),
                    new StressState(stressTag.getFloat("Stress"), stressTag.getFloat("Speed"), stressTag.getInt("Ticks")));
            if (!wasActive)
                rebuildVisual(be);
            return;
        }

        store(level).remove(be.getBlockPos());
        if (wasActive)
            rebuildVisual(be);
    }

    /**
     * Drops the client-mirrored state for a block entity being removed. The
     * server keeps its own entry until expiry (a harmless countdown against a
     * dead position), but the client mirror has no countdown of its own and
     * would otherwise linger until the position happens to be re-synced or the
     * dimension changes.
     */
    public static void clearClient(KineticBlockEntity be) {
        Level level = be.getLevel();
        if (level == null || !level.isClientSide)
            return;
        store(level).remove(be.getBlockPos());
    }

    private static void rebuildVisual(KineticBlockEntity be) {
        Level level = be.getLevel();
        if (level == null)
            return;
        VisualizationManager manager = VisualizationManager.get(level);
        if (manager == null)
            return;
        VisualManager<BlockEntity> visuals = manager.blockEntities();
        visuals.queueRemove(be);
        visuals.queueAdd(be);
    }

    public static void removeSource(KineticBlockEntity be) {
        Level level = be.getLevel();
        if (level == null || level.isClientSide)
            return;
        StressState state = getState(be);
        if (state == null || !be.hasSource())
            return;
        state.reActivateSource = true;
    }

    public static void setSource(KineticBlockEntity be, BlockEntity source) {
        Level level = be.getLevel();
        if (level == null || level.isClientSide)
            return;
        StressState state = getState(be);
        if (state == null || !(source instanceof KineticBlockEntity sourceBE))
            return;
        if (state.reActivateSource && Math.abs(sourceBE.getSpeed()) >= Math.abs(state.speed))
            state.reActivateSource = false;
    }

    public static void tickBlockEntity(KineticBlockEntity be) {
        Level level = be.getLevel();
        if (level == null || level.isClientSide)
            return;
        StressState state = getState(be);
        if (state == null || !state.reActivateSource)
            return;
        updateGeneratedRotation(be);
        state.reActivateSource = false;
    }

    public static boolean addToGoggleTooltip(KineticBlockEntity be, List<Component> tooltip) {
        if (!isActive(be) || !StressImpact.isEnabled())
            return false;

        float stressBase = be.calculateAddedStressCapacity();
        if (Mth.equal(stressBase, 0))
            return false;

        CreateLang.translate("gui.goggles.generator_stats")
            .forGoggles(tooltip);
        CreateLang.translate("tooltip.capacityProvided")
            .style(ChatFormatting.GRAY)
            .forGoggles(tooltip);

        float speed = be.getTheoreticalSpeed();
        float generatedSpeed = getSpeed(be);
        if (speed != generatedSpeed && speed != 0)
            stressBase *= generatedSpeed / speed;

        float stressTotal = Math.abs(stressBase * speed);
        CreateLang.number(stressTotal)
            .translate("generic.unit.stress")
            .style(ChatFormatting.AQUA)
            .space()
            .add(CreateLang.translate("gui.goggles.at_current_speed")
                .style(ChatFormatting.DARK_GRAY))
            .forGoggles(tooltip, 1);

        return true;
    }

    // ---- internals -----------------------------------------------------------

    /**
     * Side-correct store access: a {@link ServerLevel} resolves to the server's
     * live data, a client level to its mirrored copy — both through the same
     * attachment type, mirroring the mist-field pattern.
     */
    private static TemporaryKineticsStore store(Level level) {
        return level.getData(CMIAttachments.TEMPORARY_KINETICS.get());
    }

    private static StressState getState(KineticBlockEntity be) {
        Level level = be.getLevel();
        return level == null ? null : store(level).get(be.getBlockPos());
    }

    static void updateGeneratedRotation(KineticBlockEntity be) {
        Level level = be.getLevel();
        if (level == null || level.isClientSide)
            return;

        StressState state = getState(be);
        // No active override left (the expiry path): fall back to whatever the
        // block still generates natively instead of forcing a stop. Forcing
        // speed 0 on a native generator (water wheel, steam engine...) detached
        // it while its own generation survived — and since an idle root never
        // re-runs propagation, it stayed dormant until a chunk reload. Reading
        // the now-unmasked generated speed lets applyNewSpeed re-establish it
        // as a source; plain machinery still resolves to 0 here and stops as
        // before.
        float speed = state == null ? be.getGeneratedSpeed() : state.speed;
        float prevSpeed = be.getTheoreticalSpeed();
        KineticNetwork previousNetwork = be.hasNetwork() ? be.getOrCreateNetwork() : null;
        if (!Mth.equal(prevSpeed, speed)) {
            if (!be.hasSource() && SpeedLevel.of(prevSpeed) != SpeedLevel.of(speed))
                ((KineticBlockEntityAccessor) be).createmanaindustry$getEffects()
                    .queueRotationIndicators();
            applyNewSpeed(be, state, prevSpeed, speed);
        }

        if (be.hasNetwork() && speed != 0) {
            KineticNetwork network = be.getOrCreateNetwork();
            notifyStressCapacityChange(be, be.calculateAddedStressCapacity());
            network.updateStressFor(be, be.calculateStressApplied());
            network.updateStress();
        }

        be.onSpeedChanged(prevSpeed);
        sync(be, previousNetwork);
    }

    private static void applyNewSpeed(KineticBlockEntity be, StressState state, float prevSpeed, float speed) {
        if (speed == 0) {
            if (be.hasSource()) {
                notifyStressCapacityChange(be, 0);
                be.getOrCreateNetwork().updateStressFor(be, be.calculateStressApplied());
                return;
            }
            be.detachKinetics();
            be.setSpeed(0);
            be.setNetwork(null);
            return;
        }

        if (prevSpeed == 0) {
            be.setSpeed(speed);
            be.setNetwork(createNetworkId(be));
            be.attachKinetics();
            return;
        }

        if (be.hasSource()) {
            if (Math.abs(prevSpeed) >= Math.abs(speed)) {
                if (Math.signum(prevSpeed) != Math.signum(speed) && be.getLevel() != null)
                    be.getLevel().destroyBlock(be.getBlockPos(), true);
                return;
            }

            be.detachKinetics();
            be.removeSource();
            if (state != null)
                state.reActivateSource = false;
            be.setSpeed(speed);
            be.setNetwork(createNetworkId(be));
            be.attachKinetics();
            return;
        }

        be.detachKinetics();
        be.setSpeed(speed);
        be.attachKinetics();
    }

    private static Long createNetworkId(KineticBlockEntity be) {
        return be.getBlockPos().asLong();
    }

    private static void notifyStressCapacityChange(KineticBlockEntity be, float capacity) {
        be.getOrCreateNetwork().updateCapacityFor(be, capacity);
    }

    private static void sync(KineticBlockEntity be, KineticNetwork previousNetwork) {
        be.setChanged();
        if (previousNetwork != null)
            previousNetwork.sync();
        if (be.hasNetwork())
            be.getOrCreateNetwork()
                .sync();
        syncBlock(be);
    }

    static void syncBlock(KineticBlockEntity be) {
        be.setChanged();
        if (be instanceof SyncedBlockEntity synced)
            synced.sendData();
        Level level = be.getLevel();
        if (level != null && !level.isClientSide) {
            BlockState state = be.getBlockState();
            level.sendBlockUpdated(be.getBlockPos(), state, state, 2);
        }
    }
}
