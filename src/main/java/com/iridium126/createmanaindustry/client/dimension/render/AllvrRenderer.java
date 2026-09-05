package com.iridium126.createmanaindustry.client.dimension.render;

import java.util.Iterator;

import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import net.minecraft.client.Minecraft;
import net.minecraft.client.Camera;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.culling.Frustum;
import net.minecraft.client.renderer.texture.TextureAtlas;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

import org.joml.Matrix4f;
import org.joml.Matrix4fc;
import org.joml.Vector4f;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL13;
import org.lwjgl.opengl.GL15;
import org.lwjgl.opengl.GL20;
import org.lwjgl.opengl.GL30;
import org.lwjgl.opengl.GL42;
import org.lwjgl.opengl.GL43;
import org.lwjgl.opengl.GL46;

import com.iridium126.createmanaindustry.CreateManaIndustry;
import com.iridium126.createmanaindustry.client.dimension.AllvrClientCubeCache;
import com.iridium126.createmanaindustry.client.dimension.iris.AllvrIrisDataHolder;
import com.iridium126.createmanaindustry.client.dimension.iris.AllvrIrisFrameTarget;
import com.iridium126.createmanaindustry.client.dimension.iris.AllvrIrisPipelineData;
import com.iridium126.createmanaindustry.client.dimension.iris.AllvrVoxyUniforms;
import com.iridium126.createmanaindustry.config.ClientConfig;
import com.iridium126.createmanaindustry.dimension.AllvrDimensions;
import com.iridium126.createmanaindustry.dimension.cube.AllvrCubePos;
import com.mojang.blaze3d.systems.RenderSystem;

import net.neoforged.neoforge.client.event.RenderLevelStageEvent;

/**
 * ALLVR V0 terrain renderer (doc §13 phase 3): Tier B MDI forward pass —
 * CPU greedy mesher, per-cube MDI commands, CPU frustum culling, no occlusion
 * culling / LOD / deferred shading (those are phases 4–5).
 * <p>
 * Frame slot by mode (grilling decision ⑧ fallback chain): no pack →
 * AFTER_SKY (the vanilla-terrain window this pass replaces); pack in use
 * without a resolvable voxy patch (or with the patched program unavailable) →
 * AFTER_LEVEL, self-lit after the composite blit (documented V0 coexistence
 * trade-off); pack-lit via the voxy patch (iris integration G2) →
 * AFTER_SOLID_BLOCKS — inside the gbuffer phase, so the draw lands in the
 * pack's colortex targets before its deferred passes sample them
 * ({@link AllvrIrisFrameTarget} carries the pack-lit framebuffer).
 * <p>
 * Lifecycle: cube apply/forget/setBlock arrive from {@link AllvrClientCubeCache}
 * on the main thread; mesh jobs run on the mesher worker; results are drained
 * and uploaded on the render thread inside the stage handler.
 */
public final class AllvrRenderer {

    public static final AllvrRenderer INSTANCE = new AllvrRenderer();

    private final AllvrBuffers buffers = new AllvrBuffers();
    private final AllvrShaderCache shaders = new AllvrShaderCache();
    private final AllvrNodeStore nodes = new AllvrNodeStore();
    private final Long2ObjectOpenHashMap<Cube> renderCubes = new Long2ObjectOpenHashMap<>();
    /** Dedupe set for submitted mesh jobs (main thread only). */
    private final LongOpenHashSet pending = new LongOpenHashSet();
    private final int[] commands = new int[AllvrBuffers.COMMAND_STRIDE * AllvrBuffers.MAX_COMMANDS];

    // GPU-cull path state (4a). Nodes are maintained regardless of the active
    // path (setMesh/freeNode mirror assignQuads/forget), so the config switch
    // is seamless without a resync pass.
    private final float[] frustumPlanes = new float[24];
    private final Matrix4f projViewScratch = new Matrix4f();
    private final Vector4f planeScratch = new Vector4f();
    private boolean mdiOk;
    /** True when the context is GL 4.6 core (MDIC entry point choice). */
    private boolean coreGl46;
    private boolean warnedGpuCaps;
    // starts at 2 so cleared stamps (0) and pre-run stamps never alias a
    // live lastFrameId (>= 2) in the two-phase membership test
    private int frameId = 2;
    /** 1.5·near·far/(far−near): view-space → depth-space bias for HiZ tests. */
    private float depthBiasScale = -1f;
    private long lastGpuDebugMillis;
    /** HiZ re-allocation throttle state (failure retry backoff). */
    private long lastHizAllocMillis;
    private boolean lastHizAllocFailed;

    private boolean initialized;
    private boolean tierOk;
    private boolean warnedTier;
    private boolean warnedPack;
    private boolean warnedPatchFallback;
    // iris integration (G2 draw mounting): the allay pipeline's patch data +
    // the pack-lit draw targets it resolves to
    private AllvrIrisPipelineData irisData;
    private AllvrIrisFrameTarget frameTarget;
    private int appliedCustomIdRevision = -1;
    private int loggedMeshResults;
    private long lastStatsLogMillis;
    /** Cubes currently holding a deferred (arena-starved) mesh stream. */
    private int deferredCount;
    private long lastStarveWarnMillis;

    /** Texture unit for the vanilla lightmap in the level-2 albedo pass
     *  (outside the HiZ/MC-depth compute units and the patch's sampler range). */
    private static final int LIGHTMAP_UNIT = 5;

    private static final class Cube {
        int slot = -1;
        int quadStart = -1;
        int quadCount = 0;
        boolean needsRemesh;
        /** Mesh result held when the quad arena couldn't fit it — retried by
         *  {@link #retryDeferred} once {@code AllvrBuffers#canFit} passes, so
         *  an exhausted arena defers instead of dropping the cube forever. */
        long[] deferredQuads;
    }

    // ------------------------------------------------------------------
    // frame
    // ------------------------------------------------------------------

    public void onRenderStage(RenderLevelStageEvent event) {
        Minecraft mc = Minecraft.getInstance();
        ClientLevel level = mc.level;
        if (level == null || level.dimension() != AllvrDimensions.ALLAY_LEVEL) {
            return;
        }
        boolean packInUse = irisPackInUse();
        // iris integration context — resolved BEFORE the stage gate so the vx*
        // uniform suppliers stay current even on frames the terrain draw skips
        AllvrIrisPipelineData data = null;
        if (packInUse && ClientConfig.allvrIrisIntegration) {
            data = AllvrIrisDataHolder.current();
            if (data != null) {
                AllvrVoxyUniforms.update(event.getModelViewMatrix(), event.getProjectionMatrix(),
                    mc.options.renderDistance().get() * 16);
                this.syncIrisData(data);
            }
        }
        RenderLevelStageEvent.Stage stage = event.getStage();
        // mode decision (grilling decision ⑧ fallback chain):
        //  1. pack-lit (patched): voxy.json patch function resolved + compiled →
        //     AFTER_SOLID_BLOCKS, inside the gbuffer phase before the pack's
        //     deferred passes sample the patch-written buffers (Photon)
        //  2. albedo pass: voxy.json resolves but ships NO patch function
        //     (Complementary) — vanilla-gbuffer-mimicking albedo into the pack's
        //     declared colortex, still at AFTER_SOLID_BLOCKS; the pack's own
        //     deferred lighting shades our pixels like vanilla terrain
        //  3. unpatched coexistence (no voxy.json / compile failure / switch
        //     off): AFTER_LEVEL, self-lit after the composite blit (V0 behavior)
        //  4. no pack: AFTER_SKY (vanilla window, byte-identical V0)
        boolean patched = data != null && this.shaders.patchedTerrain() != 0;
        boolean albedo = !patched && data != null && this.shaders.albedoTerrain() != 0;
        RenderLevelStageEvent.Stage chosen = patched || albedo
            ? RenderLevelStageEvent.Stage.AFTER_SOLID_BLOCKS
            : packInUse ? RenderLevelStageEvent.Stage.AFTER_LEVEL
            : RenderLevelStageEvent.Stage.AFTER_SKY;
        if (stage != chosen) {
            return;
        }
        if (packInUse && !this.warnedPack) {
            this.warnedPack = true;
            chat(mc, data != null
                ? "[Allvr] shader pack active — allay terrain drawn into the pack's gbuffer (pack-lit)"
                : "[Allvr] shader pack active — drawing allay terrain post-composite "
                    + "(no pack lighting; V0 coexistence)");
        }
        if (!this.initialized) {
            this.initialize(mc);
        }
        if (!this.tierOk || !this.shaders.ready() || !this.buffers.ready()) {
            if (this.shaders.needsRebuild() && this.buffers.ready()) {
                this.shaders.rebuild();
            }
            return;
        }

        this.pumpResults();
        // 4a: GPU-driven path (node traversal → cmdgen → MDIC) behind the
        // config switch; the CPU path below is the V0 fallback and stays
        // until 4c acceptance removes it.
        if (this.wantGpu()) {
            this.gpuDraw(event, level, data);
        } else {
            this.draw(event, level, data);
        }
    }

