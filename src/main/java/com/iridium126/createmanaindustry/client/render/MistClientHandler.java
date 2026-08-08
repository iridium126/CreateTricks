package com.iridium126.createmanaindustry.client.render;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import com.iridium126.createmanaindustry.CreateManaIndustry;
import com.iridium126.createmanaindustry.CMIFluids;
import com.iridium126.createmanaindustry.config.Config;
import com.iridium126.createmanaindustry.content.fluids.mist.MistSync;
import com.mojang.blaze3d.platform.NativeImage;
import com.mojang.math.Axis;

import foundry.veil.api.client.render.VeilRenderSystem;
import foundry.veil.api.client.render.post.PostPipeline;
import foundry.veil.api.client.render.shader.program.UniformAccess;
import foundry.veil.api.event.VeilRenderLevelStageEvent;
import foundry.veil.platform.VeilEventPlatform;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.inventory.InventoryMenu;
import net.minecraft.world.level.material.Fluid;
import net.minecraft.world.level.material.Fluids;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.client.extensions.common.IClientFluidTypeExtensions;
import net.neoforged.neoforge.fluids.FluidStack;

import org.joml.Matrix4f;
import org.joml.Vector3f;

/**
 * Client-side handler that collects active Kinetic Atomizer positions and
 * passes them as uniforms to the Veil mist volumetric shader.
 * <p>
 * Active atomizers are tracked via a {@link ConcurrentHashMap} populated by
 * {@link #setActive(BlockPos, FluidStack)} which should be called from the
 * atomizer block entity's client-side sync handler.
 * <p>
 * Call {@link #init()} once during client setup to register the Veil
 * post-processing listener.
 */
public final class MistClientHandler {

    private static final ResourceLocation PIPELINE_ID = CreateManaIndustry.modLoc("mist");
    private static final int MAX_ATOMIZERS = 32;
    /** Radius units per render frame during appear/disappear/change transitions. */
    private static final float RADIUS_LERP_SPEED = 0.5f;
    /** Below this radius a source is considered vanished and not mergeable. */
    private static final float MIN_MERGE_RADIUS = 0.01f;
    /** Absorption multiplier when the camera is outside every mist volume. */
    private static final float OUTSIDE_ABSORPTION = 0.25f;
    /** Mist concentration at which absorption reaches its full value. */
    private static final float FULL_ABSORPTION_CONC = 0.5f;

    /** Per-source client data with animation state for smooth radius transitions. */
    private static final class MistSourceData {
        final FluidStack fluid;
        float displayRadius;   // current rendered radius, lerps toward target each frame
        float targetRadius;    // desired radius from server
        boolean fading;        // true = target is 0, remove when display reaches 0

        MistSourceData(FluidStack fluid, int radius) {
            this.fluid = fluid;
            this.targetRadius = radius;
            this.displayRadius = 0f; // start at 0 for fade-in animation
            this.fading = false;
        }

        /*boolean isAnimating() {
            return Math.abs(displayRadius - targetRadius) > 0.01f;
        }*/
    }

    /** Client-side registry of active atomizer positions and their per-source data. */
    private static final Map<BlockPos, MistSourceData> activeSources = new ConcurrentHashMap<>();

    // Per-source layout: x, y, z, invRadiusSq, colorIndex, absorptionScale (6 floats).
    // invRadiusSq (0 for vanished sources) lets the shader test d2 * invR2 <= 1
    // with a multiply instead of a division in the hot loop.
    private static final float[] atomizerData = new float[MAX_ATOMIZERS * 6];
    // One RGBA entry per distinct fluid present among the packed sources:
    // r, g, b plus the glow emission (light level derived, mistGlowStrength scaled).
    private static final float[] paletteData = new float[MAX_ATOMIZERS * 4];
    private static int atomizerCount = 0;
    private static int paletteCount = 0;
    private static boolean initialized = false;
    private static boolean dirty = true;
    private static boolean pipelineActive = false;

    /** Cache of extracted fluid texture colors, keyed by still texture ResourceLocation. */
    private static final Map<ResourceLocation, float[]> fluidColorCache = new HashMap<>();

