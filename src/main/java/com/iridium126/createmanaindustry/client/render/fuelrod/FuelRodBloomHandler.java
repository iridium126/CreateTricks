package com.iridium126.createmanaindustry.client.render.fuelrod;

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
import com.iridium126.createmanaindustry.client.render.mist.MistClientHandler;

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
 * The glow renders the <b>top ring</b>: a pulsing ring 0.5 blocks below the
 * rod's top whose radius diffuses from {@code maxRadius} to
 * {@code 2 * maxRadius} over a 3s cycle while the tube widens
 * {@code 0 -> maxRadius / 10} and the brightness breathes on a sine (rise then
 * decay). Each rod's phase is offset by its position, so nearby rods pulse out
 * of sync. The ring's colour is a fixed dual-tone palette (hot pink-white core
 * cooling to purple-red, cold cyan outer edge — the molten rose quartz vs
 * liquid soul colour conflict), its strength is configured
 * ({@code fuelRodBloomRingStrength}); the animation clock is injected per
 * frame as {@code RingTime}. The window pane glow was removed.
 * <p>
 * The post pipeline is active iff at least one rod is present (fade-outs
 * included) AND the iris gbuffer path is not taking over — see
 * {@link #syncGlowPipeline()}.
 */
public final class FuelRodBloomHandler {

    private static final ResourceLocation PIPELINE_ID = CreateManaIndustry.modLoc("fuel_rod_glow");
    private static final int MAX_RODS = 32;
    /** Per-rod uniform stride: x, y, z, maxRadius, height, intensity. */
    private static final int ROD_STRIDE = 6;
    /** Intensity added per frame during fade in/out. */
    private static final float FADE_SPEED = 0.08f;
    /** Below this intensity a rod is considered fully faded and removable. */
    private static final float MIN_INTENSITY = 0.01f;

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

    // Per-rod layout: x, y, z, maxRadius, height, intensity (6 floats), max 32.
    private static final float[] rodData = new float[MAX_RODS * ROD_STRIDE];
    private static int rodCount = 0;
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

        var ringUniform = shader.getUniform("RingStrength");
        if (ringUniform != null)
            ringUniform.setFloat((float) ClientConfig.fuelRodBloomRingStrength);

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
     * Packs the active rods into the uniform array in a deterministic order
     * (largest rod first), capped at {@link #MAX_RODS}. The axis is the rod's
     * bottom-centre block column: x/z at the block centre, y at the bottom
     * block's floor, spanning {@code height} blocks upward.
     */
    private static void packRodData() {
        List<RodSourceData> list = new ArrayList<>(rods.values());
        list.sort((a, b) -> Float.compare(b.maxRadius * b.height, a.maxRadius * a.height));
        int count = Math.min(list.size(), MAX_RODS);
        for (int i = 0; i < count; i++) {
            RodSourceData d = list.get(i);
            int base = i * ROD_STRIDE;
            rodData[base] = d.center.getX() + 0.5f;
            rodData[base + 1] = d.center.getY();
            rodData[base + 2] = d.center.getZ() + 0.5f;
            rodData[base + 3] = d.maxRadius;
            rodData[base + 4] = d.height;
            rodData[base + 5] = d.displayIntensity;
        }
        rodCount = count;
    }
}