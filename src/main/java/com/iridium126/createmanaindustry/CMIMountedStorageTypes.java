package com.iridium126.createmanaindustry;

import static com.iridium126.createmanaindustry.CreateManaIndustry.REGISTRATE;

import com.iridium126.createmanaindustry.content.fluids.fueltank.storage.FuelTankMountedStorageType;
import com.simibubi.create.api.contraption.storage.fluid.MountedFluidStorageType;
import com.tterrag.registrate.util.entry.RegistryEntry;

/**
 * Mounted fluid storage types for Create contraptions (mirrors Create's
 * {@code AllMountedStorageTypes}). The fuel tank registers one so its contents
 * stay accessible to pipes while carried on a moving structure.
 */
public final class CMIMountedStorageTypes {

	public static final RegistryEntry<MountedFluidStorageType<?>, FuelTankMountedStorageType> MOLTEN_SALT_FUEL_TANK =
		REGISTRATE.mountedFluidStorage("molten_salt_fuel_tank", FuelTankMountedStorageType::new)
			.register();

	private CMIMountedStorageTypes() {
	}

	public static void register() {
	}
}
