package com.iridium126.createtricks;

import com.simibubi.create.foundation.data.CreateRegistrate;

import net.minecraft.core.registries.Registries;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.CreativeModeTab;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.registries.DeferredHolder;
import net.neoforged.neoforge.registries.DeferredRegister;

public final class CreateTricksRegistrate {
	public static final CreateRegistrate REGISTRATE = CreateRegistrate.create(CreateTricks.MODID);

	private static final DeferredRegister<CreativeModeTab> CREATIVE_TABS =
			DeferredRegister.create(Registries.CREATIVE_MODE_TAB, CreateTricks.MODID);

	public static final DeferredHolder<CreativeModeTab, CreativeModeTab> MAIN_TAB = CREATIVE_TABS.register("main",
			() -> CreativeModeTab.builder()
					.title(Component.translatable("itemGroup.createtricks"))
					.icon(() -> CreateTricksBlocks.STRESS_MANA_CONVERTER.asStack())
					.build());

	private CreateTricksRegistrate() {}

	public static void register(IEventBus modEventBus) {
		REGISTRATE.setCreativeTab(MAIN_TAB);
		REGISTRATE.registerEventListeners(modEventBus);
		CREATIVE_TABS.register(modEventBus);
		CreateTricksBlocks.register();
		CreateTricksBlockEntityTypes.register();
	}
}
