package com.iridium126.createmanaindustry.hexcasting;

import com.iridium126.createmanaindustry.CreateManaIndustry;

import at.petrak.hexcasting.api.casting.ActionRegistryEntry;
import at.petrak.hexcasting.api.casting.math.HexDir;
import at.petrak.hexcasting.api.casting.math.HexPattern;
import at.petrak.hexcasting.common.lib.HexRegistries;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.registries.DeferredHolder;
import net.neoforged.neoforge.registries.DeferredRegister;

/**
 * Registry for custom Hexcasting actions added by Create: Mana Industry.
 * Uses {@link DeferredRegister} directly to avoid the Kotlin mod context
 * requirement in Hexcasting's {@code IXplatRegister}.
 */
public class CMIHexActions {

    private static final DeferredRegister<ActionRegistryEntry> ACTIONS =
            DeferredRegister.create(HexRegistries.ACTION, CreateManaIndustry.MODID);

    public static final DeferredHolder<ActionRegistryEntry, ActionRegistryEntry> READ_IOTA_FROM_BLOCK =
            ACTIONS.register("read_iota_from_block", () ->
                    new ActionRegistryEntry(
                            HexPattern.fromAngles("wqwqwqwqwqwaw", HexDir.NORTH_WEST),
                            OpReadIotaFromBlock.INSTANCE));

    public static void register(IEventBus modEventBus) {
        ACTIONS.register(modEventBus);
    }
}
