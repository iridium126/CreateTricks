package com.iridium126.createmanaindustry.compat.hexcasting;

import com.iridium126.createmanaindustry.CreateManaIndustry;

import at.petrak.hexcasting.api.casting.iota.IotaType;
import at.petrak.hexcasting.common.lib.HexRegistries;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.registries.DeferredHolder;
import net.neoforged.neoforge.registries.DeferredRegister;

/**
 * Registry for custom Hexcasting iota types added by Create: Mana Industry.
 * <p>
 * Only loaded when both hexcasting and trickster are present (see the gate in
 * {@link CreateManaIndustry}); the iota registry is synced, so entries
 * register at mod-construct time reach clients automatically.
 */
public class CMIHexIotaTypes {

    private static final DeferredRegister<IotaType<?>> IOTA_TYPES =
            DeferredRegister.create(HexRegistries.IOTA_TYPE, CreateManaIndustry.MODID);

    public static final DeferredHolder<IotaType<?>, IotaType<?>> TRICK =
            IOTA_TYPES.register("trick", () -> TrickIota.TYPE);

    public static void register(IEventBus modEventBus) {
        IOTA_TYPES.register(modEventBus);
    }
}
