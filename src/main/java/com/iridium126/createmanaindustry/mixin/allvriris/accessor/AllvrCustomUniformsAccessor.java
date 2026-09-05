package com.iridium126.createmanaindustry.mixin.allvriris.accessor;

import it.unimi.dsi.fastutil.objects.Object2IntMap;
import net.irisshaders.iris.uniforms.custom.CustomUniforms;
import net.irisshaders.iris.uniforms.custom.cached.CachedUniform;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

import java.util.Map;

/**
 * Exposes iris's custom-uniform location map so ALLVR's uniform layout builder
 * can mirror pack CustomUniforms (per-pass keyed) into the shared std140 UBO —
 * voxy's same accessor, under ALLVR's own name for coexistence.
 */
@Mixin(value = CustomUniforms.class, remap = false)
public interface AllvrCustomUniformsAccessor {

    @Accessor
    Map<Object, Object2IntMap<CachedUniform>> getLocationMap();
}