    /**
     * Keeps the iris-integration runtime state in step with the pipeline data:
     * the patched program (identity-keyed, rebuilt after an F3+T), the pack-lit
     * frame target (one per data — the old one's GL objects are released) and
     * the customId column of the state table (full re-resolve on pack change,
     * revision-driven re-upload for late-registered states).
     */
    private void syncIrisData(AllvrIrisPipelineData data) {
        this.shaders.syncPatchedTerrain(data);
        if (this.shaders.patchedTerrain() == 0) {
            // no patch function in the pack's voxy.json (or it failed to
            // compile) — the level-2 albedo pass takes over
            this.shaders.syncAlbedoTerrain(data);
        }
        if (data == this.irisData) {
            if (AllvrRenderStateMap.customIdRevision() != this.appliedCustomIdRevision) {
                this.appliedCustomIdRevision = AllvrRenderStateMap.setCustomIds(data.getCustomIds());
                this.buffers.invalidateStateTable();
            }
            return;
        }
        if (this.frameTarget != null) {
            this.frameTarget.destroy();
            this.frameTarget = null;
        }
        this.irisData = data;
        this.frameTarget = new AllvrIrisFrameTarget(data, AllvrIrisDataHolder.depthSupplier());
        data.setDepthTextures(this.frameTarget::depthTexture, this.frameTarget::depthTexture);
        this.appliedCustomIdRevision = AllvrRenderStateMap.setCustomIds(data.getCustomIds());
        this.buffers.invalidateStateTable();
    }

    /**
     * GPU path gate: config + MDIC caps + all four compute programs. The
     * GPU-cull buffers must NOT be part of this gate — they are allocated
     * on demand inside {@link #gpuDraw}; requiring them here deadlocked the
     * switch (gate false → gpuDraw never runs → buffers never allocated).
     */
    private boolean wantGpu() {
        if (!ClientConfig.allvrGpuPipeline) {
            return false;
        }
        if (!this.mdiOk) {
            if (!this.warnedGpuCaps) {
                this.warnedGpuCaps = true;
                CreateManaIndustry.LOGGER.warn(
                    "[Allvr] gpuPipeline requested but MDIC (GL 4.6 / ARB_indirect_parameters) unavailable — CPU path active");
            }
            return false;
        }
        return this.shaders.gpuReady();
    }

    private void initialize(Minecraft mc) {
        this.initialized = true;
        var caps = org.lwjgl.opengl.GL.getCapabilities();
        // per-command draw parameters: GL 4.6 core, or the ARB extension on 4.5
        this.tierOk = caps.OpenGL46 || caps.GL_ARB_shader_draw_parameters;
        AllvrShaderCache.setUseExtensionFallback(!caps.OpenGL46 && caps.GL_ARB_shader_draw_parameters);
        // MDIC (GL_PARAMETER_BUFFER draw count): GL 4.6 core or ARB_indirect_parameters
        this.mdiOk = caps.OpenGL46 || caps.GL_ARB_indirect_parameters;
        this.coreGl46 = caps.OpenGL46;
        CreateManaIndustry.LOGGER.info(
            "[Allvr] caps probe: OpenGL46={} ARB_shader_draw_parameters={} ARB_indirect_parameters={} → tier {} mdi {}",
            caps.OpenGL46, caps.GL_ARB_shader_draw_parameters, caps.GL_ARB_indirect_parameters,
            this.tierOk ? "B" : "C", this.mdiOk ? "ok" : "unavailable");
        this.buffers.ensure();
        AllvrMesherWorker.start();
        if (!this.tierOk && !this.warnedTier) {
            this.warnedTier = true;
            chat(mc, "[Allvr] GL 4.6 / ARB_shader_draw_parameters unavailable — allay terrain disabled (Tier C)");
        }
    }

    // ------------------------------------------------------------------
    // cube lifecycle (main thread)
    // ------------------------------------------------------------------

    public void onCubeApplied(long key) {
        if (!this.initialized) {
            // main thread === render thread: GL context is live, so the lazy
            // init is safe here too (cube packets can arrive before the first
            // rendered frame)
            this.initialize(Minecraft.getInstance());
        }
        Cube rc = this.renderCubes.get(key);
        if (rc == null) {
            rc = new Cube();
            AllvrCubePos pos = AllvrCubePos.fromLong(key);
            rc.slot = this.buffers.allocSlot(pos.minBlockX(), pos.minBlockY(), pos.minBlockZ());
            this.renderCubes.put(key, rc);
        }
        this.submit(key);
    }

    public void onCubeForgotten(long key) {
        Cube rc = this.renderCubes.remove(key);
        if (rc == null) {
            return;
        }
        if (rc.deferredQuads != null) {
            this.deferredCount--;
        }
        if (rc.quadStart >= 0) {
            this.buffers.freeRange(rc.quadStart, rc.quadCount);
        }
        this.buffers.freeSlot(rc.slot);
        this.nodes.freeNode(key);
        this.pending.remove(key);
        this.sawFirstMesh.remove(key);
    }

