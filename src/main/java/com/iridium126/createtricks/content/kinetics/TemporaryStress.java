package com.iridium126.createtricks.content.kinetics;

import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;

import com.iridium126.createtricks.mixin.KineticBlockEntityAccessor;
import com.simibubi.create.content.kinetics.KineticNetwork;
import com.simibubi.create.content.kinetics.base.KineticBlockEntity;
import com.simibubi.create.content.kinetics.base.IRotate.SpeedLevel;
import com.simibubi.create.content.kinetics.base.IRotate.StressImpact;
import com.simibubi.create.foundation.blockEntity.SyncedBlockEntity;
import com.simibubi.create.foundation.utility.CreateLang;

import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.Mth;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;

public final class TemporaryStress {
	public static final String NBT_KEY = "CreateTricksTemporaryStress";

	private static final Map<StressKey, StressState> SERVER_STATES = new ConcurrentHashMap<>();
	private static final Map<StressKey, StressState> CLIENT_STATES = new ConcurrentHashMap<>();

	private TemporaryStress() {}

	public static void apply(KineticBlockEntity be, float stress, float speed, int durationTicks) {
		Level level = be.getLevel();
		if (level == null || level.isClientSide || durationTicks <= 0)
			return;

		StressState state = new StressState(stress, speed, durationTicks);
		SERVER_STATES.put(StressKey.of(level, be.getBlockPos()), state);
		updateGeneratedRotation(be);
	}

	public static void tick(ServerLevel level) {
		Iterator<Map.Entry<StressKey, StressState>> iterator = SERVER_STATES.entrySet().iterator();
		while (iterator.hasNext()) {
			Map.Entry<StressKey, StressState> entry = iterator.next();
			if (!entry.getKey().is(level))
				continue;

			StressState state = entry.getValue();
			state.ticksRemaining--;
			if (state.ticksRemaining > 0)
				continue;

			iterator.remove();
			BlockEntity be = level.getBlockEntity(entry.getKey().pos);
			if (be instanceof KineticBlockEntity kinetic) {
				updateGeneratedRotation(kinetic);
				syncBlock(kinetic);
			}
		}
	}

	public static float getStress(KineticBlockEntity be) {
		StressState state = getState(be);
		if (state == null)
			return 0;
		return state.stressCapacity();
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
		StressState state = getServerState(be);
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
		if (level == null)
			return;
		StressKey key = StressKey.of(level, be.getBlockPos());
		if (tag.contains(NBT_KEY)) {
			CompoundTag stressTag = tag.getCompound(NBT_KEY);
			CLIENT_STATES.put(key,
					new StressState(stressTag.getFloat("Stress"), stressTag.getFloat("Speed"), stressTag.getInt("Ticks")));
			return;
		}

		CLIENT_STATES.remove(key);
	}

	public static boolean isSource(KineticBlockEntity be) {
		return getSpeed(be) != 0;
	}

	public static void removeSource(KineticBlockEntity be) {
		Level level = be.getLevel();
		if (level == null || level.isClientSide)
			return;
		StressState state = getServerState(be);
		if (state == null || !be.hasSource())
			return;
		state.reActivateSource = true;
	}

	public static void setSource(KineticBlockEntity be, BlockEntity source) {
		StressState state = getServerState(be);
		if (state == null || !(source instanceof KineticBlockEntity sourceBE))
			return;
		if (state.reActivateSource && Math.abs(sourceBE.getSpeed()) >= Math.abs(state.speed))
			state.reActivateSource = false;
	}

	public static void tickBlockEntity(KineticBlockEntity be) {
		Level level = be.getLevel();
		if (level == null || level.isClientSide)
			return;
		StressState state = getServerState(be);
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

	private static StressState getState(KineticBlockEntity be) {
		Level level = be.getLevel();
		if (level == null)
			return null;
		return (level.isClientSide ? CLIENT_STATES : SERVER_STATES).get(StressKey.of(level, be.getBlockPos()));
	}

	private static StressState getServerState(KineticBlockEntity be) {
		Level level = be.getLevel();
		if (level == null)
			return null;
		return SERVER_STATES.get(StressKey.of(level, be.getBlockPos()));
	}

	private static void updateGeneratedRotation(KineticBlockEntity be) {
		Level level = be.getLevel();
		if (level == null || level.isClientSide)
			return;

		StressState state = getServerState(be);
		float speed = state == null ? 0 : state.speed;
		float prevSpeed = be.getTheoreticalSpeed();
		KineticNetwork previousNetwork = be.hasNetwork() ? be.getOrCreateNetwork() : null;
		if (!Mth.equal(prevSpeed, speed)) {
			if (!be.hasSource() && SpeedLevel.of(prevSpeed) != SpeedLevel.of(speed))
				((KineticBlockEntityAccessor) be).createtricks$getEffects()
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

	private static void syncBlock(KineticBlockEntity be) {
		be.setChanged();
		if (be instanceof SyncedBlockEntity synced)
			synced.sendData();
		Level level = be.getLevel();
		if (level != null && !level.isClientSide) {
			BlockState state = be.getBlockState();
			level.sendBlockUpdated(be.getBlockPos(), state, state, 2);
		}
	}

	private record StressKey(ResourceKey<Level> dimension, BlockPos pos) {
		private StressKey {
			pos = pos.immutable();
		}

		private static StressKey of(Level level, BlockPos pos) {
			return new StressKey(level.dimension(), pos);
		}

		private boolean is(Level level) {
			return Objects.equals(dimension, level.dimension());
		}
	}

	private static final class StressState {
		private final float stress;
		private final float speed;
		private int ticksRemaining;
		private boolean reActivateSource;

		private StressState(float stress, float speed, int ticksRemaining) {
			this.stress = stress;
			this.speed = speed;
			this.ticksRemaining = ticksRemaining;
		}

		private float stressCapacity() {
			float absSpeed = Math.abs(speed);
			return absSpeed == 0 ? 0 : Math.abs(stress) / absSpeed;
		}
	}
}