    private MistClientHandler() {}

    /**
     * Registers the Veil post-processing listener. Safe to call multiple times.
     * Must be called on the client after Veil has initialized.
     */
    public static void init() {
        if (initialized)
            return;
        initialized = true;

        // Bridge: register for client sync notifications from atomizer BEs
        // and other mist sources (e.g. timed recipe byproducts).
        MistSync.registerSyncCallback(data ->
                MistClientHandler.setActive(data.pos(), data.fluid(), data.radius()));

        // Listen for Veil post-processing to inject uniforms
        VeilEventPlatform.INSTANCE.preVeilPostProcessing(MistClientHandler::onPrePostProcessing);

        // Iris shaderpack path: render mist into the iris gbuffer via the
        // iris-veil-compat world render hook, and reconcile the vanilla post
        // pipeline every frame so the two paths never run simultaneously.
        // AFTER_LEVEL fires every frame even when Iris has taken over the
        // world rendering, covering the gap where the hook is silent.
        if (CreateManaIndustry.IRISVEIL_ACTIVE) {
            MistIrisHook.init();
            VeilEventPlatform.INSTANCE.onVeilRenderLevelStage((stage, levelRenderer, bufferSource,
                    matrixStack, frustumMatrix, projectionMatrix, renderTick, deltaTracker, camera, frustum) -> {
                if (stage == VeilRenderLevelStageEvent.Stage.AFTER_LEVEL)
                    syncMistPipeline();
            });
        }
    }

    /**
     * Called by the atomizer BE when its active state is synced to the client.
     * An empty FluidStack starts a fade-out; the source is removed when the
     * displayed radius reaches zero.
     */
    public static void setActive(BlockPos pos, FluidStack fluid, int radius) {
        if (fluid.isEmpty()) {
            // Start fade-out instead of immediate removal
            MistSourceData existing = activeSources.get(pos);
            if (existing != null) {
                existing.targetRadius = 0f;
                existing.fading = true;
            }
        } else {
            MistSourceData existing = activeSources.get(pos);
            if (existing != null) {
                // Update target — display radius lerps to new value
                existing.targetRadius = radius;
                existing.fading = false;
            } else {
                // New source — display starts at 0, lerps to target (fade-in)
                activeSources.put(pos.immutable(), new MistSourceData(fluid, radius));
            }
        }
        dirty = true;

        // Pipeline activation is reconciled in syncMistPipeline() (invariant:
        // post pipeline active iff mist present AND the iris path is not on).
        // Dispatch to the render thread: in single-player the integrated server
        // thread can reach this via MistSync's shared callback list, and Veil
        // must only be touched from the render thread.
        if (Minecraft.getInstance().isSameThread())
            syncMistPipeline();
        else
            Minecraft.getInstance().execute(MistClientHandler::syncMistPipeline);
    }

    /**
     * Clears all client mist sources. Called when the client's level/dimension
     * changes so sources from a previous dimension don't linger at the same
     * absolute coordinates (the map is keyed by BlockPos only).
     */
    public static void clearAll() {
        activeSources.clear();
        dirty = true;
        atomizerCount = 0;
        paletteCount = 0;
        pipelineActive = false;
        syncMistPipeline();
    }

    // --- animation ------------------------------------------------------------

    /**
     * Advances all radius animations by one frame. Removes fully-faded sources
     * and cleans up the pipeline when no sources remain.
     * <p>
     * Called every render frame from {@link #onPrePostProcessing}.
     *
     * @return true while any source is still animating (triggers a repack)
     */
    private static boolean updateAnimations() {
        boolean anyAnimating = false;
        var it = activeSources.entrySet().iterator();
        while (it.hasNext()) {
            var entry = it.next();
            MistSourceData data = entry.getValue();

            // Lerp display toward target
            float diff = data.targetRadius - data.displayRadius;
            if (Math.abs(diff) <= 0.01f) {
                data.displayRadius = data.targetRadius;
            } else {
                float step = Math.signum(diff) * Math.min(RADIUS_LERP_SPEED, Math.abs(diff));
                data.displayRadius += step;
                anyAnimating = true;
            }

            // Remove fully-faded sources (fade-out completed)
            if (data.fading && data.displayRadius <= 0.01f) {
                it.remove();
            }
        }

        // Pipeline cleanup is reconciled in syncMistPipeline()
        syncMistPipeline();

        return anyAnimating;
    }

