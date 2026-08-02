package com.iridium126.createmanaindustry.content.display;

import com.iridium126.createmanaindustry.CreateManaIndustry;
import com.simibubi.create.api.behaviour.display.DisplayTarget;
import com.simibubi.create.api.registry.CreateRegistries;

import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.fml.event.lifecycle.FMLCommonSetupEvent;
import net.neoforged.neoforge.registries.RegisterEvent;

@EventBusSubscriber(modid = CreateManaIndustry.MODID)
public final class CMIDisplayTargets {
    public static final SpellConstructDisplayTarget SPELL_CONSTRUCT = new SpellConstructDisplayTarget();
    public static final ModularSpellConstructDisplayTarget MODULAR_SPELL_CONSTRUCT = new ModularSpellConstructDisplayTarget();
    public static final ResourceLocation SPELL_CONSTRUCT_ID = ResourceLocation.fromNamespaceAndPath(CreateManaIndustry.MODID, "spell_construct");
    public static final ResourceLocation MODULAR_SPELL_CONSTRUCT_ID = ResourceLocation.fromNamespaceAndPath(CreateManaIndustry.MODID, "modular_spell_construct");

    private CMIDisplayTargets() {}

    @SubscribeEvent
    public static void registerDisplayTarget(RegisterEvent event) {
        if (!CreateManaIndustry.TRICKSTER_ACTIVE)
            return;
        if (!event.getRegistryKey().equals(CreateRegistries.DISPLAY_TARGET))
            return;

        event.register(CreateRegistries.DISPLAY_TARGET, SPELL_CONSTRUCT_ID, () -> SPELL_CONSTRUCT);
        event.register(CreateRegistries.DISPLAY_TARGET, MODULAR_SPELL_CONSTRUCT_ID, () -> MODULAR_SPELL_CONSTRUCT);
    }

    @SubscribeEvent
    public static void registerBlockEntityBindings(FMLCommonSetupEvent event) {
        if (!CreateManaIndustry.TRICKSTER_ACTIVE)
            return;
        event.enqueueWork(() -> {
            registerBlockEntity("spell_construct", SPELL_CONSTRUCT);
            registerBlockEntity("modular_spell_construct", MODULAR_SPELL_CONSTRUCT);
        });
    }

    private static void registerBlockEntity(String path, DisplayTarget target) {
        ResourceLocation id = ResourceLocation.fromNamespaceAndPath("trickster", path);
        BlockEntityType<?> type = BuiltInRegistries.BLOCK_ENTITY_TYPE.get(id);
        if (type != null)
            DisplayTarget.BY_BLOCK_ENTITY.register(type, target);
        else
            CreateManaIndustry.LOGGER.warn("Could not bind DisplayTarget to missing block entity type {}", id);
    }
}
