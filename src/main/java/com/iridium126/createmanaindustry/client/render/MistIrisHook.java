package com.iridium126.createmanaindustry.client.render;

import com.iridium126.createmanaindustry.CreateManaIndustry;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.VertexFormat;
import foundry.veil.api.client.render.VeilRenderSystem;
import foundry.veil.api.client.render.shader.program.ShaderProgram;
import net.minecraft.client.Minecraft;
import top.leonx.irisveil.IrisVeilCompat;
import top.leonx.irisveil.compat.veil.VeilCompatRegistry;

import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL13;
import org.lwjgl.opengl.GL30;

/**
 * Renders the mist volumetric shader inside the Iris gbuffer while a shader
 * pack is active.
 * <p>
 * The vanilla Veil post pipeline reads {@code minecraft:main} — but with Iris
 * the world renders into the gbuffer (colortex0) and the post output gets
 * overwritten by Iris's final composite. This hook draws the same full-screen
 * ray-march pass directly into colortex0 at the after-translucent point of
 * {@code LevelRenderer.renderLevel} (via iris-veil-compat's world render
 * hook), so the fog is sampled by the shaderpack's composite pass.
 * <p>
 * Reuses the vanilla {@code mist_volumetric} shader unchanged — the main
 * render target's depth texture holds the full solid world depth at the hook
 * point (iris never clears it), so the depth-based ray endpoint and the march
 * cutoff at the scene surface behave exactly like the vanilla post pipeline,
 * giving full occlusion of the fog behind solid geometry.
 * <p>
 * The samplers are bound manually (unit 0 = colortex0, unit 1 = main depth):
 * Veil's sampler-unit bookkeeping misassigns them under Iris (depth sampling
 * returned 0, and mixing Veil binds with manual ones corrupted the colour
 * sampler). Depth sampling parameters are set explicitly as well.
 * <p>
 * All references to iris-veil-compat classes are guarded by
 * {@link CreateManaIndustry#IRISVEIL_ACTIVE} — the classes are only resolved
 * when the mod is actually loaded.
 */
public final class MistIrisHook {

    private static final String HOOK_ID = "createmanaindustry:mist";
    /** Draw into colortex0, like the simulated end-sea compat hook. */
    private static final int[] DRAW_BUFFERS = {0};

    private static boolean registered;

    private MistIrisHook() {
    }

    /**
     * Registers the world render hook with iris-veil-compat. Safe to call
     * multiple times; no-op when iris-veil-compat is not loaded.
     */
    public static void init() {
        if (!CreateManaIndustry.IRISVEIL_ACTIVE || registered)
            return;
        registered = true;
        VeilCompatRegistry.registerWorldRenderHook(
                HOOK_ID, DRAW_BUFFERS,
                MistIrisHook::shouldRender,
                MistIrisHook::render);
    }

    /**
     * Whether the iris gbuffer path is currently taking over from the vanilla
     * Veil post pipeline. Consulted by {@link MistClientHandler#syncMistPipeline()}
     * to maintain the "post pipeline XOR iris hook" invariant.
     */
    public static boolean isActivePath() {
        if (!CreateManaIndustry.IRISVEIL_ACTIVE
                || !IrisVeilCompat.isShaderPackInUse()
                || !MistClientHandler.isMistActive())
            return false;
        ShaderProgram shader = getProgram();
        return shader != null && shader.isValid();
    }

    /**
     * Called every frame by iris-veil-compat before the framebuffer is bound.
     * Ticks the mist animations (the iris path is the only tick point while a
     * shader pack is active) and reconciles the vanilla post pipeline.
     */
    private static boolean shouldRender() {
        MistClientHandler.tickMist();
        MistClientHandler.syncMistPipeline();
        return isActivePath();
    }

    /**
     * Draws the mist pass into the iris gbuffer. The compat gbuffer framebuffer
     * (writing to colortex0) is already bound by iris-veil-compat; the main
     * render target is restored by the framework afterwards.
     */
    private static boolean render(Object camera, Object gameRenderer) {
        try {
            ShaderProgram shader = getProgram();
            if (shader == null)
                return false;

            // colortex0's current texture is attached to the bound framebuffer.
            // Querying it avoids the flipped-after-translucent ambiguity of
            // IrisCompat#getRenderTargets (the alt/main texture choice).
            int colortex0Id = GL30.glGetFramebufferAttachmentParameteri(
                    GL30.GL_FRAMEBUFFER, GL30.GL_COLOR_ATTACHMENT0,
                    GL30.GL_FRAMEBUFFER_ATTACHMENT_OBJECT_NAME);

            // Main RT depth — iris writes the full solid world depth here and
            // nothing clears it during the frame. Fetched fresh every frame
            // (the texture can be recreated on resize). The default sampling
            // parameters (NEAREST, CLAMP, no compare mode) are left untouched —
            // overriding them permanently would leak into the vanilla post
            // pipeline after a shaderpack switch.
            int depthId = Minecraft.getInstance().getMainRenderTarget().getDepthTextureId();

            // Mirror Veil's post pipeline state: replace-style blend since the
            // shader outputs the final color (occlusion is handled inside the
            // shader by the depth-based march cutoff, like the vanilla path).
            RenderSystem.enableBlend();
            RenderSystem.blendFuncSeparate(GL11.GL_ONE, GL11.GL_ZERO, GL11.GL_ONE, GL11.GL_ZERO);

            shader.bind();
            // Bind both samplers manually — Veil's sampler-unit bookkeeping can
            // misassign them under Iris (depth sampling returned 0 before, and
            // mixing it with manual binds corrupted the colour sampler), so pin
            // unit 0 = colortex0 and unit 1 = main depth explicitly.
            int col0Uniform = shader.getUniformLocation("DiffuseSampler0");
            int depthUniform = shader.getUniformLocation("DiffuseDepthSampler");
            RenderSystem.activeTexture(GL13.GL_TEXTURE0);
            GL11.glBindTexture(GL11.GL_TEXTURE_2D, colortex0Id);
            if (col0Uniform >= 0)
                GL30.glUniform1i(col0Uniform, 0);
            RenderSystem.activeTexture(GL13.GL_TEXTURE1);
            GL11.glBindTexture(GL11.GL_TEXTURE_2D, depthId);
            if (depthUniform >= 0)
                GL30.glUniform1i(depthUniform, 1);
            MistClientHandler.applyMistUniforms(shader);
            shader.setDefaultUniforms(VertexFormat.Mode.TRIANGLE_STRIP);
            VeilRenderSystem.drawScreenQuad();

            ShaderProgram.unbind();
            RenderSystem.disableBlend();
            RenderSystem.defaultBlendFunc();
            // Restore the conventional active texture unit — the manual binds
            // left it on unit 1, and render code generally assumes unit 0. The
            // texture bindings themselves are left alone: iris and the vanilla
            // pipeline rebind whatever they sample on every draw.
            RenderSystem.activeTexture(GL13.GL_TEXTURE0);
            return true;
        } catch (RuntimeException | LinkageError e) {
            CreateManaIndustry.LOGGER.warn("Iris mist hook render failed", e);
            return false;
        }
    }

    /** Fetches the mist shader program fresh each frame (survives shader reloads). */
    private static ShaderProgram getProgram() {
        return VeilRenderSystem.renderer().getShaderManager()
                .getShader(CreateManaIndustry.modLoc("mist_volumetric_iris"));
    }


}
