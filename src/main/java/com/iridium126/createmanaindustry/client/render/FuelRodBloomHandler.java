package com.iridium126.createmanaindustry.client.render;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import com.iridium126.createmanaindustry.CreateManaIndustry;
import com.iridium126.createmanaindustry.config.ClientConfig;
import com.iridium126.createmanaindustry.content.fluids.fueltank.FuelRodStructure;
import com.iridium126.createmanaindustry.content.fluids.fueltank.FuelRodSync;

import foundry.veil.api.client.render.VeilRenderSystem;
import foundry.veil.api.client.render.post.PostPipeline;
import foundry.veil.api.client.render.shader.program.UniformAccess;
import foundry.veil.api.event.VeilRenderLevelStageEvent;
import foundry.veil.platform.VeilEventPlatform;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceLocation;

/**
 * Client-side handler that collects formed fuel rods (from the synced
 * {@link FuelRodStructure.RodData}) and passes them as uniforms to the Veil
 * fuel rod glow shader (mirrors {@code MistClientHandler}).
 * <p>
 * Rods are tracked via a {@link ConcurrentHashMap} keyed by the bottom-centre
 * tank position, populated by {@link #onRodSync} through the
 * {@link FuelRodSync} bridge (block entities emit on client-side
 * {@code read} of their rod state). Each rod fades in on formation and fades
 * out on breakage. Call {@link #init()} once during client setup to register
 * the Veil post-processing listener.
 * <p>
 * The glow is composed of two parts, both fed to the shader:
 * <ul>
 * <li><b>Window glow</b> — only the *visible* fuel tank windows of the rod:
 * each layer's outer-boundary cells' outward-facing side windows (the 8x8
 * panes of the tank model), plus every cell's top pane that is not covered by
 * a tank layer above it (upper layers may be smaller, exposing lower tops).
 * Windows are packed as axis-aligned quads into {@link #WINDOW_DATA}.
 * <li><b>Top ring</b> — a pulsing ring 0.5 blocks below the rod's top: its
 * radius diffuses from {@code maxRadius} to {@code 2 * maxRadius} over a 3s
 * cycle while the tube widens {@code 0 -> maxRadius / 10} and the brightness
 * breathes on a sine (rise then decay). Each rod's phase is offset by its
 * position, so nearby rods pulse out of sync. The ring's color
 * ({@code fuelRodBloomRingColor}) and strength are independent of the window
 * glow; the animation clock is injected per frame as {@code RingTime}.
 * </ul>
 * The opaque vertical cylinder of the original glow was removed.
 * <p>
 * The post pipeline is active iff at least one rod is present (fade-outs
 * included) AND the iris gbuffer path is not taking over — see
 * {@link #syncGlowPipeline()}.
 */
public final class FuelRodBloomHandler {

    private static final ResourceLocation PIPELINE_ID = CreateManaIndustry.modLoc("fuel_rod_glow");
    private static final int MAX_RODS = 32;
    /** Per-rod uniform stride: x, y, z, maxRadius, height, intensity, windowStart, windowCount. */
    private static final int ROD_STRIDE = 8;
    /** Maximum window quads (4 floats each) across all rods; larger rods are filled first. */
    private static final int MAX_WINDOWS = 128;
    /** Intensity added per frame during fade in/out. */
    private static final float FADE_SPEED = 0.08f;
    /** Below this intensity a rod is considered fully faded and removable. */
    private static final float MIN_INTENSITY = 0.01f;
    /** Coarse shader test radius = maxRadius + this; rays beyond it skip the rod's windows. */
    private static final float WINDOW_BAND = 1.0f;

    // Window pane geometry, mirroring assets/.../models/block/molten_salt_fuel_tank/block.json:
    // side panes span 4..12/16 on the face (half extent 0.25), the top pane spans 2..14/16
    // (half extent 0.375); every pane is inset 0.95/16 from its outer edge.
    private static final float FACE_NEAR = 0.5f / 16f; // Avoid z-fighting
    private static final float FACE_FAR = 15.5f / 16f;

    // Window orientation types (must match the shader's axisFromType).
    private static final float TYPE_PX = 0f; // normal +X
    private static final float TYPE_NX = 1f; // normal -X
    private static final float TYPE_NZ = 2f; // normal -Z
    private static final float TYPE_PZ = 3f; // normal +Z
    private static final float TYPE_TOP = 4f; // normal +Y

    /** Per-rod client data with animation state for smooth intensity transitions. */
    private static final class RodSourceData {
        final BlockPos center;
        final int[] radii; // per layer, index = y offset from center.getY()
        final float maxRadius;
        final float height;
        float displayIntensity;
        boolean fading; // true = the rod broke; remove when the intensity reaches 0

