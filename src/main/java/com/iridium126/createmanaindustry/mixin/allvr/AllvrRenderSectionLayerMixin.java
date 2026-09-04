package com.iridium126.createmanaindustry.mixin.allvr;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import com.iridium126.createmanaindustry.dimension.AllvrDimensions;

import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.client.renderer.RenderType;

/**
 * Disables vanilla terrain rendering inside the allay dimension (doc §6.4):
 * the column chunks there are empty air shells and the ALLVR terrain pass owns
 * the slot (drawn at AFTER_SKY). The shells would render nothing anyway —
 * cancelling removes the per-frame empty-section iteration and, more
 * importantly, asserts ALLVR as the sole terrain authority in the dimension.
 * <p>
 * Sodium, when present, intercepts the same method and calls its own
 * {@code DefaultChunkRenderer.render} — that path is cancelled separately by
 * {@link AllvrSodiumTerrainMixin} (two HEAD callbacks on one method both run;
 * cancelling vanilla alone cannot stop sodium's).
 */
@Mixin(LevelRenderer.class)
public abstract class AllvrRenderSectionLayerMixin {

    @Shadow
    private ClientLevel level;

    @Inject(method = "renderSectionLayer(Lnet/minecraft/client/renderer/RenderType;DDDLorg/joml/Matrix4f;Lorg/joml/Matrix4f;)V",
        at = @At("HEAD"), cancellable = true)
    private void allvr$cancelVanillaTerrain(RenderType renderType, double x, double y, double z,
                                            org.joml.Matrix4f frustrumMatrix, org.joml.Matrix4f projectionMatrix,
                                            CallbackInfo ci) {
        if (this.level != null && this.level.dimension() == AllvrDimensions.ALLAY_LEVEL) {
            ci.cancel();
        }
    }
}
