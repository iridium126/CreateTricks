package com.iridium126.createmanaindustry.mixin.vanilla;

import com.iridium126.createmanaindustry.client.particles.engine.CMIParticleEngine;

import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.GameRenderer;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Tail hook on the vanilla crosshair pick: lets the GPU allay particles win
 * the crosshair like real entities (see
 * {@link CMIParticleEngine#injectCrosshairPick} for the full contract).
 * Runs AFTER vanilla has chosen its entity/block result; the engine only
 * replaces it when an allay particle is strictly closer, so every vanilla
 * outcome stays intact otherwise. No-op cost when the engine is unavailable
 * (field guard returns before any allocation).
 */
@Mixin(GameRenderer.class)
public abstract class GameRendererPickMixin {

    @Inject(method = "pick(F)V", at = @At("TAIL"))
    private void createmanaindustry$injectParticlePick(float partialTicks, CallbackInfo ci) {
        CMIParticleEngine.INSTANCE.injectCrosshairPick(Minecraft.getInstance());
    }
}