    // --- Veil event callbacks ---

    private static void onPrePostProcessing(ResourceLocation name, PostPipeline pipeline,
            PostPipeline.Context context) {
        if (!PIPELINE_ID.equals(name))
            return;

        tickMist();
        applyMistUniforms(pipeline);
    }

    /**
     * Advances animations, repacks data if needed and refreshes absorption.
     * Called exactly once per render frame by whichever render path is active
     * (the Veil post-processing event, or the iris gbuffer hook).
     */
    static void tickMist() {
        boolean animating = updateAnimations();
        if (dirty || animating) {
            packAtomizerData();
            dirty = false;
        }
        // Camera-dependent per-source absorption — refresh every frame
        updateAbsorptionScales();
    }

    /** True while any mist source is present (fade-outs included). */
    static boolean isMistActive() {
        return !activeSources.isEmpty();
    }

    /**
     * Reconciles the Veil post pipeline against the current render path.
     * Invariant: the post pipeline is active iff mist is present AND the iris
     * shaderpack hook is not taking over. Called from {@link #setActive},
     * {@link #updateAnimations()}, the AFTER_LEVEL stage event and the iris
     * hook's shouldRender — all idempotent.
     */
    static void syncMistPipeline() {
        boolean want = isMistActive() && !MistIrisHook.isActivePath();
        if (want == pipelineActive)
            return;
        pipelineActive = want;
        var manager = VeilRenderSystem.renderer().getPostProcessingManager();
        if (want)
            manager.add(PIPELINE_ID);
        else
            manager.remove(PIPELINE_ID);
    }

    /**
     * Injects all mist uniforms into the given shader program or post pipeline
     * (both implement {@link UniformAccess}). Shared by the vanilla Veil post
     * path and the iris gbuffer hook.
     */
    static void applyMistUniforms(UniformAccess shader) {
        var countUniform = shader.getUniform("AtomizerCount");
        if (countUniform != null)
            countUniform.setInt(atomizerCount);

        var dataUniform = shader.getUniform("AtomizerData");
        if (dataUniform != null)
            dataUniform.setFloats(atomizerData);

        var paletteUniform = shader.getUniform("MistPalette");
        if (paletteUniform != null)
            paletteUniform.setFloats(paletteData);

        var paletteCountUniform = shader.getUniform("PaletteCount");
        if (paletteCountUniform != null)
            paletteCountUniform.setInt(paletteCount);

        var opacityUniform = shader.getUniform("MistOpacity");
        if (opacityUniform != null)
            opacityUniform.setFloat(0.4f);

        var densityUniform = shader.getUniform("MistDensity");
        if (densityUniform != null)
            densityUniform.setFloat(0.25f);

        var stepUniform = shader.getUniform("MistStepScale");
        if (stepUniform != null)
            stepUniform.setFloat(0.8f);

        var sunUniform = shader.getUniform("SunDirection");
        if (sunUniform != null) {
            if (Minecraft.getInstance().level != null) {
                Vector3f sunDir = getSunDirectionWorld();
                sunUniform.setVector(sunDir.x, sunDir.y, sunDir.z, 0.0f);
            } else {
                sunUniform.setVector(-0.7f, 0.5f, 0.3f, 0.0f);
            }
        }
    }

