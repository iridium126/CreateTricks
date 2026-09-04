package com.iridium126.createmanaindustry.client.dimension.render;

import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.LinkedBlockingQueue;

import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.state.BlockState;

import com.iridium126.createmanaindustry.client.dimension.AllvrClientCubeCache;
import com.iridium126.createmanaindustry.dimension.cube.AllvrCubePos;

/**
 * Single daemon worker that turns streamed cubes into greedy-meshed quad
 * streams (doc §8.2 M0). Jobs are cube keys; the snapshot (34³ states +
 * occluder flags) is built under the cube cache's lock so worker reads never
 * race main-thread applies/writes, then meshed outside the lock. Results are
 * drained by the render thread in {@code AllvrRenderer}.
 * <p>
 * V0 keeps one worker: a burst of 24 streamed cubes/tick meshes at a few ms
 * per cube, so results lag the stream slightly during load spikes — load
 * order pop-in, not data loss (a re-submitted job is deduped upstream).
 */
public final class AllvrMesherWorker {

    /** One meshed cube ready to upload. */
    public record MeshResult(long key, long[] quads) {}

    private static final LinkedBlockingQueue<Long> JOBS = new LinkedBlockingQueue<>();
    private static final ConcurrentLinkedQueue<MeshResult> RESULTS = new ConcurrentLinkedQueue<>();
    private static volatile boolean running;
    private static Thread thread;

    public static void start() {
        if (thread != null) {
            return;
        }
        running = true;
        thread = new Thread(AllvrMesherWorker::run, "CMI-AllvrMesher");
        thread.setDaemon(true);
        thread.start();
    }

    public static void stop() {
        running = false;
        JOBS.clear();
        RESULTS.clear();
        thread = null;
    }

    public static void submit(long key) {
        JOBS.add(key);
    }

    /** Thread handle for lazy startup checks (null before the first start). */
    public static Thread threadOrNull() {
        return thread;
    }

    /** Drops queued jobs/results (level switch); the worker thread stays up. */
    public static void clearQueues() {
        JOBS.clear();
        RESULTS.clear();
    }

    public static MeshResult poll() {
        return RESULTS.poll();
    }

    private static void run() {
        BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
        while (running) {
            Long key = null;
            try {
                key = JOBS.poll(50, java.util.concurrent.TimeUnit.MILLISECONDS);
                if (key != null) {
                    process(key, pos);
                }
            } catch (InterruptedException ignored) {
                return;
            } catch (Throwable t) {
                // keep the worker alive: a dead thread silently ends ALL remeshing
                // (the renderer's lazy-start check only sees a non-null handle);
                // failed jobs are dropped, not retried — deterministic failures
                // would otherwise loop forever
                com.iridium126.createmanaindustry.CreateManaIndustry.LOGGER
                    .error("[Allvr] mesher failed on cube {}, job dropped", key, t);
            }
        }
    }

    private static void process(long key, BlockPos.MutableBlockPos pos) {
        AllvrCubePos cpos = AllvrCubePos.fromLong(key);
        int bx = cpos.minBlockX();
        int by = cpos.minBlockY();
        int bz = cpos.minBlockZ();
        BlockState[] states = new BlockState[AllvrMesher.PADDED * AllvrMesher.PADDED * AllvrMesher.PADDED];
        byte[] occludes = new byte[states.length];

        // Snapshot under the cache lock: worker reads must not race main-thread
        // apply/forget/setBlock (the lock is also held by those writers).
        synchronized (AllvrClientCubeCache.LOCK) {
            int i = 0;
            for (int y = -1; y <= AllvrMesher.CUBE; y++) {
                for (int z = -1; z <= AllvrMesher.CUBE; z++) {
                    for (int x = -1; x <= AllvrMesher.CUBE; x++) {
                        BlockState state = AllvrClientCubeCache.getBlockState(
                            pos.set(bx + x, by + y, bz + z));
                        states[i] = state;
                        occludes[i] = AllvrMesher.occludesAt(state);
                        i++;
                    }
                }
            }
        }

        RESULTS.add(new MeshResult(key, AllvrMesher.build(states, occludes)));
    }

    private AllvrMesherWorker() {}
}
