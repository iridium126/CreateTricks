package com.iridium126.createmanaindustry.mixin.iris;

import java.nio.file.Path;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import com.iridium126.createmanaindustry.client.shadercompat.ShaderColoredLightAdapter;
import com.iridium126.createmanaindustry.client.shadercompat.ShaderColoredLightAdapters;

import net.irisshaders.iris.shaderpack.include.IncludeGraph;

/**
 * Generic dispatcher over the shaderpack include loader: every included file's
 * content passes through {@code IncludeGraph#readFile}, so adapters get one
 * chance per file to transform its text in memory. Adapters self-gate on the
 * active pack name and on structural anchors; unmatched files pass through.
 */
@Mixin(value = IncludeGraph.class, remap = false)
public class IncludeGraphMixin {

	// require = 0: same rationale as IrisIdMapMixin.
	@Inject(method = "readFile", at = @At("RETURN"), cancellable = true, remap = false, require = 0)
	private static void cmi$transformShaderSource(Path path, CallbackInfoReturnable<String> cir) {
		Path fileName = path.getFileName();
		if (fileName == null)
			return;

		String name = fileName.toString();
		String packName = ShaderColoredLightAdapters.activePackName();
		for (ShaderColoredLightAdapter adapter : ShaderColoredLightAdapters.ALL) {
			if (!adapter.appliesTo(packName))
				continue;
			String transformed = adapter.transformShader(name, cir.getReturnValue());
			if (transformed != null) {
				cir.setReturnValue(transformed);
				return;
			}
		}
	}
}
