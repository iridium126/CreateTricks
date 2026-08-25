package com.iridium126.createmanaindustry.mixin.irisveil;

import java.util.HashSet;
import java.util.Set;

import com.iridium126.createmanaindustry.client.particles.shaderpack.ShaderPackProgramCompiler;

import net.irisshaders.iris.gl.program.ProgramSamplers;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyVariable;

/**
 * Reserves the texture units the merged MODEL-particle programs pin their TBO
 * samplers to ({@code ShaderPackProgramCompiler#MERGED_SAMPLER_UNIT_BASE}
 * through {@code +3}) inside Iris's own sampler allocator, so a pack sampler
 * can never be assigned onto them -- the same practice iris-flw-compat uses
 * for its Flywheel units via this exact builder parameter. Without the
 * reservation, a pack sampler landing on units 10-13 would silently fight
 * the particle TBO fetches within the same draw.
 *
 * <p>Unconditional by design: reservation correctness must not depend on any
 * runtime config, and four units cost the pack's allocator nothing measurable.
 *
 * <p>Gated on iris-veil-compat presence by {@code CMIMixinPlugin} (the
 * {@code .irisveil.} package rule); Iris is a hard dependency of
 * iris-veil-compat, so targeting iris internals is safe whenever this mixin
 * applies.
 */
@Mixin(value = ProgramSamplers.class, remap = false)
public class MixinProgramSamplers {

    @ModifyVariable(method = "builder", at = @At("LOAD"), argsOnly = true)
    private static Set<Integer> createmanaindustry$reserveParticleTboUnits(Set<Integer> reservedTextureUnits) {
        // The set Iris hands in is immutable, so duplicate before modifying.
        Set<Integer> units = new HashSet<>(reservedTextureUnits);
        // MERGED_SAMPLER_UNIT_BASE is a compile-time constant, so referencing it
        // here neither loads the compiler class nor risks drift between the two.
        for (int i = 0; i < 4; i++)
            units.add(ShaderPackProgramCompiler.MERGED_SAMPLER_UNIT_BASE + i);
        return units;
    }
}
