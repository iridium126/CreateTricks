package com.iridium126.createmanaindustry.compat.hexcasting;

import com.iridium126.createmanaindustry.CreateManaIndustry;
import com.mojang.serialization.Codec;
import com.samsthenerd.inline.api.InlineData;

import dev.enjarai.trickster.spell.SpellPart;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;

/**
 * Inline-mod data that renders a Trickster {@link SpellPart} as its full
 * circular spell tree in text (chat, tooltips). Mirrors Hexcasting's own
 * {@code InlinePatternData}; the actual drawing happens on the client in
 * {@code InlineTrickRenderer}, which delegates to Trickster's
 * {@code CircleRenderer} for a pixel-identical look.
 * <p>
 * Server-safe: only references common-side classes.
 */
public class InlineTrickData implements InlineData<InlineTrickData> {

    public static final ResourceLocation RENDERER_ID = CreateManaIndustry.modLoc("trick");

    public final SpellPart spell;

    public InlineTrickData(SpellPart spell) {
        this.spell = spell;
    }

    @Override
    public InlineTrickDataType getType() {
        return InlineTrickDataType.INSTANCE;
    }

    @Override
    public ResourceLocation getRendererId() {
        return RENDERER_ID;
    }

    @Override
    public Component asText(boolean withExtra) {
        return Component.literal(".").withStyle(asStyle(withExtra));
    }

    public static class InlineTrickDataType implements InlineDataType<InlineTrickData> {
        private static final ResourceLocation ID = CreateManaIndustry.modLoc("trick");
        public static final InlineTrickDataType INSTANCE = new InlineTrickDataType();

        @Override
        public ResourceLocation getId() {
            return ID;
        }

        @Override
        public Codec<InlineTrickData> getCodec() {
            return TrickIota.SPELL_CODEC.xmap(InlineTrickData::new, data -> data.spell);
        }
    }
}
