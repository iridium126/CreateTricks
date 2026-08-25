package com.iridium126.createmanaindustry.mixin.iris;

import com.iridium126.createmanaindustry.accessor.CMIProgramDirectivesAccessor;

import net.irisshaders.iris.gl.blending.BlendModeOverride;
import net.irisshaders.iris.shaderpack.properties.ProgramDirectives;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.Optional;

/**
 * Getter short-circuit over {@link ProgramDirectives#getBlendModeOverride()},
 * letting the ghost merged program pin the standard translucent blend on top
 * of its Route-A inherited entity directives. The shadow field lives PER
 * DIRECTIVES INSTANCE, and {@code withDirectiveOverride} hands every synthetic
 * source the same original instance -- so callers must first clone via
 * {@code withOverriddenDrawBuffers(getDrawBuffers())} before pinning, or both
 * programs would flip to the ghost blend. Same technique as iris-veil-compat's
 * alpha-test override (MIT, (c) top.leonx). Gated on iris by
 * {@code CMIMixinPlugin} (the {@code .iris.} package).
 */
@Mixin(value = ProgramDirectives.class, remap = false)
public class MixinProgramDirectives implements CMIProgramDirectivesAccessor {

    @Unique
    private BlendModeOverride cmiBlendModeOverride;

    @Override
    public void createmanaindustry$setBlendModeOverride(BlendModeOverride override) {
        this.cmiBlendModeOverride = override;
    }

    @Inject(method = "getBlendModeOverride", at = @At("HEAD"), cancellable = true)
    private void createmanaindustry$injectBlendOverride(CallbackInfoReturnable<Optional<BlendModeOverride>> cir) {
        if (this.cmiBlendModeOverride != null)
            cir.setReturnValue(Optional.of(this.cmiBlendModeOverride));
    }
}