    /**
     * Client-side block change: remesh the cube (and border-adjacent neighbors
     * for the face-culling seam), plus the light-driven dirty set (grilling
     * decision ⑥): an occluder change shifts the sky column exposure BELOW it
     * (the column scan looks up from every voxel; the 128-block window spans
     * 4 cubes), an emitter change relights every cube whose voxels sit within
     * manhattan 15 of it.
     */
    public void onBlockChanged(net.minecraft.core.BlockPos pos, BlockState oldState, BlockState newState) {
        AllvrCubePos cpos = AllvrCubePos.of(pos);
        long key = cpos.asLong();
        Cube rc = this.renderCubes.get(key);
        if (rc == null) {
            return;
        }
        this.submit(key);
        // a change at the cube border can invalidate the neighbor's culled
        // faces across the seam — remesh only the directly touched neighbors
        int lx = pos.getX() & 31;
        int ly = pos.getY() & 31;
        int lz = pos.getZ() & 31;
        if (lx == 0) {
            this.dirtyCube(cpos.getX() - 1, cpos.getY(), cpos.getZ());
        } else if (lx == 31) {
            this.dirtyCube(cpos.getX() + 1, cpos.getY(), cpos.getZ());
        }
        if (ly == 0) {
            this.dirtyCube(cpos.getX(), cpos.getY() - 1, cpos.getZ());
        } else if (ly == 31) {
            this.dirtyCube(cpos.getX(), cpos.getY() + 1, cpos.getZ());
        }
        if (lz == 0) {
            this.dirtyCube(cpos.getX(), cpos.getY(), cpos.getZ() - 1);
        } else if (lz == 31) {
            this.dirtyCube(cpos.getX(), cpos.getY(), cpos.getZ() + 1);
        }
        // light dirt — the same rules AllvrLightBaker bakes from
        if (oldState != null && newState != null) {
            if (AllvrMesher.occludesAt(oldState) != AllvrMesher.occludesAt(newState)) {
                for (int k = 1; k <= AllvrLightBaker.SKY_WINDOW_BLOCKS >> 5; k++) {
                    this.dirtyCube(cpos.getX(), cpos.getY() - k, cpos.getZ());
                }
            }
            if (oldState.getLightEmission() != newState.getLightEmission()) {
                // per axis at most one neighbor qualifies (15 < 32)
                int minX = lx <= 14 ? -1 : 0;
                int maxX = lx >= 17 ? 1 : 0;
                int minY = ly <= 14 ? -1 : 0;
                int maxY = ly >= 17 ? 1 : 0;
                int minZ = lz <= 14 ? -1 : 0;
                int maxZ = lz >= 17 ? 1 : 0;
                for (int dx = minX; dx <= maxX; dx++) {
                    for (int dy = minY; dy <= maxY; dy++) {
                        for (int dz = minZ; dz <= maxZ; dz++) {
                            if ((dx | dy | dz) != 0) {
                                this.dirtyCube(cpos.getX() + dx, cpos.getY() + dy, cpos.getZ() + dz);
                            }
                        }
                    }
                }
            }
        }
    }

    public void dropLevel() {
        this.renderCubes.clear();
        this.pending.clear();
        this.deferredCount = 0;
        this.buffers.reset();
        this.nodes.clear();
        // zero the GPU node buffer on the next sync — nodes.clear() alone
        // leaves the old session's nodes alive in it (capacity unchanged →
        // the dirty-set upload is a no-op) and the traversal's over-dispatch
        // tail draws them as ghost cubes with stale arena offsets
        this.buffers.invalidateNodeUpload();
        AllvrMesherWorker.clearQueues();
    }

    public void requestShaderRebuild() {
        this.shaders.requestRebuild();
    }

    private void submit(long key) {
        if (!this.pending.add(key)) {
            return;
        }
        if (AllvrMesherWorker.threadOrNull() == null) {
            AllvrMesherWorker.start();
        }
        AllvrMesherWorker.submit(key);
    }

    /** Marks a cube (any offset) for remesh when it holds geometry. */
    private void dirtyCube(int cx, int cy, int cz) {
        long nkey = AllvrCubePos.of(cx, cy, cz).asLong();
        Cube rc = this.renderCubes.get(nkey);
        if (rc != null && rc.quadCount > 0) {
            this.submit(nkey);
        }
    }

    // ------------------------------------------------------------------
    // results + draw (render thread)
    // ------------------------------------------------------------------

    private void pumpResults() {
        AllvrMesherWorker.MeshResult result;
        while ((result = AllvrMesherWorker.poll()) != null) {
            long key = result.key();
            this.pending.remove(key);
            Cube rc = this.renderCubes.get(key);
            if (rc == null) {
                continue;
            }
            if (rc.quadStart >= 0) {
                this.buffers.freeRange(rc.quadStart, rc.quadCount);
                rc.quadStart = -1;
                rc.quadCount = 0;
            }
            if (result.quads().length > 0) {
                if (this.loggedMeshResults < 3) {
                    this.loggedMeshResults++;
                    CreateManaIndustry.LOGGER.info("[Allvr] mesh result #{}: cube {} → {} quads",
                        this.loggedMeshResults, AllvrCubePos.fromLong(key), result.quads().length);
                }
                int start = this.buffers.allocRange(result.quads().length);
                if (start < 0) {
                    // arena full: hold the stream and retry when space frees up —
                    // dropping it left the cube unrendered until some later block
                    // change happened to re-trigger a remesh
                    // the stale node still points at the just-freed range — drop it
                    this.unpublishNodeMesh(key);
                    if (rc.deferredQuads == null) {
                        this.deferredCount++;
                    }
                    rc.deferredQuads = result.quads();
                    long now = System.currentTimeMillis();
                    if (now - this.lastStarveWarnMillis > 5000) {
                        this.lastStarveWarnMillis = now;
                        CreateManaIndustry.LOGGER.warn("[Allvr] quad arena full — {} cube(s) deferred",
                            this.deferredCount);
                    }
                } else {
                    if (rc.deferredQuads != null) {
                        // a fresher stream just landed — the stale one is superseded
                        rc.deferredQuads = null;
                        this.deferredCount--;
                    }
                    this.assignQuads(key, rc, start, result.quads());
                }
            } else {
                // empty mesh: the range freed above is never re-published —
                // drop the stale node (see unpublishNodeMesh)
                this.unpublishNodeMesh(key);
            }
            if (rc.needsRemesh) {
                rc.needsRemesh = false;
                this.submit(key);
            }
        }
        this.retryDeferred();
    }

    private void assignQuads(long key, Cube rc, int start, long[] quads) {
        this.buffers.uploadQuads(start, quads);
        rc.quadStart = start;
        rc.quadCount = quads.length;
        // GPU-cull path: publish the mesh into the node SSBO. Slotless cubes
        // (cubeInfo table full) stay nodeless — the V0 draw skips them too.
        if (rc.slot >= 0) {
            AllvrCubePos cpos = AllvrCubePos.fromLong(key);
            this.nodes.setMesh(key,
                new net.minecraft.core.BlockPos(cpos.minBlockX(), cpos.minBlockY(), cpos.minBlockZ()),
                start, quads.length, rc.slot);
        }
        // first mesh: neighbors meshed earlier may have culled faces
        // against this cube while it was still void air
        if (rc.slot >= 0 && !this.sawFirstMesh.contains(key)) {
            this.sawFirstMesh.add(key);
            this.dirtyAllNeighbors(key);
        }
    }

    /**
     * Drops the cube's node publication (GPU-cull path) after its arena range
     * was freed. Without this, the node keeps HAS_MESH + stale quadStart/
     * quadCount pointing at freed — and possibly already-reused — quad memory,
     * which the traversal then draws as ghost geometry at this cube's origin:
     * a GPU-path-only artifact (the V0 draw skips the cube via quadCount == 0),
     * same class as the 4b dropLevel ghost fix. Reached by two pumpResults
     * branches: an empty mesh result, and an arena-starved (deferred) remesh.
     * {@code freeNode} is a no-op for cubes that never published a node (first
     * mesh already empty, or the cubeInfo table was full); a later
     * {@link #assignQuads} re-allocates the node via {@code setMesh}.
     */
    private void unpublishNodeMesh(long key) {
        this.nodes.freeNode(key);
    }

