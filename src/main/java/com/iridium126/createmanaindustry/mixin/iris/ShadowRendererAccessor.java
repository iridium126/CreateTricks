package com.iridium126.createmanaindustry.mixin.iris;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import com.iridium126.createmanaindustry.client.render.shaderpack.IrisShadowTextures;

import net.irisshaders.iris.shadows.ShadowRenderer;
import net.irisshaders.iris.shadows.ShadowRenderTargets;

/**
 * Captures the iris shadow-map depth texture ID for the mist Tyndall effect.
 * <p>
 * {@link ShadowRenderer}'s public statics carry the shadow matrices and
 * resolution, but the GL texture IDs live on its private {@code targets} field
 * and the instance itself is held privately by {@code IrisRenderingPipeline}.
 * This mixin watches {@code ShadowRenderer}'s constructor and forwards the
 * depth-map ID ({@code shadowtex0}) to {@link IrisShadowTextures}, which
 * {@code MistIrisHook} reads each frame. The holder indirection avoids the
 * mixin-merging pitfall of reading a {@code @Unique} static field from the
 * mixin class directly.
 * <p>
 * iris is Mojang-mapped at runtime, so the mixin is {@code remap = false}; it
 * is gated to load only when iris is present by {@code CMIMixinPlugin} (the
 * {@code .iris.} rule).
 */
@Mixin(value = ShadowRenderer.class, remap = false)
public abstract class ShadowRendererAccessor {

    @Shadow
    private ShadowRenderTargets targets;

    @Shadow
    private float sunPathRotation;

    @Inject(method = "<init>", at = @At("RETURN"))
    private void createmanaindustry$captureShadowDepthTexture(CallbackInfo ci) {
        IrisShadowTextures.setShadowDepthTextureId(this.targets.getDepthTexture().getTextureId());
        IrisShadowTextures.setOpaqueDepthTextureId(
                this.targets.getDepthTextureNoTranslucents().getTextureId());
        // Live handle for the lazily-created color targets (shadowcolor0 is only
        // allocated once a pack references it); resolved on the render thread
        // under iris only.
        IrisShadowTextures.setShadowTargets(this.targets);
        IrisShadowTextures.setSunPathRotation(this.sunPathRotation);
    }
}
