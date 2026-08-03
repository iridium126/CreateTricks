package com.iridium126.createmanaindustry.ponder;

import com.iridium126.createmanaindustry.CreateManaIndustry;
import com.iridium126.createmanaindustry.CMIBlocks;
import com.simibubi.create.infrastructure.ponder.AllCreatePonderTags;

import net.createmod.ponder.api.registration.PonderPlugin;
import net.createmod.ponder.api.registration.PonderTagRegistrationHelper;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.ItemLike;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;

public class CMIPonderPlugin implements PonderPlugin {
    private static final ResourceLocation SPELL_CONSTRUCT_BLOCK =
            ResourceLocation.fromNamespaceAndPath("trickster", "spell_construct");
    private static final ResourceLocation MODULAR_SPELL_CONSTRUCT_BLOCK =
            ResourceLocation.fromNamespaceAndPath("trickster", "modular_spell_construct");
    private static final ResourceLocation CHARGING_ARRAY_BLOCK =
            ResourceLocation.fromNamespaceAndPath("trickster", "charging_array");

    @Override
    public String getModId() {
        return CreateManaIndustry.MODID;
    }

    @Override
    public void registerTags(PonderTagRegistrationHelper<ResourceLocation> helper) {
        PonderTagRegistrationHelper<ItemLike> itemHelper =
                helper.withKeyFunction(itemLike -> BuiltInRegistries.ITEM.getKey(itemLike.asItem()));

        Block chargingArray = BuiltInRegistries.BLOCK.get(CHARGING_ARRAY_BLOCK);
        if (chargingArray != null && chargingArray != Blocks.AIR)
            itemHelper.addToTag(AllCreatePonderTags.ARM_TARGETS).add(chargingArray);

        Block spellConstruct = BuiltInRegistries.BLOCK.get(SPELL_CONSTRUCT_BLOCK);
        if (spellConstruct != null && spellConstruct != Blocks.AIR) {
            itemHelper.addToTag(AllCreatePonderTags.DISPLAY_TARGETS).add(spellConstruct);
            itemHelper.addToTag(AllCreatePonderTags.ARM_TARGETS).add(spellConstruct);
        }

        Block modularSpellConstruct = BuiltInRegistries.BLOCK.get(MODULAR_SPELL_CONSTRUCT_BLOCK);
        if (modularSpellConstruct != null && modularSpellConstruct != Blocks.AIR) {
            itemHelper.addToTag(AllCreatePonderTags.DISPLAY_TARGETS).add(modularSpellConstruct);
            itemHelper.addToTag(AllCreatePonderTags.ARM_TARGETS).add(modularSpellConstruct);
        }

        itemHelper.addToTag(AllCreatePonderTags.ARM_TARGETS).add(CMIBlocks.ALLAY_BURNER);
    }
}
