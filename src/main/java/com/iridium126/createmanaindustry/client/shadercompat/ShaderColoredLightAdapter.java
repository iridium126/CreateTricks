package com.iridium126.createmanaindustry.client.shadercompat;

import javax.annotation.Nullable;

/**
 * Per-shaderpack adapter for colored fuel-tank lights.
 * <p>
 * Implementations encapsulate everything pack-specific: how to recognize the
 * pack, which numeric IDs the tank's lit cells should claim, and which shader
 * source files need in-memory text transforms. The Iris mixins are generic
 * dispatchers over {@link ShaderColoredLightAdapters#ALL}; they hold no
 * pack knowledge themselves.
 * <p>
 * All implementations must fail open: any structural surprise means logging
 * and leaving the pack untouched, never throwing and never half-patching.
 */
public interface ShaderColoredLightAdapter {

	/** Whether this adapter targets the given active shaderpack name. */
	boolean appliesTo(String shaderPackName);

	/**
	 * Applies block.properties ID-map adjustments (moves, takeovers, additions)
	 * through the provided context. Called once per pack load from the IdMap
	 * construction tail, before the rendering pipeline consumes the maps.
	 */
	void patchBlockIdMap(BlockIdMapContext ctx);

	/**
	 * Transforms one shader source file read from the pack.
	 *
	 * @param fileName simple file name (e.g. {@code light_colors.glsl})
	 * @param source   full original source text
	 * @return the transformed text, or {@code null} to leave it untouched
	 */
	@Nullable
	String transformShader(String fileName, String source);
}