    /** Uploads deferred mesh results once the arena can actually fit them.
     *  {@code canFit} mirrors {@code allocRange}'s success test, so a
     *  fragmented arena waits for real contiguous space instead of
     *  spin-remeshing the same cube every frame. */
    private void retryDeferred() {
        if (this.deferredCount == 0) {
            return;
        }
        Iterator<Long2ObjectOpenHashMap.Entry<Cube>> it = this.renderCubes.long2ObjectEntrySet().fastIterator();
        while (it.hasNext()) {
            Long2ObjectOpenHashMap.Entry<Cube> e = it.next();
            Cube rc = e.getValue();
            if (rc.deferredQuads == null || !this.buffers.canFit(rc.deferredQuads.length)) {
                continue;
            }
            int start = this.buffers.allocRange(rc.deferredQuads.length);
            if (start < 0) {
                continue; // arena changed between canFit and alloc — retry next frame
            }
            long[] quads = rc.deferredQuads;
            rc.deferredQuads = null;
            this.deferredCount--;
            this.assignQuads(e.getLongKey(), rc, start, quads);
        }
    }

    private final LongOpenHashSet sawFirstMesh = new LongOpenHashSet();

    private void dirtyAllNeighbors(long key) {
        AllvrCubePos pos = AllvrCubePos.fromLong(key);
        for (int axis = 0; axis < 3; axis++) {
            for (int dir = -1; dir <= 1; dir += 2) {
                int x = pos.getX() + (axis == 0 ? dir : 0);
                int y = pos.getY() + (axis == 1 ? dir : 0);
                int z = pos.getZ() + (axis == 2 ? dir : 0);
                long nkey = AllvrCubePos.of(x, y, z).asLong();
                Cube rc = this.renderCubes.get(nkey);
                if (rc != null && rc.quadCount > 0) {
                    this.submit(nkey);
                }
            }
        }
    }

    private void draw(RenderLevelStageEvent event, ClientLevel level, AllvrIrisPipelineData data) {
        Minecraft mc = Minecraft.getInstance();
        Camera camera = event.getCamera();
        Vec3 camPos = camera.getPosition();
        Frustum frustum = event.getFrustum();

        int n = 0;
        int cubesWithGeometry = 0;
        Iterator<Long2ObjectOpenHashMap.Entry<Cube>> it = this.renderCubes.long2ObjectEntrySet().fastIterator();
        while (it.hasNext() && n < AllvrBuffers.MAX_COMMANDS) {
            Long2ObjectOpenHashMap.Entry<Cube> e = it.next();
            Cube rc = e.getValue();
            if (rc.quadCount <= 0 || rc.slot < 0) {
                continue;
            }
            cubesWithGeometry++;
            AllvrCubePos pos = AllvrCubePos.fromLong(e.getLongKey());
            if (!frustum.isVisible(new AABB(pos.minBlockX(), pos.minBlockY(), pos.minBlockZ(),
                pos.minBlockX() + 32, pos.minBlockY() + 32, pos.minBlockZ() + 32))) {
                continue;
            }
            // one command reaches quads only through the shared index buffer
            // (MAX_QUADS_PER_COMMAND); craftable cubes beyond that (e.g.
            // checkerboard, ~5×10⁴ quads) continue in consecutive commands
            int remaining = rc.quadCount;
            int baseQuad = rc.quadStart;
            while (remaining > 0 && n < AllvrBuffers.MAX_COMMANDS) {
                int take = Math.min(remaining, AllvrBuffers.MAX_QUADS_PER_COMMAND);
                int o = n * AllvrBuffers.COMMAND_STRIDE;
                this.commands[o] = take * 6;
                this.commands[o + 1] = 1;
                this.commands[o + 2] = 0;
                this.commands[o + 3] = baseQuad * 4;
                this.commands[o + 4] = rc.slot;
                baseQuad += take;
                remaining -= take;
                n++;
            }
        }
        if (n == 0) {
            this.logStats(0, cubesWithGeometry);
            return;
        }

        int prog = this.shaders.terrain();
        if (this.shaders.needsRebuild()) {
            this.shaders.rebuild();
            prog = this.shaders.terrain();
            if (prog == 0) {
                return;
            }
        }

        this.buffers.uploadCommands(this.commands, n);
        this.drawTerrain(event, level, camPos, n, false, data);
        this.logStats(n, cubesWithGeometry);

        // off-departure hygiene (particle-engine discipline)
        RenderSystem.depthMask(true);
        RenderSystem.defaultBlendFunc();
        RenderSystem.disableBlend();
        GL20.glUseProgram(0);
        this.buffers.unbind();
    }

    /** Camera-relative + fog + day uniforms shared by both draw paths. */
    private void terrainUniforms(int prog, RenderLevelStageEvent event, ClientLevel level, Vec3 camPos) {
        AllvrShaderCache.uniformMat4(prog, "ModelViewMat", event.getModelViewMatrix());
        AllvrShaderCache.uniformMat4(prog, "ProjMat", event.getProjectionMatrix());
        int camX = net.minecraft.util.Mth.floor(camPos.x);
        int camY = net.minecraft.util.Mth.floor(camPos.y);
        int camZ = net.minecraft.util.Mth.floor(camPos.z);
        AllvrShaderCache.uniformIVec3(prog, "uCamInt", camX, camY, camZ);
        AllvrShaderCache.uniformVec3(prog, "uCamFrac",
            (float) (camPos.x - camX), (float) (camPos.y - camY), (float) (camPos.z - camZ));
        AllvrShaderCache.uniformFloat(prog, "uLight", dayFactor(level));

        // fog: vanilla 1.21.1 exposes the current level fog via RenderSystem
        // scalars; unset defaults (0..1) mean "no fog set" — treat as none
        float fogStart = RenderSystem.getShaderFogStart();
        float fogEnd = RenderSystem.getShaderFogEnd();
        if (fogEnd > fogStart) {
            float[] fogColor = RenderSystem.getShaderFogColor();
            AllvrShaderCache.uniformFloat(prog, "uFogStart", fogStart);
            AllvrShaderCache.uniformFloat(prog, "uFogEnd", fogEnd);
            AllvrShaderCache.uniformVec3(prog, "uFogColor", fogColor[0], fogColor[1], fogColor[2]);
        } else {
            AllvrShaderCache.uniformFloat(prog, "uFogStart", 1.0e6f);
            AllvrShaderCache.uniformFloat(prog, "uFogEnd", 2.0e6f);
            AllvrShaderCache.uniformVec3(prog, "uFogColor", 1, 1, 1);
        }
        AllvrShaderCache.uniformInt(prog, "uAtlas", 0);
        AllvrShaderCache.uniformInt(prog, "uStateTable", AllvrBuffers.STATE_TBO_UNIT);
    }