        RodSourceData(FuelRodStructure.RodData rod) {
            this.center = rod.center;
            this.radii = rod.radii.clone();
            this.maxRadius = rod.maxRadius;
            this.height = rod.height;
            this.displayIntensity = 0f; // start at 0 for fade-in
            this.fading = false;
        }
    }

    /** Client-side registry of formed rods keyed by bottom-centre position. */
    private static final Map<BlockPos, RodSourceData> rods = new ConcurrentHashMap<>();

    // Per-rod layout: x, y, z, maxRadius, height, intensity, windowStart, windowCount (8 floats), max 32.
    private static final float[] rodData = new float[MAX_RODS * ROD_STRIDE];
    // Per-window layout: cx, cy, cz, type (4 floats), max 128.
    private static final float[] windowData = new float[MAX_WINDOWS * 4];
    private static int rodCount = 0;
    private static int windowCount = 0;
    private static boolean initialized = false;
    private static boolean dirty = true;
    private static boolean pipelineActive = false;

    private FuelRodBloomHandler() {
    }

    /**
     * Registers the Veil post-processing listener and the rod sync bridge.
     * Safe to call multiple times; must be called on the client after Veil has
     * initialized.
     */
    public static void init() {
        if (initialized)
            return;
        initialized = true;

        FuelRodSync.registerSyncCallback(FuelRodBloomHandler::onRodSync);

        VeilEventPlatform.INSTANCE.preVeilPostProcessing(FuelRodBloomHandler::onPrePostProcessing);

        // Iris shaderpack path: draw the same glow into the iris gbuffer via the
        // iris-veil-compat world render hook (see FuelRodBloomIrisHook).
        if (CreateManaIndustry.IRISVEIL_ACTIVE) {
            FuelRodBloomIrisHook.init();
            // Reconcile the vanilla post pipeline every frame even while iris has
            // taken over the world rendering (the AFTER_LEVEL stage fires on both
            // paths): toggling a shaderpack off stops the iris hook, and without
            // this per-frame reconciliation the pipeline would stay removed until
            // the next rod state change (mirrors MistClientHandler).
            VeilEventPlatform.INSTANCE.onVeilRenderLevelStage((stage, levelRenderer, bufferSource,
                    matrixStack, frustumMatrix, projectionMatrix, renderTick, deltaTracker, camera, frustum) -> {
                if (stage == VeilRenderLevelStageEvent.Stage.AFTER_LEVEL)
                    syncGlowPipeline();
            });
        }
    }

    /**
     * Called by the fuel tank block entity's client-side sync whenever its rod
     * state changed. A null rod starts a fade-out; the source is removed when
     * the displayed intensity reaches zero.
     */
    static void onRodSync(FuelRodSync.RodSyncData data) {
        RodSourceData existing = rods.get(data.center());
        if (data.rod() == null) {
            // Start fade-out instead of immediate removal
            if (existing != null)
                existing.fading = true;
        } else if (existing != null && Arrays.equals(existing.radii, data.rod().radii)) {
            // Re-formed with the same shape — keep the animation continuous
            existing.fading = false;
        } else {
            rods.put(data.center().immutable(), new RodSourceData(data.rod()));
        }
        dirty = true;

        // Pipeline activation is reconciled in syncGlowPipeline(). Dispatch to the
        // render thread: in single-player the integrated server thread can reach
        // this via the shared callback list, and Veil must only be touched from
        // the render thread.
        dispatchPipelineSync();
    }

    /**
     * Clears all client rods. Called when the client's level/dimension changes
     * so rods from a previous dimension don't linger at the same absolute
     * coordinates (the map is keyed by BlockPos only).
     */
    public static void clearAll() {
        rods.clear();
        dirty = true;
        rodCount = 0;
        windowCount = 0;
        pipelineActive = false;
        syncGlowPipeline();
    }

    // --- animation ------------------------------------------------------------

    /**
     * Advances all rod intensity animations by one frame, removes fully-faded
     * rods and reconciles the pipeline. Called every render frame from the
     * vanilla post-processing event or the iris gbuffer hook.
     */
    static void tickGlow() {
        boolean animating = updateAnimations();
        if (dirty || animating) {
            packRodData();
            dirty = false;
        }
    }

