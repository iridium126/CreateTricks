package com.iridium126.createmanaindustry.compat.jei.category.animations;

import javax.annotation.Nullable;

import com.iridium126.createmanaindustry.CMIBlocks;
import com.iridium126.createmanaindustry.CMIPartialModels;
import com.iridium126.createmanaindustry.CMISpriteShifts;
import com.iridium126.createmanaindustry.content.burner.AllayBurnerBlock;
import com.iridium126.createmanaindustry.content.burner.RenderedAllay;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.mojang.math.Axis;
import com.simibubi.create.compat.jei.category.animations.AnimatedKinetics;
import com.simibubi.create.content.processing.burner.BlazeBurnerBlock;

import net.createmod.catnip.animation.AnimationTickHolder;
import net.createmod.catnip.render.CachedBuffers;
import net.createmod.catnip.render.SpriteShiftEntry;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.model.AllayModel;
import net.minecraft.client.model.geom.ModelLayers;
import net.minecraft.client.renderer.LightTexture;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.Mth;
import net.minecraft.world.level.Level;

/**
 * JEI animation of the Allay Burner, mirroring Create's {@code AnimatedBlazeBurner}.
 * <p>
 * The burner is always in its heating state here (the only heat level an active
 * allay burner reports to basins), so unlike the blaze variant there is no
 * {@code withHeat}: the cage, dancing allay, bobbing rods and UV-scrolling
 * flame all come from {@code AllayBurnerRenderer}'s in-world look.
 * <p>
 * The allay (a vanilla entity model, not a baked block model) is rendered
 * straight into {@code graphics.bufferSource()} using the exact transform chain
 * of {@code AllayBurnerRenderer.renderSafe}, anchored to the cage via the same
 * {@code atLocal/scale/flipForGuiRender} mapping {@code GuiGameElement} applies
 * to the cage block.
 */
public class AnimatedAllayBurner extends AnimatedKinetics {

    private static final ResourceLocation ALLAY_TEXTURE =
        ResourceLocation.withDefaultNamespace("textures/entity/allay/allay.png");

    // Shared across burners and JEI frames: vanilla uses one AllayModel for every
    // allay, so a single lazily-baked instance is safe (mirrors AllayBurnerRenderer).
    @Nullable
    private static AllayModel allayModel;

    // Dance state for the JEI allay. Drawn frames are sequential on the client
    // thread, so a single cached instance per animation object is enough.
    @Nullable
    private RenderedAllay renderedAllay;

    public AnimatedAllayBurner() {}

