package com.iridium126.createmanaindustry.mixin.allvriris.shared;

import com.google.common.collect.ImmutableList;
import com.iridium126.createmanaindustry.CreateManaIndustry;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import net.irisshaders.iris.shaderpack.include.ShaderPackSourceNames;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/**
 * Registers the voxy adaptation file names with iris's source-name discovery
 * so the voxy.json/voxy_*.glsl files enter iris's include graph (the source
 * provider only serves files reachable from a start file — without this, a
 * pack's voxy.json is invisible and the patch parse silently gets null).
 * Apply-time gated off while voxy is installed (its identical hook already
 * covers the names).
 * <p>
 * MUST NOT check the runtime config here: {@code POTENTIAL_STARTS} is a
 * {@code static final} computed exactly once when this class first loads —
 * which happens at the first pack load, typically BEFORE client configs are
 * loaded (G2 smoke ②, 2026-09-05). A config gate froze the names out for the
 * whole JVM session. The config gates live where values are re-evaluated per
 * pack load: the patch parse ({@code AllvrProgramSetMixin}) and the pipeline
 * data build ({@code AllvrIrisPipelineMixin}). Registering the names is
 * side-effect free when the integration is off — iris merely reads a few
 * extra pack files into its include graph.
 */
@Mixin(value = ShaderPackSourceNames.class, remap = false)
public abstract class AllvrShaderPackSourceNamesMixin {

    @WrapOperation(method = "findPotentialStarts", at = @At(value = "INVOKE",
        target = "Lcom/google/common/collect/ImmutableList;builder()Lcom/google/common/collect/ImmutableList$Builder;"))
    private static ImmutableList.Builder<String> allvr$addVoxyNames(Operation<ImmutableList.Builder<String>> original) {
        // runs exactly once (static initializer) — hence the once-per-session log
        CreateManaIndustry.LOGGER.info("[Allvr] voxy source names registered with the pack include graph");
        return original.call()
            .add("voxy.json")
            .add("voxy_opaque.glsl")
            .add("voxy_translucent.glsl");
    }
}
