package com.iridium126.createmanaindustry.mixin.vanilla;

import net.minecraft.client.Minecraft;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Invoker;

/**
 * Invoker for the private {@code Minecraft.startUseItem}: the storm allay
 * use-key fall-through ({@code CMIParticleEngine#replayVanillaUse}) cancels
 * the vanilla use against the synthetic crosshair proxy and re-runs it
 * against the pre-injection pick result, so the held item is never handed to
 * a throwaway object.
 */
@Mixin(Minecraft.class)
public interface MinecraftInvoker {

    @Invoker("startUseItem")
    void createmanaindustry$invokeStartUseItem();
}
