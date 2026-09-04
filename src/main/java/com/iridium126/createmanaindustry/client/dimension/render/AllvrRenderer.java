package com.iridium126.createmanaindustry.client.dimension.render;

import java.util.Iterator;

import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import net.minecraft.client.Minecraft;
import net.minecraft.client.Camera;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.culling.Frustum;
import net.minecraft.client.renderer.texture.TextureAtlas;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL13;
import org.lwjgl.opengl.GL20;
import org.lwjgl.opengl.GL46;

import com.iridium126.createmanaindustry.CreateManaIndustry;
import com.iridium126.createmanaindustry.client.dimension.AllvrClientCubeCache;
import com.iridium126.createmanaindustry.dimension.AllvrDimensions;
import com.iridium126.createmanaindustry.dimension.cube.AllvrCubePos;
import com.mojang.blaze3d.systems.RenderSystem;

import net.neoforged.neoforge.client.event.RenderLevelStageEvent;

/**
 * ALLVR V0 terrain renderer (doc §13 phase 3): Tier B MDI forward pass —
 * CPU greedy mesher, per-cube MDI commands, CPU frustum culling, no occlusion
 * culling / LOD / deferred shading (those are phases 4–5).
 * <p>
 * Frame slot: AFTER_SKY by default (the vanilla-terrain window this pass
 * replaces); when an iris shader pack is in use the draw moves to AFTER_LEVEL
 * with a one-time chat note — iris restructures the frame (gbuffer capture +
 * composite blit) and the pack's final output would overwrite an AFTER_SKY
 * draw, while after the blit our terrain stays visible (unlit by the pack;
 * documented V0 coexistence trade-off, doc §6.4 实况).
 * <p>
 * Lifecycle: cube apply/forget/setBlock arrive from {@link AllvrClientCubeCache}
 * on the main thread; mesh jobs run on the mesher worker; results are drained
 * and uploaded on the render thread inside the stage handler.
 */
public final class AllvrRenderer {

    public static final AllvrRenderer INSTANCE = new AllvrRenderer();

    private final AllvrBuffers buffers = new AllvrBuffers();
    private final AllvrShaderCache shaders = new AllvrShaderCache();
    private final Long2ObjectOpenHashMap<Cube> renderCubes = new Long2ObjectOpenHashMap<>();
    /** Dedupe set for submitted mesh jobs (main thread only). */
    private final LongOpenHashSet pending = new LongOpenHashSet();
    private final int[] commands = new int[AllvrBuffers.COMMAND_STRIDE * AllvrBuffers.MAX_COMMANDS];

    private boolean initialized;
    private boolean tierOk;
    private boolean warnedTier;
    private boolean warnedPack;
    private int loggedMeshResults;
    private long lastStatsLogMillis;
    /** Cubes currently holding a deferred (arena-starved) mesh stream. */
    private int deferredCount;
    private long lastStarveWarnMillis;

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
        RenderLevelStageEvent.Stage stage = event.getStage();
        RenderLevelStageEvent.Stage chosen = packInUse
            ? RenderLevelStageEvent.Stage.AFTER_LEVEL
            : RenderLevelStageEvent.Stage.AFTER_SKY;
        if (stage != chosen) {
            return;
        }
        if (packInUse && !this.warnedPack) {
            this.warnedPack = true;
            chat(mc, "[Allvr] shader pack active — drawing allay terrain post-composite "
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
        this.draw(event, level);
    }

    private void initialize(Minecraft mc) {
        this.initialized = true;
        var caps = org.lwjgl.opengl.GL.getCapabilities();
        // per-command draw parameters: GL 4.6 core, or the ARB extension on 4.5
        this.tierOk = caps.OpenGL46 || caps.GL_ARB_shader_draw_parameters;
        AllvrShaderCache.setUseExtensionFallback(!caps.OpenGL46 && caps.GL_ARB_shader_draw_parameters);
        CreateManaIndustry.LOGGER.info("[Allvr] caps probe: OpenGL46={} ARB_shader_draw_parameters={} → tier {}",
            caps.OpenGL46, caps.GL_ARB_shader_draw_parameters, this.tierOk ? "B" : "C");
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
        this.pending.remove(key);
        this.sawFirstMesh.remove(key);
    }

    /** Client-side block change: remesh the cube (and border-adjacent neighbors). */
    public void onBlockChanged(net.minecraft.core.BlockPos pos) {
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
            this.dirtyNeighbor(cpos.getX() - 1, cpos.getY(), cpos.getZ());
        } else if (lx == 31) {
            this.dirtyNeighbor(cpos.getX() + 1, cpos.getY(), cpos.getZ());
        }
        if (ly == 0) {
            this.dirtyNeighbor(cpos.getX(), cpos.getY() - 1, cpos.getZ());
        } else if (ly == 31) {
            this.dirtyNeighbor(cpos.getX(), cpos.getY() + 1, cpos.getZ());
        }
        if (lz == 0) {
            this.dirtyNeighbor(cpos.getX(), cpos.getY(), cpos.getZ() - 1);
        } else if (lz == 31) {
            this.dirtyNeighbor(cpos.getX(), cpos.getY(), cpos.getZ() + 1);
        }
    }

    public void dropLevel() {
        this.renderCubes.clear();
        this.pending.clear();
        this.deferredCount = 0;
        this.buffers.reset();
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

    private void dirtyNeighbor(int cx, int cy, int cz) {
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
        // first mesh: neighbors meshed earlier may have culled faces
        // against this cube while it was still void air
        if (rc.slot >= 0 && !this.sawFirstMesh.contains(key)) {
            this.sawFirstMesh.add(key);
            this.dirtyAllNeighbors(key);
        }
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

    private void draw(RenderLevelStageEvent event, ClientLevel level) {
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

        this.buffers.ensureStateTable(AllvrRenderStateMap.entryCount());
        this.buffers.uploadCommands(this.commands, n);

        GL20.glUseProgram(prog);
        this.buffers.bindForDraw();

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

        GL13.glActiveTexture(GL13.GL_TEXTURE0);
        GL11.glBindTexture(GL11.GL_TEXTURE_2D,
            mc.getTextureManager().getTexture(TextureAtlas.LOCATION_BLOCKS).getId());

        // state: opaque forward pass into the MC main target (borrowed depth —
        // vanilla entities/translucents render after and depth-test correctly)
        RenderSystem.enableDepthTest();
        GL11.glEnable(GL11.GL_CULL_FACE);
        GL11.glCullFace(GL11.GL_BACK);
        RenderSystem.depthMask(true);
        RenderSystem.disableBlend();

        this.buffers.draw(n);
        this.logStats(n, cubesWithGeometry);

        // off-departure hygiene (particle-engine discipline)
        RenderSystem.depthMask(true);
        RenderSystem.defaultBlendFunc();
        RenderSystem.disableBlend();
        GL20.glUseProgram(0);
        this.buffers.unbind();
    }

    /** 5 s-throttled pipeline stats — the visibility triage log: commands > 0
     * but nothing visible points at the shader/transform; commands == 0
     * points at the geometry pipeline (snapshot → mesher → arena). */
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