    /**
     * Shared draw tail for both command sources (doc §13 iris slice G2):
     * patched-program selection (pack-lit vs unpatched fallback), the pack-lit
     * frame target bind/unbind with FBO+viewport save/restore (the vanilla
     * translucents and iris's own passes continue in the same frame), the
     * common terrain program state, the MDI submission and the shadow-pass
     * depth-only draw.
     */
    private void drawTerrain(RenderLevelStageEvent event, ClientLevel level, Vec3 camPos,
                             int commandCount, boolean useMdIC, AllvrIrisPipelineData data) {
        Minecraft mc = Minecraft.getInstance();
        // 0 = unpatched main-target draw, 1 = patched (pack-lit), 2 = albedo pass
        int mode = 0;
        int prog = this.shaders.terrain();
        if (data != null) {
            prog = this.shaders.patchedTerrain();
            if (prog != 0) {
                mode = 1;
            } else if (this.shaders.albedoTerrain() != 0) {
                mode = 2;
                prog = this.shaders.albedoTerrain();
            } else if (!this.warnedPatchFallback) {
                this.warnedPatchFallback = true;
                CreateManaIndustry.LOGGER.warn("[Allvr] patched terrain programs unavailable — falling back "
                    + "to the unpatched draw (fallback chain, grilling decision ⑧)");
            }
        }
        if (prog == 0) {
            return;
        }

        int savedFbo = 0;
        int[] savedViewport = null;
        if (mode > 0) {
            savedFbo = GL11.glGetInteger(GL30.GL_DRAW_FRAMEBUFFER_BINDING);
            savedViewport = new int[4];
            GL11.glGetIntegerv(GL11.GL_VIEWPORT, savedViewport);
            var main = mc.getMainRenderTarget();
            if (this.frameTarget.beginFrame(main.width, main.height)) {
                this.frameTarget.bind();
            } else {
                // incomplete target (dead pack textures) → albedo fallback: the
                // unpatched program draws into the currently bound gbuffer FBO
                mode = 0;
                prog = this.shaders.terrain();
            }
        }

        // TBO freshness parity: the state table must cover every id the mesher
        // registers (invalidateStateTable forces the re-upload after a
        // customId re-resolve — grilling decision ⑦)
        this.buffers.ensureStateTable(AllvrRenderStateMap.entryCount());
        GL20.glUseProgram(prog);
        this.buffers.bindForDraw();
        this.terrainUniforms(prog, event, level, camPos);
        if (mode == 2) {
            // albedo pass samples the vanilla lightmap with the baked nibbles
            GL13.glActiveTexture(GL13.GL_TEXTURE0 + LIGHTMAP_UNIT);
            GL11.glBindTexture(GL11.GL_TEXTURE_2D, AllvrIrisPipelineData.getLightmapTextureId());
            GL20.glUniform1i(GL20.glGetUniformLocation(prog, "uLightmapTex"), LIGHTMAP_UNIT);
        }
        GL13.glActiveTexture(GL13.GL_TEXTURE0);
        GL11.glBindTexture(GL11.GL_TEXTURE_2D,
            mc.getTextureManager().getTexture(TextureAtlas.LOCATION_BLOCKS).getId());

        // state: opaque pass (borrowed depth — the pack's deferred shading and
        // the vanilla translucents depth-test against it correctly)
        RenderSystem.enableDepthTest();
        GL11.glEnable(GL11.GL_CULL_FACE);
        GL11.glCullFace(GL11.GL_BACK);
        RenderSystem.depthMask(true);
        RenderSystem.disableBlend();

        if (useMdIC) {
            this.buffers.drawIndirectCount(this.coreGl46);
        } else {
            this.buffers.draw(commandCount);
        }

        if (mode > 0) {
            if (mode == 2) {
                GL13.glActiveTexture(GL13.GL_TEXTURE0 + LIGHTMAP_UNIT);
                GL11.glBindTexture(GL11.GL_TEXTURE_2D, 0);
                GL13.glActiveTexture(GL13.GL_TEXTURE0);
            }
            this.frameTarget.unbind();
            if (ClientConfig.allvrIrisShadowPass) {
                this.drawShadowPass(commandCount, useMdIC);
            }
            GL30.glBindFramebuffer(GL30.GL_FRAMEBUFFER, savedFbo);
            GL11.glViewport(savedViewport[0], savedViewport[1], savedViewport[2], savedViewport[3]);
        }
    }

    /**
     * G2 shadow-pass participation (grilling decision ⑥, sub-switch
     * {@code allvrIrisShadowPass}): depth-only MDI into the pack's shadow map.
     * iris rendered its own shadow content earlier this frame — ours adds on
     * top via depth LEQUAL, so the pack's deferred shadow sampling sees allay
     * terrain shadows. Runs inside the patched draw's stage slot (still before
     * every composite that samples shadowtex). The draw is world-space (the
     * shadow matrices consume absolute positions, uCamInt = 0): float32 at
     * |Y| ≈ 30M quantizes to 2-block steps — the accepted R20 envelope.
     */
    private void drawShadowPass(int commandCount, boolean useMdIC) {
        Matrix4f shadowModelView = AllvrIrisDataHolder.shadowModelView();
        Matrix4f shadowProjection = AllvrIrisDataHolder.shadowProjection();
        if (shadowModelView == null || shadowProjection == null) {
            return; // no shadow pass ran this frame (shadows off / night config)
        }
        int depthTex = AllvrIrisDataHolder.shadowDepthTexture();
        int resolution = AllvrIrisDataHolder.shadowResolution();
        if (!this.frameTarget.bindShadow(depthTex, resolution)) {
            return;
        }
        int prog = this.shaders.terrain(); // unpatched program — no TAA jitter in shadow space
        if (prog == 0) {
            return;
        }

        int savedFbo = GL11.glGetInteger(GL30.GL_DRAW_FRAMEBUFFER_BINDING);
        int[] savedViewport = new int[4];
        GL11.glGetIntegerv(GL11.GL_VIEWPORT, savedViewport);
        GL30.glBindFramebuffer(GL30.GL_FRAMEBUFFER, this.frameTarget.shadowFbo());
        GL11.glViewport(0, 0, resolution, resolution);

        GL20.glUseProgram(prog);
        AllvrShaderCache.uniformMat4(prog, "ModelViewMat", shadowModelView);
        AllvrShaderCache.uniformMat4(prog, "ProjMat", shadowProjection);
        AllvrShaderCache.uniformIVec3(prog, "uCamInt", 0, 0, 0);
        AllvrShaderCache.uniformVec3(prog, "uCamFrac", 0f, 0f, 0f);
        AllvrShaderCache.uniformInt(prog, "uAtlas", 0);
        AllvrShaderCache.uniformInt(prog, "uStateTable", AllvrBuffers.STATE_TBO_UNIT);
        // depth-only: the fsh still runs (alpha cutout discard) but writes no color
        GL11.glColorMask(false, false, false, false);
        RenderSystem.enableDepthTest();
        if (useMdIC) {
            this.buffers.drawIndirectCount(this.coreGl46);
        } else {
            this.buffers.draw(commandCount);
        }
        GL11.glColorMask(true, true, true, true);

        GL30.glBindFramebuffer(GL30.GL_FRAMEBUFFER, savedFbo);
        GL11.glViewport(savedViewport[0], savedViewport[1], savedViewport[2], savedViewport[3]);
    }

