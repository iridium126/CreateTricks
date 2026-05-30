package com.iridium126.createtricks.content.kinetics;

import java.util.Iterator;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import com.simibubi.create.content.kinetics.KineticNetwork;
import com.simibubi.create.content.kinetics.base.KineticBlockEntity;
import com.simibubi.create.foundation.blockEntity.SyncedBlockEntity;

import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.Mth;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;

public final class TemporaryStress {
	public static final String NBT_KEY = "CreateTricksTemporaryStress";

	private static final Map<BlockPos, StressState> SERVER_STATES = new ConcurrentHashMap<>();
	private static final Map<BlockPos, StressState> CLIENT_STATES = new ConcurrentHashMap<>();

	private TemporaryStress() {}

	public static void apply(KineticBlockEntity be, float stress, float speed, int durationTicks) {
		if (be.getLevel() == null || be.getLevel().isClientSide || durationTicks <= 0)
			return;

		StressState state = new StressState(stress, speed, durationTicks);
		SERVER_STATES.put(be.getBlockPos().immutable(), state);
		updateGeneratedRotation(be);
	}

	public static void tick(ServerLevel level) {
		Iterator<Map.Entry<BlockPos, StressState>> iterator = SERVER_STATES.entrySet().iterator();
		while (iterator.hasNext()) {
			Map.Entry<BlockPos, StressState> entry = iterator.next();
			StressState state = entry.getValue();
			state.ticksRemaining--;
			if (state.ticksRemaining > 0)
				continue;

			iterator.remove();
			BlockEntity be = level.getBlockEntity(entry.getKey());
			if (be instanceof KineticBlockEntity kinetic) {
				Set<KineticBlockEntity> affected = collectNetworkMembers(kinetic);
				updateGeneratedRotation(kinetic);
				syncAll(affected);
			}
		}
	}

	public static float getStress(KineticBlockEntity be) {
		StressState state = getState(be);
		if (state == null)
			return 0;
		float speed = Math.abs(getGeneratedSpeed(be));
		return speed == 0 ? 0 : state.stress / speed;
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
		StressState state = SERVER_STATES.get(be.getBlockPos());
		if (state == null || state.ticksRemaining <= 0)
			return;

		CompoundTag stressTag = new CompoundTag();
		stressTag.putFloat("Stress", state.stress);
		stressTag.putFloat("Speed", state.speed);
		stressTag.putInt("Ticks", state.ticksRemaining);
		tag.put(NBT_KEY, stressTag);
	}

	public static void readClient(KineticBlockEntity be, CompoundTag tag) {
		if (tag.contains(NBT_KEY)) {
			CompoundTag stressTag = tag.getCompound(NBT_KEY);
			CLIENT_STATES.put(be.getBlockPos().immutable(),
					new StressState(stressTag.getFloat("Stress"), stressTag.getFloat("Speed"), stressTag.getInt("Ticks")));
			return;
		}

		CLIENT_STATES.remove(be.getBlockPos());
	}

	private static StressState getState(KineticBlockEntity be) {
		Level level = be.getLevel();
		if (level == null)
			return null;
		return (level.isClientSide ? CLIENT_STATES : SERVER_STATES).get(be.getBlockPos());
	}

	private static void updateGeneratedRotation(KineticBlockEntity be) {
		Level level = be.getLevel();
		if (level == null || level.isClientSide)
			return;

		StressState state = SERVER_STATES.get(be.getBlockPos());
		float speed = state == null ? 0 : getGeneratedSpeed(be);
		float prevSpeed = be.getTheoreticalSpeed();
		if (!Mth.equal(prevSpeed, speed))
			applyNewSpeed(be, prevSpeed, speed);

		if (be.hasNetwork() && speed != 0) {
			KineticNetwork network = be.getOrCreateNetwork();
			network.updateCapacityFor(be, be.calculateAddedStressCapacity());
			network.updateStressFor(be, be.calculateStressApplied());
			network.updateStress();
		}

		be.onSpeedChanged(prevSpeed);
		sync(be);
	}

	private static void applyNewSpeed(KineticBlockEntity be, float prevSpeed, float speed) {
		if (speed == 0) {
			if (be.hasSource()) {
				be.getOrCreateNetwork().updateCapacityFor(be, 0);
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
			be.setSpeed(speed);
			be.source = null;
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

	private static float getGeneratedSpeed(KineticBlockEntity be) {
		return be.getGeneratedSpeed();
	}

	private static void sync(KineticBlockEntity be) {
		be.setChanged();
		syncAll(collectNetworkMembers(be));
		syncBlock(be);
	}

	private static Set<KineticBlockEntity> collectNetworkMembers(KineticBlockEntity be) {
		if (!be.hasNetwork())
			return Set.of(be);
		return new HashSet<>(be.getOrCreateNetwork().members.keySet());
	}

	private static void syncAll(Set<KineticBlockEntity> members) {
		for (KineticBlockEntity member : members)
			syncBlock(member);
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

	private static final class StressState {
		private final float stress;
		private final float speed;
		private int ticksRemaining;

		private StressState(float stress, float speed, int ticksRemaining) {
			this.stress = stress;
			this.speed = speed;
			this.ticksRemaining = ticksRemaining;
		}
	}
}