    /**
     * World-space unit vector toward the sun, mirroring the vanilla
     * {@code renderSky} / iris {@code CelestialUniforms} celestial rotation so the
     * mist beams point at the same sun as Photon's volumetric fog:
     * {@code sunDir = YP(-90°) · ZP(sunPathRotation) · XP(getTimeOfDay·360°) · (0,1,0)}.
     * {@code getTimeOfDay()} is the smoothed day cycle where 0 = noon (sun overhead);
     * {@code sunPathRotation} comes from the active shaderpack (Photon ships -35),
     * falling back to -35 when iris is absent.
     */
    static Vector3f getSunDirectionWorld() {
        Minecraft mc = Minecraft.getInstance();
        float skyAngle = mc.level.getTimeOfDay(mc.getTimer().getGameTimeDeltaTicks());
        Matrix4f celestial = new Matrix4f();
        celestial.rotate(Axis.YP.rotationDegrees(-90.0F));
        celestial.rotate(Axis.ZP.rotationDegrees(IrisShadowTextures.getSunPathRotation()));
        celestial.rotate(Axis.XP.rotationDegrees(skyAngle * 360.0F));
        return celestial.transformDirection(0.0F, 1.0F, 0.0F, new Vector3f()).normalize();
    }

    private static void packAtomizerData() {
        // Collect sources in a deterministic order (largest radius first).
        List<SourceEntry> sources = new ArrayList<>();
        for (var entry : activeSources.entrySet()) {
            MistSourceData data = entry.getValue();
            BlockPos pos = entry.getKey();
            sources.add(new SourceEntry(pos.getX() + 0.5f, pos.getY() + 0.5f, pos.getZ() + 0.5f,
                    data.displayRadius, data.fluid, data.fading, data.targetRadius));
        }
        sources.sort((a, b) -> Float.compare(b.radius, a.radius));

        // Merge adjacent same-fluid sources only when over the slot budget.
        List<PackedSource> packed = new ArrayList<>();
        if (sources.size() > MAX_ATOMIZERS) {
            packed = mergeSources(sources);
            // Deterministic fallback: if merging cannot fit everything, keep the
            // largest-radius sources instead of dropping arbitrary ones.
            if (packed.size() > MAX_ATOMIZERS) {
                packed.sort((a, b) -> Float.compare(b.radius, a.radius));
                packed = packed.subList(0, MAX_ATOMIZERS);
            }
        } else {
            for (SourceEntry s : sources)
                packed.add(s.asPacked());
        }

        // Build the color palette (one entry per distinct fluid) and pack.
        Map<Fluid, Integer> paletteIndex = new HashMap<>();
        paletteCount = 0;
        int count = 0;
        for (PackedSource s : packed) {
            int idx = paletteIndex.computeIfAbsent(s.fluid.getFluid(), fluid -> {
                float[] color = getCachedFluidColor(s.fluid);
                int i = paletteCount;
                paletteData[i * 4] = color[0];
                paletteData[i * 4 + 1] = color[1];
                paletteData[i * 4 + 2] = color[2];
                // Glow emission mirrors the fluid's own light level (mana/soul 15,
                // media 10, water 0), scaled by the global config multiplier.
                int lightLevel = fluid.getFluidType().getLightLevel(new FluidStack(fluid, 1));
                paletteData[i * 4 + 3] = (float) Math.min(1.0, lightLevel / 15.0)
                        * (float) Config.mistGlowStrength;
                paletteCount++;
                return i;
            });
            int base = count * 6;
            atomizerData[base] = s.x;
            atomizerData[base + 1] = s.y;
            atomizerData[base + 2] = s.z;
            atomizerData[base + 3] = s.radius <= MIN_MERGE_RADIUS ? 0f : 1f / (s.radius * s.radius);
            atomizerData[base + 4] = idx;
            atomizerData[base + 5] = 1.0f; // overwritten every frame by updateAbsorptionScales
            count++;
        }
        atomizerCount = count;
    }