    /**
     * 4b GPU-driven draw (doc §9.1/§9.2/§9.4): flush dirty nodes → reset →
     * traversal (frustum + HiZ vs last frame's pyramid + two-phase split) →
     * finalize → cmdgen → clamp → one glMultiDrawElementsIndirectCount whose command
     * count is only ever read by the GPU (GL_PARAMETER_BUFFER) → build the HiZ
     * pyramid from the borrowed MC main depth → revalidate phase-1 stamps
     * against the fresh pyramid. Kernel sequence is serialized by SSBO/image
     * barriers; the terrain draw tail is the V0 path's program/state with a
     * different command source. HiZ degrades to frustum-only (4a) under an
     * active iris pack or when the pyramid can't be built (Q7 safe degradation).
     */
    private void gpuDraw(RenderLevelStageEvent event, ClientLevel level, AllvrIrisPipelineData data) {
        if (this.shaders.needsRebuild()) {
            this.shaders.rebuild();
            if (!this.shaders.gpuReady()) {
                this.draw(event, level, null); // compute compile failed — V0 fallback
                return;
            }
        }
        Minecraft mc = Minecraft.getInstance();
        Camera camera = event.getCamera();
        Vec3 camPos = camera.getPosition();

        this.buffers.ensureGpuCull();
        this.buffers.syncNodes(this.nodes);
        int highWater = this.nodes.highWater();
        int nodeCount = this.nodes.nodeCount();
        if (highWater == 0) {
            this.logGpuStats(nodeCount, 0, -1, -1, -1, "");
            return;
        }
        this.extractFrustum(event.getProjectionMatrix(), event.getModelViewMatrix(), camPos);
        int curFrame = ++this.frameId;
        int lastFrame = curFrame - 1;
        boolean hiz = !irisPackInUse() && this.shaders.hizReady()
            && this.extractDepthBias(event.getProjectionMatrix())
            && this.ensureHiz(mc);

        this.buffers.bindGpuCull();
        if (hiz) {
            GL13.glActiveTexture(GL13.GL_TEXTURE0 + AllvrBuffers.HIZ_UNIT);
            GL11.glBindTexture(GL11.GL_TEXTURE_2D, this.buffers.hizTexture());
            GL13.glActiveTexture(GL13.GL_TEXTURE0);
        }

        // 1. reset queue + command counters
        GL20.glUseProgram(this.shaders.cullReset());
        GL43.glDispatchCompute(1, 1, 1);
        GL42.glMemoryBarrier(GL43.GL_SHADER_STORAGE_BARRIER_BIT);

        // 2. traversal: frustum + HiZ cull every live node → two-phase queue + stamp
        int travProg = this.shaders.traversal();
        GL20.glUseProgram(travProg);
        GL20.glUniform4fv(GL20.glGetUniformLocation(travProg, "uPlanes"), this.frustumPlanes);
        AllvrShaderCache.uniformIVec3(travProg, "uCamInt",
            net.minecraft.util.Mth.floor(camPos.x),
            net.minecraft.util.Mth.floor(camPos.y),
            net.minecraft.util.Mth.floor(camPos.z));
        GL30.glUniform1ui(GL30.glGetUniformLocation(travProg, "uFrameId"), curFrame);
        GL30.glUniform1ui(GL30.glGetUniformLocation(travProg, "uLastFrameId"), lastFrame);
        GL30.glUniform1ui(GL30.glGetUniformLocation(travProg, "uHizEnabled"), hiz ? 1 : 0);
        if (hiz) {
            this.uploadHizUniforms(travProg, event, mc);
        }
        GL43.glDispatchCompute((highWater + 63) / 64, 1, 1);
        GL42.glMemoryBarrier(GL43.GL_SHADER_STORAGE_BARRIER_BIT);

        // 3. finalize: two-phase counts → cmdgen dispatch size
        GL20.glUseProgram(this.shaders.cullFinalize());
        GL43.glDispatchCompute(1, 1, 1);
        GL42.glMemoryBarrier(GL43.GL_SHADER_STORAGE_BARRIER_BIT);

        // 4. cmdgen: visible nodes (phase 1 first) → MDI commands + atomic count
        GL20.glUseProgram(this.shaders.cmdgen());
        this.buffers.dispatchCmdgenIndirect();
        GL42.glMemoryBarrier(GL43.GL_COMMAND_BARRIER_BIT | GL43.GL_SHADER_STORAGE_BARRIER_BIT);

        // 4b. clamp the MDI command count to MAX_COMMANDS: cmdgen drops commands
        // past the cap but keeps its counter running, and a parameter-buffer
        // count above maxcount makes the MDIC draw error out entirely (nothing
        // draws that frame) — converge in place before the draw consumes it
        GL20.glUseProgram(this.shaders.cullClamp());
        GL43.glDispatchCompute(1, 1, 1);
        GL42.glMemoryBarrier(GL43.GL_COMMAND_BARRIER_BIT | GL43.GL_SHADER_STORAGE_BARRIER_BIT);

        // triage readback (Q9): 5 s-throttled, debug-log gated — the real
        // per-frame path stays zero-readback. Also dumps node[0]'s raw uints
        // (GPU mirror truth) and a CPU re-run of the plane test (frustum
        // sanity vs the vanilla-Frustum count the V0 path logs).
        int phase1 = -1;
        int phase2 = -1;
        int cmdCount = -1;
        String triage = "";
        if (CreateManaIndustry.LOGGER.isDebugEnabled()
                && System.currentTimeMillis() - this.lastGpuDebugMillis > 5000) {
            this.lastGpuDebugMillis = System.currentTimeMillis();
            int[] q = new int[3];
            GL15.glBindBuffer(GL43.GL_SHADER_STORAGE_BUFFER, this.buffers.queueBuffer());
            GL15.glGetBufferSubData(GL43.GL_SHADER_STORAGE_BUFFER, 0, q);
            phase1 = q[0];
            phase2 = q[1];
            int[] c = new int[1];
            GL15.glBindBuffer(GL43.GL_SHADER_STORAGE_BUFFER, this.buffers.commandCountBuffer());
            GL15.glGetBufferSubData(GL43.GL_SHADER_STORAGE_BUFFER, 0, c);
            cmdCount = c[0];
            // command-stream validity triage: garbage commands (stale/ghost
            // node data, misaligned arena offsets) draw misaligned quads and
            // surface as stretched dark-line artifacts — the first commands
            // must reference an aligned, in-bounds arena range and a live slot
            String cmdTriage = "";
            if (cmdCount > 0) {
                int[] cmds = new int[20];
                GL15.glBindBuffer(GL43.GL_SHADER_STORAGE_BUFFER, this.buffers.commandBuffer());
                GL15.glGetBufferSubData(GL43.GL_SHADER_STORAGE_BUFFER, 0, cmds);
                StringBuilder sb = new StringBuilder(" | cmds");
                for (int i = 0; i < 4; i++) {
                    int count = cmds[i * 5];
                    int baseV = cmds[i * 5 + 3];
                    int inst = cmds[i * 5 + 4];
                    boolean ok = (baseV & 3) == 0 && cmds[i * 5 + 1] == 1 && cmds[i * 5 + 2] == 0
                        && inst > 0 && inst < AllvrBuffers.MAX_SLOTS
                        && ((long) (baseV >> 2)) + (count / 6) <= this.buffers.arenaUsedQuads();
                    sb.append(String.format(" [%d,%d,%d,%d,%d%s]", count, cmds[i * 5 + 1],
                        cmds[i * 5 + 2], baseV, inst, ok ? "" : " BAD"));
                }
                cmdTriage = sb.toString();
            }
            int[] n0 = new int[8];
            GL15.glBindBuffer(GL43.GL_SHADER_STORAGE_BUFFER, this.buffers.nodeBuffer());
            GL15.glGetBufferSubData(GL43.GL_SHADER_STORAGE_BUFFER, 0, n0);
            GL15.glBindBuffer(GL43.GL_SHADER_STORAGE_BUFFER, 0);
            int camX = net.minecraft.util.Mth.floor(camPos.x);
            int camY = net.minecraft.util.Mth.floor(camPos.y);
            int camZ = net.minecraft.util.Mth.floor(camPos.z);
            triage = String.format(
                " | planes0=(%.3f,%.3f,%.3f,%.3f) planes3=(%.3f,%.3f,%.3f,%.3f) cam=(%d,%d,%d)"
                    + " | node0=[%08x %08x %08x %08x %08x %08x %08x %08x]"
                    + " | node0mirror=[%08x %08x %08x %08x %08x %08x %08x %08x]"
                    + " | cpuFrustum %d/%d",
                this.frustumPlanes[0], this.frustumPlanes[1], this.frustumPlanes[2], this.frustumPlanes[3],
                this.frustumPlanes[12], this.frustumPlanes[13], this.frustumPlanes[14], this.frustumPlanes[15],
                camX, camY, camZ,
                n0[0], n0[1], n0[2], n0[3], n0[4], n0[5], n0[6], n0[7],
                this.nodes.mirror()[0], this.nodes.mirror()[1],
                this.nodes.mirror()[2], this.nodes.mirror()[3],
                this.nodes.mirror()[4], this.nodes.mirror()[5],
                this.nodes.mirror()[6], this.nodes.mirror()[7],
                this.debugCpuFrustumPass(camX, camY, camZ), this.nodes.nodeCount()) + cmdTriage
                + (cmdCount >= AllvrBuffers.MAX_COMMANDS ? " CLAMPED" : "");
        }

        // 5. terrain draw — shared tail (pack-lit FBO under a resolved patch),
        // MDIC submission with the GPU count
        this.drawTerrain(event, level, camPos, -1, true, data);
        this.logGpuStats(nodeCount, highWater, phase1, phase2, cmdCount, triage);

        // 6. build the fresh HiZ pyramid from this frame's depth, then
        // re-validate phase-1 stamps against it (their depth is on screen)
        if (hiz) {
            GL42.glMemoryBarrier(GL43.GL_FRAMEBUFFER_BARRIER_BIT | GL42.GL_TEXTURE_FETCH_BARRIER_BIT);
            this.buildHiz(mc);
            // pyramid levels were written via imageStore — make them visible to
            // the revalidate kernel's sampler reads before its dispatch
            GL42.glMemoryBarrier(GL43.GL_SHADER_IMAGE_ACCESS_BARRIER_BIT | GL42.GL_TEXTURE_FETCH_BARRIER_BIT);
            int revProg = this.shaders.revalidate();
            GL20.glUseProgram(revProg);
            AllvrShaderCache.uniformIVec3(revProg, "uCamInt",
                net.minecraft.util.Mth.floor(camPos.x),
                net.minecraft.util.Mth.floor(camPos.y),
                net.minecraft.util.Mth.floor(camPos.z));
            this.uploadHizUniforms(revProg, event, mc);
            // fixed over-dispatch: p1 lives GPU-side (zero readback) — the
            // kernel exits on the queue bounds; see gpu_cull_revalidate.comp
            GL43.glDispatchCompute((AllvrBuffers.QUEUE_CAPACITY + 63) / 64, 1, 1);
            GL42.glMemoryBarrier(GL43.GL_SHADER_IMAGE_ACCESS_BARRIER_BIT | GL42.GL_TEXTURE_FETCH_BARRIER_BIT);
        }

        // off-departure hygiene (particle-engine discipline)
        GL13.glActiveTexture(GL13.GL_TEXTURE0 + AllvrBuffers.HIZ_UNIT);
        GL11.glBindTexture(GL11.GL_TEXTURE_2D, 0);
        GL13.glActiveTexture(GL13.GL_TEXTURE0 + AllvrBuffers.MC_DEPTH_UNIT);
        GL11.glBindTexture(GL11.GL_TEXTURE_2D, 0);
        GL13.glActiveTexture(GL13.GL_TEXTURE0);
        RenderSystem.depthMask(true);
        RenderSystem.defaultBlendFunc();
        RenderSystem.disableBlend();
        GL20.glUseProgram(0);
        this.buffers.unbind();
        this.buffers.unbindGpuCull();
    }

