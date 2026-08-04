package com.iridium126.createmanaindustry.mixin.vanilla;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

import net.minecraft.util.random.SimpleWeightedRandomList;
import net.minecraft.world.level.BaseSpawner;
import net.minecraft.world.level.SpawnData;

/**
 * Accesses the private spawn-potential fields of {@link BaseSpawner} so the
 * Allay Burner item can inspect spawner contents when capturing allays
 * (mirrors Create's blaze-burner capture logic).
 */
@Mixin(BaseSpawner.class)
public interface BaseSpawnerAccessor {

    @Accessor("spawnPotentials")
    SimpleWeightedRandomList<SpawnData> createmanaindustry$getSpawnPotentials();

    @Accessor("nextSpawnData")
    SpawnData createmanaindustry$getNextSpawnData();
}
