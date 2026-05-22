package com.iridium126.createtricks;

import com.iridium126.createtricks.display.SpellConstructDisplayTarget;
import com.simibubi.create.api.behaviour.display.DisplayTarget;
import com.simibubi.create.api.registry.CreateRegistries;

import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.fml.event.lifecycle.FMLCommonSetupEvent;
import net.neoforged.neoforge.registries.RegisterEvent;

@EventBusSubscriber(modid = CreateTricks.MODID)
public final class CreateTricksDisplayTargets {
	public static final SpellConstructDisplayTarget SPELL_CONSTRUCT = new SpellConstructDisplayTarget();
	public static final ResourceLocation SPELL_CONSTRUCT_ID = ResourceLocation.fromNamespaceAndPath(CreateTricks.MODID, "spell_construct");

	private CreateTricksDisplayTargets() {}

	@SubscribeEvent
	public static void registerDisplayTarget(RegisterEvent event) {
		if (!event.getRegistryKey().equals(CreateRegistries.DISPLAY_TARGET))
			return;

		event.register(CreateRegistries.DISPLAY_TARGET, SPELL_CONSTRUCT_ID, () -> SPELL_CONSTRUCT);
	}

	@SubscribeEvent
	public static void registerBlockEntityBindings(FMLCommonSetupEvent event) {
		event.enqueueWork(() -> {
			registerBlockEntity("spell_construct");
			registerBlockEntity("modular_spell_construct");
		});
	}

	private static void registerBlockEntity(String path) {
		ResourceLocation id = ResourceLocation.fromNamespaceAndPath("trickster", path);
		BlockEntityType<?> type = BuiltInRegistries.BLOCK_ENTITY_TYPE.get(id);
		if (type != null)
			DisplayTarget.BY_BLOCK_ENTITY.register(type, SPELL_CONSTRUCT);
		else
			CreateTricks.LOGGER.warn("Could not bind DisplayTarget to missing block entity type {}", id);
	}
}
