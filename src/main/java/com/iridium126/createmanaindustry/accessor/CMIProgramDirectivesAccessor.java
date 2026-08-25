package com.iridium126.createmanaindustry.accessor;

import net.irisshaders.iris.gl.blending.BlendModeOverride;

/**
 * Duck interface over a {@code ProgramDirectives} instance, implemented by
 * {@code mixin.iris.MixinProgramDirectives}. Lets client code pin a blend
 * override onto one directives object (the ghost merged program's private
 * copy) without touching Iris internals outside mixin code.
 *
 * <p>Lives OUTSIDE the {@code mixin.*} package on purpose: the Mixin
 * transformer reserves every declared mixin package, and directly referencing
 * a non-mixin type from there raises {@code IllegalClassLoadError}. Same
 * layout convention as iris-veil-compat's / iris-flw-compat's
 * {@code accessors} packages.</p>
 */
public interface CMIProgramDirectivesAccessor {
    void createmanaindustry$setBlendModeOverride(BlendModeOverride override);
}
