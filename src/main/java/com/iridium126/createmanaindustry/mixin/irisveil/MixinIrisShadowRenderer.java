package com.iridium126.createmanaindustry.mixin.irisveil;

import com.iridium126.createmanaindustry.CreateManaIndustry;
import com.iridium126.createmanaindustry.client.particles.shaderpack.CMIPackEntityMergeHook;

import net.irisshaders.iris.shadows.ShadowRenderer;

import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * S-track window: draws the MODEL particle segments into the active shadow map
 * at the same spot iris-flw-compat uses for Flywheel content -- inside
 * {@code renderShadows}, right before Iris batches its own buffered entity
 * geometry ("draw entities" section). The shadow framebuffer, viewport and
 * matrices are all live at that point.
 *
 * <p>Gated on the pack's ENTITY-shadow directive ({@code shouldRenderEntities},
 * the flag Iris itself consults before rendering regular entities into the
 * shadow map): when a pack disables entity shadows, a native allay casts none
 * either, so our particle models must not -- same respect-for-pack-config
 * pattern iris-flw-compat applies to its own stream via this mixin family.
 * Player-only packs (entities off, player on) deliberately stay shadow-free:
 * particles are entities, not the player.
 *
 * <p>Gated on iris-veil-compat by {@code CMIMixinPlugin} (the {@code .irisveil.}
 * package rule; iris is its hard dependency). The runtime IRISVEIL_ACTIVE check
 * mirrors {@code LevelRendererBlockEntitiesMixin}: the hook class's irisveil
 * imports must never be resolved when the mod is absent.
 */
@Mixin(value = ShadowRenderer.class, remap = false)
abstract class MixinIrisShadowRenderer {

    @Final
    @Shadow
    private boolean shouldRenderEntities;

    @Inject(method = "renderShadows",
            at = @At(value = "INVOKE_STRING",
                    target = "Lnet/minecraft/util/profiling/ProfilerFiller;popPush(Ljava/lang/String;)V",
                    args = "ldc=draw entities"))
    private void createmanaindustry$drawShadowModels(CallbackInfo ci) {
        if (!CreateManaIndustry.IRISVEIL_ACTIVE)
            return;
        if (!this.shouldRenderEntities)
            return; // pack disabled entity shadows: native allays cast none either
        CMIPackEntityMergeHook.renderShadow();
    }
}
