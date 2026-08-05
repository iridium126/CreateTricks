package com.iridium126.createmanaindustry.mixin.burner;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;
import org.spongepowered.asm.mixin.gen.Invoker;

import com.simibubi.create.content.processing.burner.BlazeBurnerBlockEntity;

/**
 * Exposes {@link BlazeBurnerBlockEntity} fuel fields and feedback methods to
 * the Light Burner hex action. Must stay an interface: applied mixin classes
 * are marked invalid by the Mixin framework and cannot be loaded as regular
 * classes at runtime, so external code has to access generated methods through
 * the interface.
 */
@Mixin(value = BlazeBurnerBlockEntity.class, remap = false)
public interface BlazeBurnerBlockEntityAccessor {
    @Accessor("remainingBurnTime")
    int createmanaindustry$getRemainingBurnTime();

    @Accessor("remainingBurnTime")
    void createmanaindustry$setRemainingBurnTime(int burnTime);

    @Accessor("activeFuel")
    BlazeBurnerBlockEntity.FuelType createmanaindustry$getActiveFuel();

    @Accessor("activeFuel")
    void createmanaindustry$setActiveFuel(BlazeBurnerBlockEntity.FuelType fuelType);

    @Invoker("playSound")
    void createmanaindustry$playSound();

    @Invoker("spawnParticleBurst")
    void createmanaindustry$spawnParticleBurst(boolean soulFlame);
}
