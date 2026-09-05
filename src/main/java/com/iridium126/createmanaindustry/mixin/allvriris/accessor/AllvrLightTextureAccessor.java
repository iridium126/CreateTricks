package com.iridium126.createmanaindustry.mixin.allvriris.accessor;

import net.minecraft.client.renderer.LightTexture;
import net.minecraft.client.renderer.texture.DynamicTexture;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/**
 * Exposes LightTexture's private underlying GL texture so ALLVR's voxy-patch
 * sampler resolution can bind the lightmap ({@code lightmap} is a standard
 * entry in packs' voxy.json sampler lists). The vanilla field is private and
 * NeoForge has no AT for it here.
 */
@Mixin(LightTexture.class)
public interface AllvrLightTextureAccessor {

    @Accessor("lightTexture")
    DynamicTexture allvr$getLightTexture();
}
