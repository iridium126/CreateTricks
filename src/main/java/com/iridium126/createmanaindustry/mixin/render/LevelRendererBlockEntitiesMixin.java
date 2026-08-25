package com.iridium126.createmanaindustry.mixin.render;

import com.iridium126.createmanaindustry.CreateManaIndustry;
import com.iridium126.createmanaindustry.client.particles.shaderpack.CMIPackEntityMergeHook;

import net.minecraft.client.renderer.LevelRenderer;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Early MODEL-particle window: fires inside {@link LevelRenderer#renderLevel}
 * at the exact point Flywheel uses to draw its entity-stage instanced content
 * (the profiler section boundary before "blockentities"; priority 1001 to sort
 * after Sodium -- both copied from Flywheel's own LevelRendererMixin). At this
 * point regular entities are done, block entities / translucent terrain / the
 * deferred composites have NOT run yet, so shader packs consume the merged
 * program's gbuffer output natively and light the particles exactly like a
 * vanilla allay entity (see {@link CMIPackEntityMergeHook}). When the merge is
 * unavailable the engine's plain AFTER_LEVEL path renders the segments instead.
 *
 * <p>The IRISVEIL_ACTIVE check runs here first so the hook class -- which
 * references iris-veil-compat types -- is never loaded when the dependency is
 * absent; without it, vanilla and L0 rendering are untouched.</p>
 */
@Mixin(value = LevelRenderer.class, priority = 1001)
abstract class LevelRendererBlockEntitiesMixin {

    @Inject(
        method = "renderLevel",
        at = @At(
            value = "INVOKE_STRING",
            target = "Lnet/minecraft/util/profiling/ProfilerFiller;popPush(Ljava/lang/String;)V",
            args = "ldc=blockentities"
        )
    )
    private void cmi$drawEarlyModels(CallbackInfo ci) {
        if (!CreateManaIndustry.IRISVEIL_ACTIVE)
            return;
        CMIPackEntityMergeHook.render();
    }
}