    private static boolean updateAnimations() {
        boolean anyAnimating = false;
        var it = rods.entrySet().iterator();
        while (it.hasNext()) {
            var entry = it.next();
            RodSourceData data = entry.getValue();

            float target = data.fading ? 0f : 1f;
            float diff = target - data.displayIntensity;
            if (Math.abs(diff) <= MIN_INTENSITY) {
                data.displayIntensity = target;
            } else {
                data.displayIntensity += Math.signum(diff) * Math.min(FADE_SPEED, Math.abs(diff));
                anyAnimating = true;
            }

            // Remove fully-faded sources (fade-out completed)
            if (data.fading && data.displayIntensity <= MIN_INTENSITY)
                it.remove();
        }
        syncGlowPipeline();
        return anyAnimating;
    }

    /** True while any rod is present (fade-outs included). */
    static boolean isGlowActive() {
        return !rods.isEmpty();
    }

    /**
     * Reconciles the Veil post pipeline against the current render path.
     * Invariant: the post pipeline is active iff rods are present AND the iris
     * shaderpack hook is not taking over. Called from {@link #onRodSync},
     * {@link #updateAnimations()} and the iris hook's shouldRender — all
     * idempotent.
     */
    static void syncGlowPipeline() {
        boolean want = isGlowActive() && !FuelRodBloomIrisHook.isActivePath();
        if (want == pipelineActive)
            return;
        pipelineActive = want;
        var manager = VeilRenderSystem.renderer().getPostProcessingManager();
        if (want)
            manager.add(PIPELINE_ID);
        else
            manager.remove(PIPELINE_ID);
    }

    private static void dispatchPipelineSync() {
        if (Minecraft.getInstance().isSameThread())
            syncGlowPipeline();
        else
            Minecraft.getInstance().execute(FuelRodBloomHandler::syncGlowPipeline);
    }

    // --- Veil event callback --------------------------------------------------

    private static void onPrePostProcessing(ResourceLocation name, PostPipeline pipeline,
            PostPipeline.Context context) {
        if (!PIPELINE_ID.equals(name))
            return;
        tickGlow();
        applyGlowUniforms(pipeline);
    }

    /**
     * Injects all rod glow uniforms into the given shader program or post
     * pipeline (both implement {@link UniformAccess}). Shared by the vanilla
     * Veil post path and the iris gbuffer hook.
     */
    static void applyGlowUniforms(UniformAccess shader) {
        var countUniform = shader.getUniform("RodCount");
        if (countUniform != null)
            countUniform.setInt(rodCount);

        var dataUniform = shader.getUniform("RodData");
        if (dataUniform != null)
            dataUniform.setFloats(rodData);

        var windowUniform = shader.getUniform("WindowData");
        if (windowUniform != null)
            windowUniform.setFloats(windowData);

        var colorUniform = shader.getUniform("GlowColor");
        if (colorUniform != null) {
            int c = ClientConfig.fuelRodBloomColor;
            colorUniform.setVector(((c >> 16) & 0xFF) / 255.0f, ((c >> 8) & 0xFF) / 255.0f, (c & 0xFF) / 255.0f);
        }

        var strengthUniform = shader.getUniform("GlowStrength");
        if (strengthUniform != null)
            strengthUniform.setFloat((float) ClientConfig.fuelRodBloomStrength);

        var ringUniform = shader.getUniform("RingStrength");
        if (ringUniform != null)
            ringUniform.setFloat((float) ClientConfig.fuelRodBloomRingStrength);

        var ringColorUniform = shader.getUniform("RingColor");
        if (ringColorUniform != null) {
            int c = ClientConfig.fuelRodBloomRingColor;
            ringColorUniform.setVector(((c >> 16) & 0xFF) / 255.0f, ((c >> 8) & 0xFF) / 255.0f, (c & 0xFF) / 255.0f);
        }

        var ringTimeUniform = shader.getUniform("RingTime");
        if (ringTimeUniform != null)
            ringTimeUniform.setFloat(getRingTimeSeconds());
    }

    /**
     * Game time in seconds (tick + partial tick) driving the ring pulse
     * animation; 0 when no level is loaded (no rods can exist then anyway).
     * Thread note: only ever called from the render thread (Veil post listener
     * or iris hook), so the timer reads are race-free.
     */
    private static float getRingTimeSeconds() {
        ClientLevel level = Minecraft.getInstance().level;
        if (level == null)
            return 0f;
        float partialTick = Minecraft.getInstance().getTimer().getGameTimeDeltaPartialTick(false);
        return (level.getGameTime() + partialTick) / 20.0f;
    }

    // --- packing --------------------------------------------------------------

