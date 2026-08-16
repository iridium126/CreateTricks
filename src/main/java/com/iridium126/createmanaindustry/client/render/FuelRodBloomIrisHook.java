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
 * Draws the fuel rod bloom shader inside the Iris gbuffer while a shader pack
 * is active (mirrors {@code MistIrisHook}, without the shadow/tyndall parts).
 * <p>
 * With Iris the world renders into the gbuffer (colortex0) and Veil's post
 * pipelines are not composited, so this hook draws the same full-screen glow
 * pass directly into colortex0 at the world render hook point of
 * iris-veil-compat; the shaderpack's own composite (and bloom, if it has one)
 * then processes the added glow.
 * <p>
 * The samplers are bound manually (unit 0 = colortex0, unit 1 = main depth),
 * the same way {@code MistIrisHook} does — Veil's sampler-unit bookkeeping
 * misassigns them under Iris. All references to iris-veil-compat classes are
 * guarded by {@link CreateManaIndustry#IRISVEIL_ACTIVE}.
 */
public final class FuelRodBloomIrisHook {

    private static final String HOOK_ID = "createmanaindustry:fuel_rod_glow";
    /** Draw into colortex0, like the mist hook. */
    private static final int[] DRAW_BUFFERS = {0};

    private static boolean registered;

    private FuelRodBloomIrisHook() {
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
                FuelRodBloomIrisHook::shouldRender,
                FuelRodBloomIrisHook::render);
    }

    /**
     * Whether the iris gbuffer path is currently taking over from the vanilla
     * Veil post pipeline. Consulted by
     * {@link FuelRodBloomHandler#syncGlowPipeline()} to maintain the "post
     * pipeline XOR iris hook" invariant.
     */
    public static boolean isActivePath() {
        if (!CreateManaIndustry.IRISVEIL_ACTIVE
                || !IrisVeilCompat.isShaderPackInUse()
                || !FuelRodBloomHandler.isGlowActive())
            return false;
        ShaderProgram shader = getProgram();
        return shader != null && shader.isValid();
    }

    /**
     * Called every frame by iris-veil-compat before the framebuffer is bound.
     * Ticks the rod animations (the iris path is the only tick point while a
     * shader pack is active) and reconciles the vanilla post pipeline.
     */
    private static boolean shouldRender() {
        FuelRodBloomHandler.tickGlow();
        FuelRodBloomHandler.syncGlowPipeline();
        return isActivePath();
    }

    /**
     * Draws the glow pass into the iris gbuffer. The compat gbuffer framebuffer
     * (writing to colortex0) is already bound by iris-veil-compat; the main
     * render target is restored by the framework afterwards.
     */
    private static boolean render(Object camera, Object gameRenderer) {
        try {
            ShaderProgram shader = getProgram();
            if (shader == null)
                return false;

            // colortex0's current texture is attached to the bound framebuffer.
            int colortex0Id = GL30.glGetFramebufferAttachmentParameteri(
                    GL30.GL_FRAMEBUFFER, GL30.GL_COLOR_ATTACHMENT0,
                    GL30.GL_FRAMEBUFFER_ATTACHMENT_OBJECT_NAME);

            // Main RT depth — iris writes the full solid world depth here and
            // nothing clears it during the frame.
            int depthId = Minecraft.getInstance().getMainRenderTarget().getDepthTextureId();

            // Replace-style blend since the shader outputs the final color
            // (occlusion is handled inside the shader by the depth-based fade).
            RenderSystem.enableBlend();
            RenderSystem.blendFuncSeparate(GL11.GL_ONE, GL11.GL_ZERO, GL11.GL_ONE, GL11.GL_ZERO);

            shader.bind();
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

            FuelRodBloomHandler.applyGlowUniforms(shader);

            shader.setDefaultUniforms(VertexFormat.Mode.TRIANGLE_STRIP);
            VeilRenderSystem.drawScreenQuad();

            ShaderProgram.unbind();
            RenderSystem.disableBlend();
            RenderSystem.defaultBlendFunc();
            // Restore the conventional active texture unit (the manual binds left
            // it on unit 1).
            RenderSystem.activeTexture(GL13.GL_TEXTURE0);
            return true;
        } catch (RuntimeException | LinkageError e) {
            CreateManaIndustry.LOGGER.warn("Iris fuel rod glow hook render failed", e);
            return false;
        }
    }

    /** Fetches the glow shader program fresh each frame (survives shader reloads). */
    private static ShaderProgram getProgram() {
        return VeilRenderSystem.renderer().getShaderManager()
                .getShader(CreateManaIndustry.modLoc("fuel_rod_glow_iris"));
    }
}
