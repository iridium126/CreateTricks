package com.iridium126.createtricks;

import org.slf4j.Logger;

import com.iridium126.createtricks.content.kinetics.StressManaConverterBlock;
import com.iridium126.createtricks.content.kinetics.StressManaConverterBlockEntity;
import com.iridium126.createtricks.content.kinetics.StressRangeTooltipModifier;
import com.mojang.logging.LogUtils;
import com.simibubi.create.foundation.data.CreateRegistrate;
import com.simibubi.create.foundation.item.ItemDescription;
import com.simibubi.create.foundation.item.KineticStats;
import com.simibubi.create.foundation.item.TooltipModifier;

import net.createmod.catnip.lang.FontHelper;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.Item;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.common.Mod;
import net.neoforged.fml.ModContainer;

@Mod(CreateTricks.MODID)
public class CreateTricks {
	public static final String MODID = "createtricks";
	public static final Logger LOGGER = LogUtils.getLogger();

	public static final CreateRegistrate REGISTRATE = CreateRegistrate.create(MODID);

	static {
		REGISTRATE.defaultCreativeTab(CreateTricksCreativeModeTabs.MAIN_TAB.getKey());
		REGISTRATE.setTooltipModifierFactory(CreateTricks::createTooltipModifier);
	}

	private static TooltipModifier createTooltipModifier(Item item) {
		TooltipModifier description = new ItemDescription.Modifier(item, FontHelper.Palette.STANDARD_CREATE);
		if (item instanceof BlockItem blockItem && blockItem.getBlock() instanceof StressManaConverterBlock) {
			return description.andThen(new StressRangeTooltipModifier(
					StressManaConverterBlockEntity.MIN_STRESS_PER_RPM,
					StressManaConverterBlockEntity.MAX_STRESS_PER_RPM));
		}
		return description.andThen(TooltipModifier.mapNull(KineticStats.create(item)));
	}

	public CreateTricks(IEventBus modEventBus, ModContainer modContainer) {
		REGISTRATE.registerEventListeners(modEventBus);
		CreateTricksCreativeModeTabs.register(modEventBus);
		CreateTricksRecipeTypes.register(modEventBus);
		CreateTricksBlocks.register();
		CreateTricksFluids.register();
		CreateTricksBlockEntityTypes.register();
		modContainer.registerConfig(net.neoforged.fml.config.ModConfig.Type.COMMON, Config.SPEC);
	}

	public static ResourceLocation modLoc(String path) {
		return ResourceLocation.fromNamespaceAndPath(MODID, path);
	}
}