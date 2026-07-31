package com.iridium126.createmanaindustry.content.burner;

import java.util.Map;
import java.util.WeakHashMap;

import com.iridium126.createmanaindustry.CMIPartialModels;
import com.iridium126.createmanaindustry.CMISpriteShifts;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.mojang.math.Axis;
import com.simibubi.create.content.processing.burner.BlazeBurnerBlock;
import com.simibubi.create.foundation.blockEntity.renderer.SafeBlockEntityRenderer;

import net.createmod.catnip.animation.AnimationTickHolder;
import net.createmod.catnip.render.CachedBuffers;
import net.createmod.catnip.render.SpriteShiftEntry;
import net.createmod.catnip.render.SuperByteBuffer;
import net.minecraft.client.model.AllayModel;
import net.minecraft.client.model.geom.ModelLayers;
import net.minecraft.client.renderer.LightTexture;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.blockentity.BlockEntityRendererProvider;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.Mth;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;

/**
 * Renders the allay inside the burner cage using Minecraft's own
 * {@link AllayModel}, driven by {@link RenderedAllay} which replicates the
 * vanilla dance animation. While burning, a glowing flame base (Create's
 * {@code blaze_flame.obj} with UV-scrolling sprite) rises from the cage
 * bottom. Classic BER only — no Flywheel visual.
 */
public class AllayBurnerRenderer extends SafeBlockEntityRenderer<AllayBurnerBlockEntity> {

    private static final ResourceLocation ALLAY_TEXTURE =
        ResourceLocation.withDefaultNamespace("textures/entity/allay/allay.png");

    private final AllayModel allayModel;

    // One allay per burner block entity: the renderer instance is shared by
    // all burners, and each must keep its own dance counters and state
    // (WeakHashMap — entries are collected once the block entity is unloaded).
    private final Map<BlockEntity, RenderedAllay> allays = new WeakHashMap<>();

    public AllayBurnerRenderer(BlockEntityRendererProvider.Context context) {
        allayModel = new AllayModel(context.bakeLayer(ModelLayers.ALLAY));
    }

    @Override
    protected void renderSafe(AllayBurnerBlockEntity be, float partialTicks, PoseStack ms,
            MultiBufferSource bufferSource, int light, int overlay) {
        AllayBurnerBlock.HeatLevel heat = be.getHeatLevelFromBlock();
        if (heat == AllayBurnerBlock.HeatLevel.NONE)
            return;

        Level level = be.getLevel();

        // Per-burner dance state: syncs the dancing flag every frame (so an
        // extinguished burner stops dancing immediately) and advances the
        // vanilla counters at most once per client tick.
        int tick = (int) AnimationTickHolder.getTicks(level);
        RenderedAllay allay = allays.computeIfAbsent(be, b -> new RenderedAllay(level));
        allay.updateDance(heat == AllayBurnerBlock.HeatLevel.ALLAYHEATED, tick);

        float ageInTicks = AnimationTickHolder.getTicks(level) + partialTicks;

        ms.pushPose();
        // Anchor the vanilla entity render chain so the allay's feet land low
        // in the cage (blaze_cage.obj spans y 0..0.9375; the chain below puts
        // the feet ≈0.2 below and the head ≈0.53 above this anchor at 0.9 scale).
        ms.translate(0.5, 0.5, 0.5);
        ms.scale(0.9F, 0.9F, 0.9F);

        // Face the allay via the burner's headAngle (mirrors Create's blaze):
        // idle burners smoothly chase the nearest player, burning burners hold
        // FACING. The vanilla entity convention maps the angle as 180 - angle.
        ms.mulPose(Axis.YP.rotationDegrees(be.headAngle.getValue(partialTicks)));

        // Entity models render with a flipped Y axis — these two transforms
        // replicate LivingEntityRenderer.render verbatim (scale(-1,-1,1) then
        // translate(0,-1.501,0)); without them the model renders upside down.
        ms.scale(-1.0F, -1.0F, 1.0F);
        ms.translate(0, -1.501F, 0);

        allayModel.setupAnim(allay, 0, 0, ageInTicks, 0, 0);
        VertexConsumer allayBuffer = bufferSource.getBuffer(RenderType.entityTranslucent(ALLAY_TEXTURE));
        allayModel.renderToBuffer(ms, allayBuffer, LightTexture.FULL_BRIGHT, overlay, 0xFFFFFFFF);

        ms.popPose();

        // Rods and flame use absolute block coordinates (obj models) — render
        // them in their own transform chains, outside the allay's pose.
        if (heat == AllayBurnerBlock.HeatLevel.ALLAYHEATED) {
            renderRods(level, be, ms, bufferSource);
            renderFlame(level, be, ms, bufferSource);
        }
    }

