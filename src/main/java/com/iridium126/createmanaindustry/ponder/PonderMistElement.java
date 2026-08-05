package com.iridium126.createmanaindustry.ponder;

import com.mojang.blaze3d.platform.NativeImage;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.mojang.blaze3d.vertex.VertexFormat;

import net.createmod.ponder.api.element.PonderSceneElement;
import net.createmod.ponder.api.level.PonderLevel;
import net.createmod.ponder.foundation.PonderScene;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderStateShard;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.Mth;
import net.minecraft.world.phys.Vec3;

import org.joml.Matrix3f;
import org.joml.Matrix4f;
import org.joml.Vector3f;
import org.joml.Vector4f;

/**
 * A Ponder scene element that renders a billboarded mist volume around a
 * position, used by the Allay Burner mist scene. The real Veil post-processing
 * mist can never render inside a Ponder viewport — the scene is drawn in the
 * GUI phase after every post pass, with its own camera and orthographic
 * projection — so the mist is drawn as soft billboard sprites in the scene's
 * own coordinate space.
 */
public class PonderMistElement implements PonderSceneElement {

    /** Runtime-generated soft radial sprite — the vanilla particle textures are
     *  8x8 micro sprites and unusable as mist blobs. */
    private static final ResourceLocation MIST_TEXTURE =
            ResourceLocation.fromNamespaceAndPath("createmanaindustry", "textures/misc/ponder_mist");
    /** Radius lerp speed per tick (blocks). */
    private static final float LERP_SPEED = 0.5f;
    /** Golden angle (radians) — spreads Fibonacci-sphere sprites evenly. */
    private static final double GOLDEN_ANGLE = 2.399963229728653;

    // The scene's own translucent-block shader (the text shader applies GUI fog
    // and a discard threshold, which cuts each quad into a diagonal triangle).
    private static final RenderType MIST_RENDER_TYPE = RenderType.create(
        "cmi_ponder_mist",
        DefaultVertexFormat.POSITION_COLOR_TEX_LIGHTMAP,
        VertexFormat.Mode.QUADS,
        256,
        false,
        false,
        RenderType.CompositeState.builder()
            .setShaderState(RenderStateShard.RENDERTYPE_TRANSLUCENT_SHADER)
            .setTextureState(new RenderStateShard.TextureStateShard(MIST_TEXTURE, false, false))
            .setTransparencyState(RenderStateShard.TRANSLUCENT_TRANSPARENCY)
            .setDepthTestState(RenderStateShard.LEQUAL_DEPTH_TEST)
            .setCullState(RenderStateShard.NO_CULL)
            .setWriteMaskState(RenderStateShard.COLOR_WRITE)
            .createCompositeState(false));

    private static boolean textureReady = false;

    /** Generates and registers the soft radial mist sprite (render thread). */
    private static void ensureTexture() {
        if (textureReady)
            return;
        textureReady = true;
        NativeImage image = new NativeImage(64, 64, true);
        for (int y = 0; y < 64; y++) {
            for (int x = 0; x < 64; x++) {
                double dx = (x + 0.5 - 32) / 32;
                double dy = (y + 0.5 - 32) / 32;
                double d = Math.sqrt(dx * dx + dy * dy);
                float a = (float) Math.pow(Mth.clamp(1 - (float) (d * d), 0, 1), 2);
                image.setPixelRGBA(x, y, ((int) (a * 255) << 24) | 0xFFFFFF); // white, tinted by vertex color
            }
        }
        Minecraft.getInstance().getTextureManager().register(MIST_TEXTURE, new DynamicTexture(image));
    }

    /** Liquid Soul tint, RGB (0.35, 0.55, 1.0). */
    private static final int MIST_RGB = 0x598CFF;

    private final BlockPos center;
    private final float targetRadius;
    private final float alpha;
    private float displayRadius = 0f;
    private boolean active = false;
    private boolean visible = true;

    public PonderMistElement(BlockPos center, float radius, float alpha) {
        this.center = center;
        this.targetRadius = radius;
        this.alpha = alpha;
    }

    /** Toggles the mist fade-in/out. Called by scene instructions. */
    public void setActive(boolean active) {
        this.active = active;
    }

    @Override
    public void tick(PonderScene scene) {
        float target = active ? targetRadius : 0f;
        float diff = target - displayRadius;
        if (Math.abs(diff) <= 0.01f) {
            displayRadius = target;
        } else {
            displayRadius += Math.signum(diff) * Math.min(LERP_SPEED, Math.abs(diff));
        }
    }