    public void draw(GuiGraphics graphics, int xOffset, int yOffset) {
        PoseStack matrixStack = graphics.pose();
        matrixStack.pushPose();
        matrixStack.translate(xOffset, yOffset, 200);
        matrixStack.mulPose(Axis.XP.rotationDegrees(-15.5f));
        matrixStack.mulPose(Axis.YP.rotationDegrees(22.5f));
        int scale = 23;

        Level level = Minecraft.getInstance().level;
        float time = level == null ? 0 : AnimationTickHolder.getRenderTime(level);

        // Cage — same atLocal anchor as AnimatedBlazeBurner uses for the blaze.
        blockElement(CMIBlocks.ALLAY_BURNER.getDefaultState()
            .setValue(AllayBurnerBlock.HEAT_LEVEL, AllayBurnerBlock.HeatLevel.ALLAYHEATED))
            .atLocal(0, 1.65, 0)
            .scale(scale)
            .render(graphics);

        // Bobbing rods — mirrors AnimatedBlazeBurner's rod animation verbatim
        // (single-sin bob, same placement and 180° yaw); only the partial model
        // differs, carrying the allay burner texture.
        float offset = (Mth.sin(AnimationTickHolder.getRenderTime() / 16f) + 0.5f) / 16f;

        blockElement(CMIPartialModels.ALLAY_BURNER_RODS_LARGE)
            .atLocal(1, 1.7 + offset, 1)
            .rotate(0, 180, 0)
            .scale(scale)
            .render(graphics);

        // Dancing allay inside the cage. The frame below maps world-block space
        // to the GUI exactly like GuiGameElement maps the cage block
        // (scale -> atLocal -> flipForGuiRender), then the renderSafe chain runs
        // verbatim inside it. Skips when no client level is loaded.
        if (level != null) {
            matrixStack.pushPose();
            matrixStack.scale(scale, scale, scale);
            matrixStack.translate(0, 1.65, 0);
            matrixStack.scale(1, -1, 1);

            matrixStack.translate(0.5, 0.5, 0.5);
            matrixStack.translate(0, -0.2F, 0);
            matrixStack.scale(0.9F, 0.9F, 0.9F);
            // Facing the JEI camera: the model's default facing is rotated 180°
            // around the vertical axis so the allay looks toward the viewer.
            matrixStack.mulPose(Axis.YP.rotationDegrees(180));
            matrixStack.scale(-1.0F, -1.0F, 1.0F);
            matrixStack.translate(0, -1.501F, 0);

            RenderedAllay allay = getRenderedAllay();
            int tick = (int) AnimationTickHolder.getTicks(level);
            allay.updateDance(true, tick);
            float ageInTicks = AnimationTickHolder.getTicks(level) + AnimationTickHolder.getPartialTicks(level);

            AllayModel model = getAllayModel();
            model.setupAnim(allay, 0, 0, ageInTicks, 0, 0);
            VertexConsumer allayBuffer = graphics.bufferSource().getBuffer(RenderType.entityTranslucent(ALLAY_TEXTURE));
            model.renderToBuffer(matrixStack, allayBuffer, LightTexture.FULL_BRIGHT, OverlayTexture.NO_OVERLAY,
                0xFFFFFFFF);
            matrixStack.popPose();
        }

        // Glowing flame base — the same scaled frame and UV-scroll math as
        // AnimatedBlazeBurner's flame, driven by the allay burner sprite shift
        // at SEETHING speed (the heat level this burner reports to basins).
        matrixStack.pushPose();
        matrixStack.scale(scale, -scale, scale);
        matrixStack.translate(0, -1.8, 0);
        SpriteShiftEntry spriteShift = CMISpriteShifts.ALLAY_BURNER_FLAME;
        if (spriteShift.getTarget() != null) {
            float spriteWidth = spriteShift.getTarget().getU1() - spriteShift.getTarget().getU0();
            float spriteHeight = spriteShift.getTarget().getV1() - spriteShift.getTarget().getV0();

            float speed = 1 / 32f + 1 / 64f * BlazeBurnerBlock.HeatLevel.SEETHING.ordinal();

            double vScroll = speed * time;
            vScroll = vScroll - Math.floor(vScroll);
            vScroll = vScroll * spriteHeight / 2;

            double uScroll = speed * time / 2;
            uScroll = uScroll - Math.floor(uScroll);
            uScroll = uScroll * spriteWidth / 2;

            CachedBuffers.partial(CMIPartialModels.ALLAY_BURNER_FLAME, CMIBlocks.ALLAY_BURNER.getDefaultState())
                .shiftUVScrolling(spriteShift, (float) uScroll, (float) vScroll)
                .light(LightTexture.FULL_BRIGHT)
                .renderInto(matrixStack, graphics.bufferSource().getBuffer(RenderType.cutoutMipped()));
        }
        matrixStack.popPose();

        matrixStack.popPose();
    }

    private RenderedAllay getRenderedAllay() {
        RenderedAllay allay = renderedAllay;
        if (allay == null)
            allay = renderedAllay = new RenderedAllay(Minecraft.getInstance().level);
        return allay;
    }

    private static AllayModel getAllayModel() {
        AllayModel model = allayModel;
        if (model == null)
            model = allayModel = new AllayModel(Minecraft.getInstance().getEntityModels().bakeLayer(ModelLayers.ALLAY));
        return model;
    }
}