    /**
     * Packs the active rods into the uniform arrays in a deterministic order
     * (largest rod first), capped at {@link #MAX_RODS}. The axis is the rod's
     * bottom-centre block column: x/z at the block centre, y at the bottom
     * block's floor, spanning {@code height} blocks upward.
     * <p>
     * Each rod references a slice of the global window array, filled
     * largest-rod-first up to {@link #MAX_WINDOWS}; rods (or tails of rods)
     * beyond the window budget simply get {@code windowCount = 0} and only
     * render their top ring.
     */
    private static void packRodData() {
        List<RodSourceData> list = new ArrayList<>(rods.values());
        list.sort((a, b) -> Float.compare(b.maxRadius * b.height, a.maxRadius * a.height));
        int count = Math.min(list.size(), MAX_RODS);
        int cursor = 0;
        for (int i = 0; i < count; i++) {
            RodSourceData d = list.get(i);
            int base = i * ROD_STRIDE;
            rodData[base] = d.center.getX() + 0.5f;
            rodData[base + 1] = d.center.getY();
            rodData[base + 2] = d.center.getZ() + 0.5f;
            rodData[base + 3] = d.maxRadius;
            rodData[base + 4] = d.height;
            rodData[base + 5] = d.displayIntensity;
            rodData[base + 6] = cursor;

            int remaining = MAX_WINDOWS - cursor;
            if (remaining <= 0) {
                rodData[base + 7] = 0;
                continue;
            }
            List<float[]> quads = new ArrayList<>();
            collectWindows(d, quads);
            int take = Math.min(quads.size(), remaining);
            for (int w = 0; w < take; w++) {
                float[] quad = quads.get(w);
                int wb = (cursor + w) * 4;
                windowData[wb] = quad[0];
                windowData[wb + 1] = quad[1];
                windowData[wb + 2] = quad[2];
                windowData[wb + 3] = quad[3];
            }
            rodData[base + 7] = take;
            cursor += take;
        }
        rodCount = count;
        windowCount = cursor;
    }

    /**
     * Derives the *visible* window quads of one rod: for every layer, the
     * outward-facing side windows of the boundary cells (a cell on the diamond
     * perimeter at Manhattan distance {@code r - 2} — a face whose neighbour lies
     * outside the diamond carries a window), plus the top pane of every cell that
     * is not covered by a tank of the layer directly above it (upper layers may
     * be smaller; cells at the outline ring sit under glass, which still shows
     * the glow through it).
     * <p>
     * Window planes mirror the block model: side panes lie 0.95/16 inside the
     * outer face at mid height; the top pane lies 0.95/16 below the top at the
     * cell centre.
     */
    private static void collectWindows(RodSourceData d, List<float[]> out) {
        int[] radii = d.radii;
        int h = radii.length;
        for (int ly = 0; ly < h; ly++) {
            int r = radii[ly];
            int bound = r - 2; // tank cells at |dx|+|dz| ≤ bound; the perimeter is |dx|+|dz| == bound
            int yBase = d.center.getY() + ly;

            // --- side windows (outward faces of perimeter cells) ---
            for (int dx = -bound; dx <= bound; dx++) {
                for (int dz = -bound; dz <= bound; dz++) {
                    if (Math.abs(dx) + Math.abs(dz) != bound)
                        continue;
                    float cx = d.center.getX() + dx;
                    float cz = d.center.getZ() + dz;
                    if (Math.abs(dx + 1) + Math.abs(dz) > bound)
                        out.add(new float[] { cx + FACE_FAR, yBase + 0.5f, cz + 0.5f, TYPE_PX });
                    if (Math.abs(dx - 1) + Math.abs(dz) > bound)
                        out.add(new float[] { cx + FACE_NEAR, yBase + 0.5f, cz + 0.5f, TYPE_NX });
                    if (Math.abs(dx) + Math.abs(dz + 1) > bound)
                        out.add(new float[] { cx + 0.5f, yBase + 0.5f, cz + FACE_FAR, TYPE_PZ });
                    if (Math.abs(dx) + Math.abs(dz - 1) > bound)
                        out.add(new float[] { cx + 0.5f, yBase + 0.5f, cz + FACE_NEAR, TYPE_NZ });
                }
            }

            // --- top windows (cells not covered by the layer above) ---
            int covered = ly + 1 < h ? Math.max(radii[ly + 1] - 2, -1) : -1;
            for (int dx = -bound; dx <= bound; dx++) {
                for (int dz = -bound; dz <= bound; dz++) {
                    int md = Math.abs(dx) + Math.abs(dz);
                    if (md > bound || md <= covered)
                        continue;
                    out.add(new float[] { d.center.getX() + dx + 0.5f, yBase + FACE_FAR,
                            d.center.getZ() + dz + 0.5f, TYPE_TOP });
                }
            }
        }
    }
}