    @Override
    public boolean isVisible() {
        return visible && displayRadius > 0.01f;
    }

    @Override
    public void setVisible(boolean visible) {
        this.visible = visible;
    }

    @Override
    public void renderFirst(PonderLevel world, MultiBufferSource buffer, GuiGraphics graphics, float pt) {}

    @Override
    public void renderLayer(PonderLevel world, MultiBufferSource buffer, RenderType type, GuiGraphics graphics,
            float pt) {}

    @Override
    public void renderLast(PonderLevel world, MultiBufferSource buffer, GuiGraphics graphics, float pt) {
        if (displayRadius <= 0.01f)
            return;
        ensureTexture();

        PoseStack pose = graphics.pose();
        // The scene renders in the GUI phase with an identity modelview — the
        // blocks land correctly because SuperByteBuffer bakes the pose (scene
        // transform) into its vertices. Raw world-space vertices must be
        // transformed by the full pose matrix before being written.
        Matrix4f transform = pose.last().pose();
        // The pose's normal matrix is the world->screen rotation (plus uniform
        // scale); its transpose maps screen offsets back to world offsets, so
        // the sprites always face the scene camera.
        Matrix3f normal = pose.last().normal();
        normal.transpose();

        float r = displayRadius;
        Vec3 mistCenter = Vec3.atCenterOf(center).add(0, 1, 0);
        float fade = Math.min(1f, r / targetRadius);

        VertexConsumer consumer = buffer.getBuffer(MIST_RENDER_TYPE);
        // Three overlapping layers (shell -> inner -> core, drawn last so the
        // centre accumulates density) — the sprites are much larger than their
        // spacing, so the soft radial blobs blend into a cohesive volume
        // instead of reading as individual gradient squares.
        drawLayer(consumer, transform, normal, mistCenter, r, 12, 0.55f, 1.5f, 0.55f, 0.0, fade);
        drawLayer(consumer, transform, normal, mistCenter, r, 6, 0.28f, 1.1f, 0.7f, 1.3, fade);
        drawSprite(consumer, transform, normal, mistCenter, new Vector3f(), r * 2.0f,
            packColor(alpha * 0.5f * fade));
    }

    /**
     * Draws {@code count} billboard sprites on a Fibonacci sphere of radius
     * {@code r * shellFrac} (deterministic across replays), each of size
     * {@code r * sizeFrac} and opacity {@code alpha * alphaFrac * fade}.
     */
    private void drawLayer(VertexConsumer consumer, Matrix4f transform, Matrix3f normal,
            Vec3 center, float r, int count, float shellFrac, float sizeFrac, float alphaFrac,
            double thetaOffset, float fade) {
        int color = packColor(alpha * alphaFrac * fade);
        float quadSize = r * sizeFrac;
        for (int i = 0; i < count; i++) {
            double y = 1 - 2d * (i + 0.5) / count;
            double radiusAt = Math.sqrt(1 - y * y);
            double theta = i * GOLDEN_ANGLE + thetaOffset;
            Vector3f offset = new Vector3f(
                (float) (radiusAt * Math.cos(theta) * r * shellFrac),
                (float) (y * r * shellFrac),
                (float) (radiusAt * Math.sin(theta) * r * shellFrac));
            drawSprite(consumer, transform, normal, center, offset, quadSize, color);
        }
    }

    private static void drawSprite(VertexConsumer consumer, Matrix4f transform, Matrix3f normal,
            Vec3 center, Vector3f offset, float size, int color) {
        float half = size / 2f;
        Vector3f corner = new Vector3f();
        Vector4f vertex = new Vector4f();
        for (int i = 0; i < 4; i++) {
            float u = (i & 1) == 0 ? -half : half;
            float v = (i & 2) == 0 ? -half : half;
            corner.set(u, v, 0);
            normal.transform(corner);
            vertex.set((float) (center.x + offset.x + corner.x),
                (float) (center.y + offset.y + corner.y),
                (float) (center.z + offset.z + corner.z), 1f);
            transform.transform(vertex);
            consumer.addVertex(vertex.x, vertex.y, vertex.z)
                .setColor(color)
                .setUv((i & 1) == 0 ? 0f : 1f, (i & 2) == 0 ? 0f : 1f)
                .setUv2(0xF0, 0xF0);
        }
    }

    private static int packColor(float alpha) {
        int a = (int) (Mth.clamp(alpha, 0, 1) * 255);
        return (a << 24) | MIST_RGB;
    }
}
