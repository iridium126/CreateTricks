package com.iridium126.createmanaindustry.mixin.sodium;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import com.iridium126.createmanaindustry.dimension.AllvrDimensions;

import net.minecraft.client.Minecraft;

/**
 * Disables Sodium's terrain rendering inside the allay dimension (doc §6.4).
 * Sodium's own mixin intercepts {@code LevelRenderer.renderSectionLayer} and
 * dispatches into {@code DefaultChunkRenderer.render} — cancelling the vanilla
 * method cannot stop that dispatch (both HEAD callbacks run), so sodium's
 * entry point is cancelled here instead. Also drops its per-frame GL state
 * machine out of ALLVR's draw window.
 * <p>
 * String mixin targets with {@code remap = false}: no compile-time sodium
 * dependency (build.gradle stays untouched); the mixin only applies when the
 * target class loads, which {@code CMIMixinPlugin}'s {@code .sodium.} gate
 * further restricts to installs where sodium is present. Sodium 0.6.x has a
 * single {@code render} entry (verified against {@code .refs/sodium}).
 */
@Mixin(targets = "net.caffeinemc.mods.sodium.client.render.chunk.DefaultChunkRenderer", remap = false)
public abstract class AllvrSodiumTerrainMixin {

    @Inject(method = "render", at = @At("HEAD"), cancellable = true, remap = false)
    private void allvr$cancelSodiumTerrain(CallbackInfo ci) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.level != null && mc.level.dimension() == AllvrDimensions.ALLAY_LEVEL) {
            ci.cancel();
        }
    }
}