    /**
     * Updates each packed source's own absorption scale from the camera's
     * position relative to that source, continuously in the source's
     * concentration at the camera (same packed data the shader renders):
     * {@link #OUTSIDE_ABSORPTION} at the rim, ramping up linearly to full
     * absorption at {@link #FULL_ABSORPTION_CONC}. Called every frame — the
     * camera moves even when the sources do not.
     */
    private static void updateAbsorptionScales() {
        Vec3 cam = Minecraft.getInstance().gameRenderer.getMainCamera().getPosition();
        for (int i = 0; i < atomizerCount; i++) {
            int base = i * 6;
            float dx = atomizerData[base] - (float) cam.x;
            float dy = atomizerData[base + 1] - (float) cam.y;
            float dz = atomizerData[base + 2] - (float) cam.z;
            float invR2 = atomizerData[base + 3];
            float conc = 0.0f;
            if (invR2 > 0.0f) {
                float r = (float) (1.0 / Math.sqrt(invR2));
                float d = (float) Math.sqrt(dx * dx + dy * dy + dz * dz);
                if (d <= r)
                    conc = 1.0f - d / r;
            }
            float t = Math.min(1.0f, conc / FULL_ABSORPTION_CONC);
            atomizerData[base + 5] = OUTSIDE_ABSORPTION + (1.0f - OUTSIDE_ABSORPTION) * t;
        }
    }

    /**
     * Merges adjacent same-fluid sources when the active count exceeds the slot
     * budget, so no source silently vanishes. Render-only (the server-side
     * field is untouched) and stateless per frame.
     * <p>
     * Fading sources are never merged — they must keep their shrinking radius
     * so fade-outs stay smooth. A pair (a, b) of the same fluid merges when the
     * centers are close ({@code d <= 0.5 * min(r)} — small visual error: halo
     * up to ~19% extra area, peak dip up to 20%) or when one disk fully
     * contains the other (support-exact). The merged source sits at the
     * midpoint with radius {@code max(rA, rB) + d / 2}, which covers both
     * original supports. The pair with the smallest {@code d / min(r)} error is
     * merged first; merging stops when the budget is met or no valid pair
     * remains.
     */
    private static List<PackedSource> mergeSources(List<SourceEntry> sources) {
        List<PackedSource> result = new ArrayList<>();
        List<PackedSource> pool = new ArrayList<>();
        for (SourceEntry s : sources) {
            if (s.fading || s.targetRadius <= MIN_MERGE_RADIUS)
                result.add(s.asPacked());
            else
                pool.add(s.asPacked());
        }

        while (result.size() + pool.size() > MAX_ATOMIZERS) {
            int bestA = -1;
            int bestB = -1;
            float bestScore = Float.MAX_VALUE;
            for (int i = 0; i < pool.size(); i++) {
                for (int j = i + 1; j < pool.size(); j++) {
                    PackedSource a = pool.get(i);
                    PackedSource b = pool.get(j);
                    if (a.fluid.getFluid() != b.fluid.getFluid())
                        continue;
                    float d = (float) Math.sqrt(sq(a.x - b.x) + sq(a.y - b.y) + sq(a.z - b.z));
                    float minR = Math.min(a.radius, b.radius);
                    if (d <= 0.5f * minR || d + minR <= Math.max(a.radius, b.radius)) {
                        float score = d / minR;
                        if (score < bestScore) {
                            bestScore = score;
                            bestA = i;
                            bestB = j;
                        }
                    }
                }
            }
            if (bestA < 0)
                break; // no mergeable pair remains — the budget cannot be met

            PackedSource a = pool.remove(bestB); // remove the higher index first
            PackedSource b = pool.remove(bestA);
            float d = (float) Math.sqrt(sq(a.x - b.x) + sq(a.y - b.y) + sq(a.z - b.z));
            float cx = (a.x + b.x) * 0.5f;
            float cy = (a.y + b.y) * 0.5f;
            float cz = (a.z + b.z) * 0.5f;
            float radius = Math.max(a.radius, b.radius) + d * 0.5f;
            pool.add(new PackedSource(cx, cy, cz, radius, a.fluid));
        }

        result.addAll(pool);
        return result;
    }

    private static float sq(float v) {
        return v * v;
    }

    /** A live source snapshot for packing. */
    private static final class SourceEntry {
        final float x, y, z;
        final float radius;
        final FluidStack fluid;
        final boolean fading;
        final float targetRadius;

