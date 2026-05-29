package com.iridium126.createtricks.content.kinetics;

import java.util.Iterator;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import com.simibubi.create.content.kinetics.base.KineticBlockEntity;
import com.simibubi.create.foundation.blockEntity.SyncedBlockEntity;

import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;

public final class TemporaryStress {
	public static final String NBT_KEY = "CreateTricksTemporaryStress";

	private static final Map<BlockPos, StressState> SERVER_STATES = new ConcurrentHashMap<>();
	private static final Map<BlockPos, StressState> CLIENT_STATES = new ConcurrentHashMap<>();

	private TemporaryStress() {}

	public static void apply(KineticBlockEntity be, float stress, int durationTicks) {
		if (be.getLevel() == null || be.getLevel().isClientSide || durationTicks <= 0)
			return;

		StressState state = new StressState(stress, durationTicks);
		SERVER_STATES.put(be.getBlockPos().immutable(), state);
		refreshStress(be);
		sync(be);
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
				refreshStress(kinetic);
				sync(kinetic);
			}
		}
	}

	public static float getStress(KineticBlockEntity be) {
		StressState state = getState(be);
		return state == null ? 0 : state.stress;
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
		stressTag.putInt("Ticks", state.ticksRemaining);
		tag.put(NBT_KEY, stressTag);
	}

	public static void readClient(KineticBlockEntity be, CompoundTag tag) {
		if (tag.contains(NBT_KEY)) {
			CompoundTag stressTag = tag.getCompound(NBT_KEY);
			CLIENT_STATES.put(be.getBlockPos().immutable(),
					new StressState(stressTag.getFloat("Stress"), stressTag.getInt("Ticks")));
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

	private static void refreshStress(KineticBlockEntity be) {
		if (be.hasNetwork())
			be.getOrCreateNetwork().updateStressFor(be,
					be instanceof TemporaryStressProvider provider
							? provider.createtricks$calculateStressApplied()
							: be.calculateStressApplied());
	}

	private static void sync(KineticBlockEntity be) {
		be.setChanged();
		if (be instanceof SyncedBlockEntity synced)
			synced.sendData();
	}

	private static final class StressState {
		private final float stress;
		private int ticksRemaining;

		private StressState(float stress, int ticksRemaining) {
			this.stress = stress;
			this.ticksRemaining = ticksRemaining;
		}
	}
}
