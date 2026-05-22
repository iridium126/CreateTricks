package com.iridium126.createtricks;

import org.slf4j.Logger;

import com.mojang.logging.LogUtils;

import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.common.Mod;
import net.neoforged.fml.ModContainer;

@Mod(CreateTricks.MODID)
public class CreateTricks {
	public static final String MODID = "createtricks";
	public static final Logger LOGGER = LogUtils.getLogger();

	public CreateTricks(IEventBus modEventBus, ModContainer modContainer) {
	}
}