        SourceEntry(float x, float y, float z, float radius, FluidStack fluid, boolean fading, float targetRadius) {
            this.x = x;
            this.y = y;
            this.z = z;
            this.radius = radius;
            this.fluid = fluid;
            this.fading = fading;
            this.targetRadius = targetRadius;
        }

        PackedSource asPacked() {
            return new PackedSource(x, y, z, radius, fluid);
        }
    }

    /** A source ready for the uniform arrays: a single source or a merged cluster. */
    private static final class PackedSource {
        final float x, y, z;
        final float radius;
        final FluidStack fluid;

        PackedSource(float x, float y, float z, float radius, FluidStack fluid) {
            this.x = x;
            this.y = y;
            this.z = z;
            this.radius = radius;
            this.fluid = fluid;
        }
    }

    // --- Fluid color extraction ---

    /**
     * Returns the cached RGB color (float[3], values 0..1) for a fluid. Manual
     * mappings for common fluids; falls back to extracting dominant color from the
     * fluid's still texture.
     * <p>
     * Must be called on the render thread.
     */
    private static float[] getCachedFluidColor(FluidStack stack) {
        Fluid fluid = stack.getFluid();

        // --- Manual mappings ---
        if (fluid == Fluids.WATER || fluid == Fluids.FLOWING_WATER)
            return new float[]{1.0f, 1.0f, 1.0f};

        if (fluid == CMIFluids.LIQUID_MANA.get() || fluid == CMIFluids.LIQUID_MANA.getSource())
            return new float[]{0.39215686f, 0.98431373f, 1.0f}; // light blue

        if (fluid == CMIFluids.LIQUID_MEDIA.get() || fluid == CMIFluids.LIQUID_MEDIA.getSource())
            return new float[]{0.76862745f, 0.61960784f, 0.95294118f}; // light purple

        if (fluid == CMIFluids.LIQUID_SOUL.get() || fluid == CMIFluids.LIQUID_SOUL.getSource())
            return new float[]{0.35f, 0.55f, 1.0f}; // soul blue

        // --- Texture-based extraction ---
        ResourceLocation texLoc = IClientFluidTypeExtensions.of(fluid).getStillTexture(stack);
        if (texLoc == null)
            return new float[]{1.0f, 1.0f, 1.0f};

        return fluidColorCache.computeIfAbsent(texLoc, MistClientHandler::extractColorFromTexture);
    }

    /**
     * Extracts the dominant color from a block atlas sprite texture using sparse
     * sampling. Skips mostly transparent pixels. Falls back to white on failure.
     * <p>
     * Performance: step=4 on a 16x16 texture = ~16 pixel reads. Result is cached
     * per texture location for the lifetime of the session.
     */
    private static float[] extractColorFromTexture(ResourceLocation texLoc) {
        TextureAtlasSprite sprite = Minecraft.getInstance()
                .getTextureAtlas(InventoryMenu.BLOCK_ATLAS)
                .apply(texLoc);
        NativeImage image = sprite.contents().getOriginalImage();

        if (image == null)
            return new float[]{1.0f, 1.0f, 1.0f};

        int w = image.getWidth();
        int h = image.getHeight();
        int sampleStep = Math.max(1, Math.min(w, h) / 4); // sample every ~4th pixel

        double rSum = 0, gSum = 0, bSum = 0;
        int count = 0;

        for (int y = 0; y < h; y += sampleStep) {
            for (int x = 0; x < w; x += sampleStep) {
                int rgba = image.getPixelRGBA(x, y);
                int a = (rgba >> 24) & 0xFF;
                if (a < 128)
                    continue; // skip mostly transparent pixels
                rSum += (rgba >> 16) & 0xFF;
                gSum += (rgba >> 8) & 0xFF;
                bSum += rgba & 0xFF;
                count++;
            }
        }

        if (count == 0)
            return new float[]{1.0f, 1.0f, 1.0f};

        return new float[]{
            (float) (rSum / count / 255.0),
            (float) (gSum / count / 255.0),
            (float) (bSum / count / 255.0)
        };
    }
}
