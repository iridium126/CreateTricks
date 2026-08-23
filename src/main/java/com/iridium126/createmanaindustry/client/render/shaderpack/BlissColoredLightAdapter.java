package com.iridium126.createmanaindustry.client.render.shaderpack;

import java.util.List;
import java.util.Map;

import com.iridium126.createmanaindustry.CreateManaIndustry;

import net.irisshaders.iris.shaderpack.materialmap.BlockEntry;
import net.irisshaders.iris.shaderpack.materialmap.NamespacedId;

/**
 * Bliss adapter (chocapic13-lineage packs sharing Bliss' LPV table layout).
 * <p>
 * Bliss derives colored light from a per-ID table built by
 * {@code dimensions/setup.csh}: for every {@code block.properties} ID (blocks,
 * items and entities share one 2048-entry table) a compute pass writes an RGB
 * light color plus a 0..15 range into {@code imgBlockData}. The LPV floodfill
 * propagates that color, so claiming one block ID and assigning it a pink
 * entry covers surface tint and floodfilled colored light in one edit.
 * <p>
 * Bliss' ID layout is sparse and fully enumerated: blocks end at 501, items
 * occupy 1000..1024, entities start at 1601, and the table guard rejects IDs
 * past 2047. The adapter claims a fresh slot instead of taking one over:
 * block ID {@link #TANK_BLOCK_ID} 600 — empty gap between blocks and items,
 * mapped to froglight-tier emission ({@code lightRange = 15}, default tint
 * mix weight of zero) with the same rose pink the other adapters use.
 * <p>
 * No handheld-light mapping on purpose: the carried tank item is an empty
 * container (the glowing molten salt only exists in the placed, lit block),
 * so it must not cast held-item light — same policy as every other adapter
 * here.
 * <p>
 * The shader edit is a pure insertion anchored on the unique line that ends
 * Bliss' emitter chain in {@code setup.csh}; anchor drift across pack versions
 * fails open.
 */
public final class BlissColoredLightAdapter implements ShaderColoredLightAdapter {

	/** Fresh block.properties id claimed by the tank's lit states. */
	public static final int TANK_BLOCK_ID = 600;
	/** Warm rose pink, matched across this mod's other pack adapters. */
	public static final String PINK_COLOR_LITERAL = "vec3(1.00, 0.35, 0.55)";
	/** Matches BLOCK_FROGLIGHT_* / BLOCK_GLOWSTONE emission range. */
	public static final float TANK_LIGHT_RANGE = 15.0F;

	/** Unique comment line that ends Bliss' emitter chain in setup.csh. */
	private static final String EMITTER_TABLE_END_ANCHOR = "// hack to increase light (if set)";

	private static final NamespacedId TANK_ID =
		new NamespacedId("createmanaindustry", "molten_salt_fuel_tank");

	@Override
	public boolean appliesTo(String shaderPackName) {
		return shaderPackName.toLowerCase().contains("bliss");
	}

	@Override
	public void patchBlockIdMap(BlockIdMapContext ctx) {
		var blocks = ctx.blockProperties();
		if (blocks == null || blocks.isEmpty()) {
			CreateManaIndustry.LOGGER.warn("Fuel tank pink light compat skipped: no block.properties map");
			return;
		}
		if (blocks.containsKey(TANK_BLOCK_ID)) {
			CreateManaIndustry.LOGGER.warn(
				"Fuel tank pink light compat skipped: block id {} is already assigned in this pack", TANK_BLOCK_ID);
			return;
		}
		blocks.put(TANK_BLOCK_ID, List.of(new BlockEntry(TANK_ID, Map.of("lit", "true"))));

		// No handheld-light mapping on purpose: the carried tank item is an
		// empty container — only the placed, lit block holds glowing molten
		// salt — so it must not cast held-item light.

		CreateManaIndustry.LOGGER.info(
			"Mapped molten_salt_fuel_tank[lit=true] to Bliss block id {}", TANK_BLOCK_ID);
	}

	@Override
	public String transformShader(String fileName, String source) {
		if (!"setup.csh".equals(fileName))
			return null;

		// Bliss' emitter chain is a series of "if (blockId == X) { lightColor =
		// ...; lightRange = ...; }" blocks ending right before the exposure /
		// saturation step; inserting there covers the claimed block id with one
		// entry.
		String insertion =
			"        if (blockId == " + TANK_BLOCK_ID + ") {\n"
				+ "            lightColor = " + PINK_COLOR_LITERAL + ";\n"
				+ "            lightRange = " + TANK_LIGHT_RANGE + ";\n"
				+ "        } // Molten Salt Fuel Tank [CMI]\n";
		return insertBeforeUniqueLine(source, EMITTER_TABLE_END_ANCHOR, insertion, "LPV emitter entry");
	}

	/**
	 * Inserts {@code insertion} on its own line directly before the line
	 * containing the (exactly once occurring) {@code uniqueAnchor} text,
	 * matching the source file's own line endings. Returns {@code null} if the
	 * anchor is missing or ambiguous.
	 */
	private static String insertBeforeUniqueLine(String source, String uniqueAnchor, String insertion, String label) {
		int first = source.indexOf(uniqueAnchor);
		if (first < 0) {
			CreateManaIndustry.LOGGER.warn("Fuel tank pink light compat skipped: {} anchor not found", label);
			return null;
		}
		if (source.indexOf(uniqueAnchor, first + 1) >= 0) {
			CreateManaIndustry.LOGGER.warn("Fuel tank pink light compat skipped: {} anchor is ambiguous", label);
			return null;
		}
		int lineStart = source.lastIndexOf('\n', first) + 1;
		String newline = source.contains("\r\n") ? "\r\n" : "\n";
		String anchoredInsertion = insertion.replace("\n", newline);
		CreateManaIndustry.LOGGER.info("Inserted Bliss {} into setup.csh for the molten salt fuel tank", label);
		return source.substring(0, lineStart) + anchoredInsertion + source.substring(lineStart);
	}
}
