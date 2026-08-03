package com.iridium126.createmanaindustry.client.render;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import com.iridium126.createmanaindustry.CreateManaIndustry;
import com.iridium126.createmanaindustry.CMIFluids;
import com.iridium126.createmanaindustry.content.fluids.mist.MistEmitter;
import com.mojang.blaze3d.platform.NativeImage;

import foundry.veil.api.client.render.VeilRenderSystem;
import foundry.veil.api.client.render.post.PostPipeline;
import foundry.veil.platform.VeilEventPlatform;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.inventory.InventoryMenu;
import net.minecraft.world.level.material.Fluid;
import net.minecraft.world.level.material.Fluids;
import net.neoforged.neoforge.client.extensions.common.IClientFluidTypeExtensions;
import net.neoforged.neoforge.fluids.FluidStack;

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
public final class ClientMistHandler {

    private static final ResourceLocation PIPELINE_ID = CreateManaIndustry.modLoc("mist");
    private static final int MAX_ATOMIZERS = 16;
    /** Radius units per render frame during appear/disappear/change transitions. */
    private static final float RADIUS_LERP_SPEED = 0.5f;

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

    private static final float[] atomizerData = new float[MAX_ATOMIZERS * 4];
    private static final float[] atomizerColorData = new float[MAX_ATOMIZERS * 3];
    private static int atomizerCount = 0;
    private static boolean initialized = false;
    private static boolean dirty = true;
    private static boolean pipelineActive = false;

    /** Cache of extracted fluid texture colors, keyed by still texture ResourceLocation. */
    private static final Map<ResourceLocation, float[]> fluidColorCache = new HashMap<>();

    private ClientMistHandler() {}

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
        MistEmitter.registerSyncCallback(data ->
                ClientMistHandler.setActive(data.pos(), data.fluid(), data.radius()));

        // Listen for Veil post-processing to inject uniforms
        VeilEventPlatform.INSTANCE.preVeilPostProcessing(ClientMistHandler::onPrePostProcessing);
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

        // Add pipeline immediately on first source; removal is deferred to updateAnimations()
        if (!activeSources.isEmpty() && !pipelineActive) {
            pipelineActive = true;
            VeilRenderSystem.renderer().getPostProcessingManager().add(PIPELINE_ID);
        }
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

        // Clean up pipeline when all sources are gone (fade-outs finished)
        if (activeSources.isEmpty() && pipelineActive) {
            pipelineActive = false;
            VeilRenderSystem.renderer().getPostProcessingManager().remove(PIPELINE_ID);
        }

        return anyAnimating;
    }

    // --- Veil event callbacks ---

    private static void onPrePostProcessing(ResourceLocation name, PostPipeline pipeline,
            PostPipeline.Context context) {
        if (!PIPELINE_ID.equals(name))
            return;

        // Always tick animations; repack if dirty or animating
        boolean animating = updateAnimations();
        if (dirty || animating) {
            packAtomizerData();
            dirty = false;
        }

        var countUniform = pipeline.getUniform("AtomizerCount");
        if (countUniform != null)
            countUniform.setInt(atomizerCount);

        var dataUniform = pipeline.getUniform("AtomizerData");
        if (dataUniform != null)
            dataUniform.setFloats(atomizerData);

        var colorDataUniform = pipeline.getUniform("AtomizerColors");
        if (colorDataUniform != null)
            colorDataUniform.setFloats(atomizerColorData);

        var opacityUniform = pipeline.getUniform("MistOpacity");
        if (opacityUniform != null)
            opacityUniform.setFloat(0.4f);

        var densityUniform = pipeline.getUniform("MistDensity");
        if (densityUniform != null)
            densityUniform.setFloat(0.25f);

        var stepUniform = pipeline.getUniform("MistStepScale");
        if (stepUniform != null)
            stepUniform.setFloat(0.8f);

        var sunUniform = pipeline.getUniform("SunDirection");
        if (sunUniform != null) {
            Minecraft mc = Minecraft.getInstance();
            if (mc.level != null) {
                float sunAngle = mc.level.getSunAngle(mc.getTimer().getGameTimeDeltaTicks());
                sunUniform.setVector((float) -Math.cos(sunAngle), (float) (Math.sin(sunAngle) * 0.3), 0.0f, 0.0f);
            } else {
                sunUniform.setVector(-0.7f, 0.5f, 0.3f, 0.0f);
            }
        }
    }

    private static void packAtomizerData() {
        int count = 0;
        for (var entry : activeSources.entrySet()) {
            if (count >= MAX_ATOMIZERS)
                break;
            BlockPos pos = entry.getKey();
            MistSourceData data = entry.getValue();

            float[] color = getCachedFluidColor(data.fluid);

            // Position + radius
            int base = count * 4;
            atomizerData[base] = pos.getX() + 0.5f;
            atomizerData[base + 1] = pos.getY() + 0.5f;
            atomizerData[base + 2] = pos.getZ() + 0.5f;
            atomizerData[base + 3] = data.displayRadius;

            // Color (r, g, b)
            int cBase = count * 3;
            atomizerColorData[cBase] = color[0];
            atomizerColorData[cBase + 1] = color[1];
            atomizerColorData[cBase + 2] = color[2];

            count++;
        }
        atomizerCount = count;
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

        return fluidColorCache.computeIfAbsent(texLoc, ClientMistHandler::extractColorFromTexture);
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