    /** Binds the pyramid sampler + projection uniforms the HiZ chunk consumes. */
    private void uploadHizUniforms(int prog, RenderLevelStageEvent event, Minecraft mc) {
        GL20.glUniform1i(GL20.glGetUniformLocation(prog, "uHiz"), AllvrBuffers.HIZ_UNIT);
        GL20.glUniformMatrix4fv(GL20.glGetUniformLocation(prog, "uModelView"), false,
            event.getModelViewMatrix().get(new float[16]));
        GL20.glUniformMatrix4fv(GL20.glGetUniformLocation(prog, "uProj"), false,
            event.getProjectionMatrix().get(new float[16]));
        var main = mc.getMainRenderTarget();
        GL20.glUniform2f(GL20.glGetUniformLocation(prog, "uViewport"), main.width, main.height);
        GL20.glUniform1i(GL20.glGetUniformLocation(prog, "uHizTopLevel"), this.buffers.hizLevels() - 1);
        GL20.glUniform1f(GL20.glGetUniformLocation(prog, "uDepthBiasScale"), this.depthBiasScale);
    }

    /**
     * Depth-comparison bias scale: dz/du of the perspective depth for a
     * 1.5-block view-space margin (doc §9.2 conservative test), extracted from
     * the projection each frame (MC's far plane tracks fog distance). Returns
     * false when the matrix yields no sane perspective (HiZ disabled this frame).
     */
    private boolean extractDepthBias(Matrix4fc projectionMatrix) {
        try {
            float near = projectionMatrix.perspectiveNear();
            float far = projectionMatrix.perspectiveFar();
            if (near <= 0f || far <= near) {
                this.depthBiasScale = -1f;
                return false;
            }
            this.depthBiasScale = 1.5f * near * far / (far - near);
            return true;
        } catch (UnsupportedOperationException e) {
            this.depthBiasScale = -1f;
            return false;
        }
    }

    /** Allocates / resizes the HiZ pyramid to the current main target size. */
    private boolean ensureHiz(Minecraft mc) {
        var main = mc.getMainRenderTarget();
        int w = main.width;
        int h = main.height;
        if (w <= 0 || h <= 0) {
            return false;
        }
        if (this.buffers.hizReady() && this.buffers.hizWidth() == w && this.buffers.hizHeight() == h) {
            return true;
        }
        // re-allocation throttle: a failed attempt left hizReady() false, so
        // without this the alloc (and its GL error) would retry every frame
        long now = System.currentTimeMillis();
        if (this.lastHizAllocFailed && now - this.lastHizAllocMillis < 1000) {
            return false;
        }
        this.lastHizAllocMillis = now;
        this.buffers.allocHiz(w, h);
        this.lastHizAllocFailed = !this.buffers.hizReady();
        // the caller's hiz predicate must see the allocation outcome: returning
        // true unconditionally here left an incomplete texture bound (samples
        // 0.0 = near plane) and culled the entire world instead of degrading
        // to frustum-only
        return this.buffers.hizReady();
    }