    /**
     * The glowing rods rising from the cage, mirrors Create's burner rods at
     * SEETHING heat ({@code superheated_rods_small/large} with sin bobbing).
     */
    private void renderRods(Level level, AllayBurnerBlockEntity be, PoseStack ms,
            MultiBufferSource bufferSource) {
        float time = AnimationTickHolder.getRenderTime(level);
        float renderTick = time + (be.hashCode() % 13) * 16f;
        float offsetMult = 64;
        float offset1 = Mth.sin((float) ((renderTick / 16f + Math.PI) % (2 * Math.PI))) / offsetMult;
        float offset2 = Mth.sin((float) ((renderTick / 16f + Math.PI / 2) % (2 * Math.PI))) / offsetMult;
        // Create's headAnimation at its active value (0.175f * 1.0f).
        float animation = 0.175f;

        ms.pushPose();
        SuperByteBuffer rodsBuffer = CachedBuffers.partial(CMIPartialModels.ALLAY_BURNER_RODS_SMALL, be.getBlockState());
        rodsBuffer.translate(0, offset1 + animation + .125f, 0)
            .light(LightTexture.FULL_BRIGHT)
            .renderInto(ms, bufferSource.getBuffer(RenderType.solid()));

        SuperByteBuffer rodsBuffer2 = CachedBuffers.partial(CMIPartialModels.ALLAY_BURNER_RODS_LARGE, be.getBlockState());
        rodsBuffer2.translate(0, offset2 + animation - 3 / 16f, 0)
            .light(LightTexture.FULL_BRIGHT)
            .renderInto(ms, bufferSource.getBuffer(RenderType.solid()));
        ms.popPose();
    }

    /**
     * The glowing flame base, rendered like Create's burner flame:
     * {@code blaze_flame.obj} with UV scrolling driven by a sprite shift.
     */
    private void renderFlame(Level level, AllayBurnerBlockEntity be, PoseStack ms,
            MultiBufferSource bufferSource) {
        SpriteShiftEntry spriteShift = CMISpriteShifts.ALLAY_BURNER_FLAME;

        // Sprite shifts only resolve while the texture atlas reloads; if this
        // entry was registered afterwards (e.g. class loaded late), skip the
        // flame rather than crashing.
        if (spriteShift.getTarget() == null)
            return;

        float spriteWidth = spriteShift.getTarget()
            .getU1()
            - spriteShift.getTarget()
            .getU0();
        float spriteHeight = spriteShift.getTarget()
            .getV1()
            - spriteShift.getTarget()
            .getV0();

        float time = AnimationTickHolder.getRenderTime(level);
        // Matches Create's burner-flame speed at SEETHING heat, the heat level
        // this burner reports to basins (SEETHING.ordinal() = 4).
        float speed = 1 / 32f + 1 / 64f * BlazeBurnerBlock.HeatLevel.SEETHING.ordinal();

        double vScroll = speed * time;
        vScroll = vScroll - Math.floor(vScroll);
        vScroll = vScroll * spriteHeight / 2;

        double uScroll = speed * time / 2;
        uScroll = uScroll - Math.floor(uScroll);
        uScroll = uScroll * spriteWidth / 2;

        ms.pushPose();
        SuperByteBuffer flameBuffer = CachedBuffers.partial(CMIPartialModels.ALLAY_BURNER_FLAME, be.getBlockState());
        flameBuffer.shiftUVScrolling(spriteShift, (float) uScroll, (float) vScroll)
            .light(LightTexture.FULL_BRIGHT)
            .renderInto(ms, bufferSource.getBuffer(RenderType.cutoutMipped()));
        ms.popPose();
    }
}
