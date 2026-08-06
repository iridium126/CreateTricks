package com.iridium126.createmanaindustry.compat.trickster;

import java.util.OptionalInt;

import com.iridium126.createmanaindustry.CreateManaIndustry;

import dev.enjarai.trickster.spell.fragment.FragmentType;
import dev.enjarai.trickster.spell.trick.Tricks;
import net.minecraft.core.Registry;

/**
 * Registers the {@link IotaFragment} fragment type and the {@code read_iota}
 * trick. Both reference Hexcasting classes, so this class must only be loaded
 * from the {@code HEX_ACTIVE && TRICKSTER_ACTIVE} gate in
 * {@link CreateManaIndustry}.
 * <p>
 * The trick is registered directly into {@link Tricks#REGISTRY} (rather than
 * via {@code Tricks.register}, which forces the {@code trickster:} namespace)
 * so the id is {@code createmanaindustry:read_iota} and the lang keys resolve.
 */
public final class CMITricksterIotaRegister {

    private static boolean registered;
    private static FragmentType<IotaFragment> iotaFragmentType;

    private CMITricksterIotaRegister() {}

    public static FragmentType<IotaFragment> iotaFragmentType() {
        return iotaFragmentType;
    }

    public static void register() {
        if (registered) {
            return;
        }
        if (!(CreateManaIndustry.HEX_ACTIVE && CreateManaIndustry.TRICKSTER_ACTIVE)) {
            CreateManaIndustry.LOGGER.warn("Hexcasting or Trickster not active — skipping iota fragment/trick registration");
            return;
        }
        try {
            iotaFragmentType = Registry.register(
                    FragmentType.REGISTRY, CreateManaIndustry.modLoc("iota"),
                    new FragmentType<>(IotaFragment.ENDEC, OptionalInt.of(0xb879ff)));
            Registry.register(Tricks.REGISTRY, CreateManaIndustry.modLoc("read_iota"), new ReadIotaTrick());
            Registry.register(Tricks.REGISTRY, CreateManaIndustry.modLoc("eval_iota"), new EvalIotaTrick());
            registered = true;
            CreateManaIndustry.LOGGER.info("Registered Trickster iota fragment type and read_iota trick");
        } catch (Throwable t) {
            CreateManaIndustry.LOGGER.warn("Failed to register Trickster iota compat", t);
        }
    }
}
