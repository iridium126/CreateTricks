package com.iridium126.createmanaindustry.client.render;

import com.iridium126.createmanaindustry.compat.hexcasting.InlineTrickData;
import com.samsthenerd.inline.api.client.GlowHandling;
import com.samsthenerd.inline.api.client.InlineRenderer;

import dev.enjarai.trickster.render.CircleRenderer;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.network.chat.Style;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.phys.Vec3;

/**
 * Client-side Inline renderer for {@link InlineTrickData}: draws the full
 * Trickster spell circle tree in place of a text character.
 * <p>
 * Delegates directly to Trickster's own {@link CircleRenderer} (UI tooltip
 * mode), the exact same code path as Trickster's
 * {@code FragmentTooltipComponent}, so the result is pixel-identical to a
 * Trickster spell fragment tooltip — just scaled down to a 16px inline icon
 * (radius 8).
 */
public class InlineTrickRenderer implements InlineRenderer<InlineTrickData> {

    public static final InlineTrickRenderer INSTANCE = new InlineTrickRenderer();

    /** Icon diameter in text pixels. */
    public static final int ICON_SIZE = 16;
    public static final int CIRCLE_RADIUS = ICON_SIZE / 2;

    private InlineTrickRenderer() {}

    @Override
    public ResourceLocation getId() {
        return InlineTrickData.RENDERER_ID;
    }

    @Override
    public GlowHandling getGlowPreference(InlineTrickData forData) {
        // Like Hexcasting's pattern renderer: the outline pass is skipped in
        // render(), so no flattening needed.
        return new GlowHandling.None();
    }

    @Override
    public int render(InlineTrickData data, GuiGraphics drawContext, int index, Style style, int codepoint,
            TextRenderingContext trContext) {
        if (trContext.isGlowy())
            return charWidth(data, style, codepoint);

        var pose = drawContext.pose();
        pose.pushPose();
        // Center the 16px circle on the glyph cell (text rows are ~8px tall).
        pose.translate(0.0f, -0.5f, 0.0f);

        var renderer = new CircleRenderer(true, false, 4);
        renderer.renderCircle(pose, data.spell, CIRCLE_RADIUS, CIRCLE_RADIUS - 4, CIRCLE_RADIUS,
                0.0f, 0.0f, 1.0f, 1.0f, new Vec3(0, 0, -1), null);
        // Trickster renders into a shared static immediate — must flush it.
        CircleRenderer.VERTEX_CONSUMERS.endBatch();

        pose.popPose();
        return charWidth(data, style, codepoint);
    }

    @Override
    public int charWidth(InlineTrickData data, Style style, int codepoint) {
        return ICON_SIZE;
    }
}