    /**
     * Builds the MAX-depth pyramid from the main target's depth (verified
     * plain GL_DEPTH_COMPONENT/float in 1.21.1 MainTarget — directly
     * sampleable): level 0 = 2×2 depth quads, then a CPU-looped chain step
     * per mip with image-unit rebinding. An unbound/garbage depth source
     * collapses to "occludes nothing" — the safe-degradation contract.
     */
    private void buildHiz(Minecraft mc) {
        var main = mc.getMainRenderTarget();
        int hw = Math.max(1, (this.buffers.hizWidth() + 1) / 2);
        int hh = Math.max(1, (this.buffers.hizHeight() + 1) / 2);

        GL13.glActiveTexture(GL13.GL_TEXTURE0 + AllvrBuffers.MC_DEPTH_UNIT);
        GL11.glBindTexture(GL11.GL_TEXTURE_2D, main.getDepthTextureId());
        GL13.glActiveTexture(GL13.GL_TEXTURE0);

        int first = this.shaders.hizFirst();
        GL20.glUseProgram(first);
        GL20.glUniform1i(GL20.glGetUniformLocation(first, "uDepth"), AllvrBuffers.MC_DEPTH_UNIT);
        GL20.glUniform2f(GL20.glGetUniformLocation(first, "uDepthSize"), main.width, main.height);
        GL20.glUniform2f(GL20.glGetUniformLocation(first, "uHizSize"), hw, hh);
        GL43.glBindImageTexture(0, this.buffers.hizTexture(), 0, false, 0, GL43.GL_WRITE_ONLY, GL30.GL_R32F);
        GL43.glDispatchCompute(Math.max(1, (hw + 7) / 8), Math.max(1, (hh + 7) / 8), 1);
        GL42.glMemoryBarrier(GL43.GL_SHADER_IMAGE_ACCESS_BARRIER_BIT);

        int down = this.shaders.hizDownsample();
        GL20.glUseProgram(down);
        for (int lvl = 1; lvl < this.buffers.hizLevels(); lvl++) {
            int pw = AllvrBuffers.hizLevelDim(hw, lvl - 1);
            int ph = AllvrBuffers.hizLevelDim(hh, lvl - 1);
            int dw = AllvrBuffers.hizLevelDim(hw, lvl);
            int dh = AllvrBuffers.hizLevelDim(hh, lvl);
            GL43.glBindImageTexture(0, this.buffers.hizTexture(), lvl - 1, false, 0,
                GL43.GL_READ_ONLY, GL30.GL_R32F);
            GL43.glBindImageTexture(1, this.buffers.hizTexture(), lvl, false, 0,
                GL43.GL_WRITE_ONLY, GL30.GL_R32F);
            GL20.glUniform2f(GL20.glGetUniformLocation(down, "uSrcDims"), pw, ph);
            GL20.glUniform2f(GL20.glGetUniformLocation(down, "uDestSize"), dw, dh);
            GL43.glDispatchCompute(Math.max(1, (dw + 7) / 8), Math.max(1, (dh + 7) / 8), 1);
            GL42.glMemoryBarrier(GL43.GL_SHADER_IMAGE_ACCESS_BARRIER_BIT);
        }
        GL43.glBindImageTexture(0, 0, 0, false, 0, GL43.GL_READ_ONLY, GL30.GL_R32F);
        GL43.glBindImageTexture(1, 0, 0, false, 0, GL43.GL_WRITE_ONLY, GL30.GL_R32F);
    }

    /**
     * Gribb–Hartmann planes of Proj*View (particle-engine extraction, §6.5),
     * normalized; the camera's sub-block fraction is folded into each plane's
     * distance so the shader can test integer camera-relative AABBs directly.
     */
    private void extractFrustum(Matrix4fc projectionMatrix, Matrix4fc modelViewMatrix, Vec3 camPos) {
        Matrix4f m = this.projViewScratch.set(projectionMatrix).mul(modelViewMatrix);
        int camX = net.minecraft.util.Mth.floor(camPos.x);
        int camY = net.minecraft.util.Mth.floor(camPos.y);
        int camZ = net.minecraft.util.Mth.floor(camPos.z);
        float fx = (float) (camPos.x - camX);
        float fy = (float) (camPos.y - camY);
        float fz = (float) (camPos.z - camZ);
        for (int i = 0; i < 6; i++) {
            m.frustumPlane(i, this.planeScratch);
            float a = this.planeScratch.x;
            float b = this.planeScratch.y;
            float c = this.planeScratch.z;
            float d = this.planeScratch.w;
            float inv = 1f / (float) Math.sqrt(a * a + b * b + c * c);
            int o = i * 4;
            this.frustumPlanes[o] = a * inv;
            this.frustumPlanes[o + 1] = b * inv;
            this.frustumPlanes[o + 2] = c * inv;
            this.frustumPlanes[o + 3] = (d - (a * fx + b * fy + c * fz)) * inv;
        }
    }

    /** 5 s-throttled GPU-path stats (CPU-known counters + optional readback). */
    private void logGpuStats(int nodeCount, int highWater, int phase1, int phase2, int cmdCount, String triage) {
        long now = System.currentTimeMillis();
        if (now - this.lastStatsLogMillis < 5000) {
            return;
        }
        this.lastStatsLogMillis = now;
        if (phase1 >= 0) {
            CreateManaIndustry.LOGGER.info(
                "[Allvr] gpu frame: {} nodes ({} high water) → p1 {} + p2 {} = {} visible → {} commands{}",
                nodeCount, highWater, phase1, phase2, phase1 + phase2, cmdCount, triage);
        } else {
            CreateManaIndustry.LOGGER.info("[Allvr] gpu frame: {} nodes ({} high water){}",
                nodeCount, highWater, triage);
        }
    }

    /**
     * Triage: re-runs the traversal's exact plane test on the CPU for every
     * live node, using the same frustumPlanes array uploaded to the shader.
     * A nonzero count here with a GPU-side 0 pins the failure to the node
     * upload / kernel; a zero count here pins it to the plane extraction.
     */
    private int debugCpuFrustumPass(int camX, int camY, int camZ) {
        int pass = 0;
        for (var e : this.renderCubes.long2ObjectEntrySet()) {
            Cube rc = e.getValue();
            if (rc.quadCount <= 0 || rc.slot < 0) {
                continue;
            }
            AllvrCubePos p = AllvrCubePos.fromLong(e.getLongKey());
            float ox = p.minBlockX() - camX;
            float oy = p.minBlockY() - camY;
            float oz = p.minBlockZ() - camZ;
            boolean ok = true;
            for (int i = 0; i < 6 && ok; i++) {
                float nx = this.frustumPlanes[i * 4];
                float ny = this.frustumPlanes[i * 4 + 1];
                float nz = this.frustumPlanes[i * 4 + 2];
                float d = this.frustumPlanes[i * 4 + 3];
                float px = nx > 0f ? ox + 32f : ox;
                float py = ny > 0f ? oy + 32f : oy;
                float pz = nz > 0f ? oz + 32f : oz;
                if (nx * px + ny * py + nz * pz + d < 0f) {
                    ok = false;
                }
            }
            if (ok) {
                pass++;
            }
        }
        return pass;
    }

    /** 5 s-throttled pipeline stats — the V0 visibility triage log: commands > 0
     *  but nothing visible points at the shader/transform; commands == 0
     *  points at the geometry pipeline (snapshot → mesher → arena). */
    private void logStats(int commands, int cubesWithGeometry) {
        long now = System.currentTimeMillis();
        if (now - this.lastStatsLogMillis < 5000) {
            return;
        }
        this.lastStatsLogMillis = now;
        CreateManaIndustry.LOGGER.info("[Allvr] frame: {} MDI commands / {} cubes with geometry / {} render cubes",
            commands, cubesWithGeometry, this.renderCubes.size());
    }

    /** 0.25 at full darkness → 1.0 at clear day (V0 stand-in for phase-5 light). */
    private static float dayFactor(ClientLevel level) {
        return 0.25f + 0.75f * (15 - level.getSkyDarken()) / 15.0f;
    }

    private static void chat(Minecraft mc, String text) {
        try {
            mc.gui.getChat().addMessage(net.minecraft.network.chat.Component.literal(text));
        } catch (RuntimeException ignored) {
            // gui not ready — the log line below still records it
        }
        CreateManaIndustry.LOGGER.info(text);
    }

    /**
     * Pack detection for the stage switch. iris is a compileOnly dep guarded by
     * {@code IRIS_ACTIVE}; {@code isShaderPackInUse} is the reporting API (doc
     * §6.4) — we deliberately do NOT gate iris's own pipeline with it (see the
     * analysis in the phase-3 doc section).
     */
    private static boolean irisPackInUse() {
        if (!CreateManaIndustry.IRIS_ACTIVE) {
            return false;
        }
        try {
            return net.irisshaders.iris.api.v0.IrisApi.getInstance().isShaderPackInUse();
        } catch (Throwable t) {
            return false;
        }
    }

    private AllvrRenderer() {
    }
}
