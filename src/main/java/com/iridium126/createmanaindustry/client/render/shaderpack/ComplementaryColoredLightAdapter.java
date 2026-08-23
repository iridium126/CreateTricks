package com.iridium126.createmanaindustry.client.render.shaderpack;

import java.util.List;
import java.util.Map;

import com.iridium126.createmanaindustry.CreateManaIndustry;

import net.irisshaders.iris.shaderpack.materialmap.BlockEntry;
import net.irisshaders.iris.shaderpack.materialmap.NamespacedId;

/**
 * Complementary (Unbound/Reborn) adapter for its "Advanced Color Tracing"
 * colored lighting ({@code COLORED_LIGHTING}, default off — player's choice).
 * <p>
 * Pipeline: block.properties big IDs are converted to small per-block "mat"
 * values by {@code GetVoxelIDs()} in {@code lib/voxelization/lightVoxelization.glsl};
 * the floodfill seeding pass then resolves colors through
 * {@code GetSpecialBlocklightColor(mat)} in {@code lib/colors/blocklightColors.glsl}
 * (RGB + alpha; alpha &gt; 0 would cast extra light ignoring the vanilla
 * lightmap, which we deliberately avoid because the tank's {@code lit=true}
 * state already gates emission).
 * <p>
 * Both CU tables claim every slot of their working range, so this adapter
 * claims a fresh pair instead of taking one over:
 * <ul>
 *   <li>block ID {@link #TANK_BLOCK_ID} 11000 — unassigned, multiple of 4
 *       (keeps solid classification), and inside a completely empty region
 *       of Complementary's IPBR branch tree;</li>
 *   <li>voxel mat {@link #TANK_VOXEL_MAT} 98 — first value above the highest
 *       handled color slot.</li>
 * </ul>
 * Both shader edits are pure insertions anchored on unique lines; any anchor
 * drift across pack versions fails open.
 */
public final class ComplementaryColoredLightAdapter implements ShaderColoredLightAdapter {

	/** Fresh block.properties id claimed by the tank's lit states. */
	public static final int TANK_BLOCK_ID = 11000;
	/** Fresh voxel mat the block id converts to. */
	public static final int TANK_VOXEL_MAT = 98;
	/** Warm rose pink, froglight-tier travel/dominance per CU's pow2 scale. */
	public static final String PINK_COLOR_ENTRY = "vec4(vec3(1.00, 0.35, 0.55) * 4.5, 0.0)";

	private static final String VOXEL_IDS_ANCHOR = "return 1; // Standard Block";
	private static final String VOXEL_IDS_INSERT =
		"\tif (mat == " + TANK_BLOCK_ID + ") return " + TANK_VOXEL_MAT + "; // Molten Salt Fuel Tank [CMI]\n";

	private static final String COLOR_FALLTHROUGH_ANCHOR = "return vec4(blocklightCol * 20.0, 0.0);";
	private static final String COLOR_INSERT =
		"\tif (mat == " + TANK_VOXEL_MAT + ") return " + PINK_COLOR_ENTRY + "; // Molten Salt Fuel Tank [CMI]\n";

	@Override
	public boolean appliesTo(String shaderPackName) {
		return shaderPackName.toLowerCase().contains("complementary");
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
		// Family signature (advisory only): CU maps lit candles to the 10900
		// cluster. Its absence doesn't block the patch — the tank claims a fresh
		// id and touches nothing shared — but it hints at a layout drift worth
		// rechecking.
		List<BlockEntry> candles = blocks.get(10900);
		if (!holdsOnlyLitCandles(candles)) {
			CreateManaIndustry.LOGGER.warn(
				"Fuel tank pink light compat: unexpected content at 10900 (expected lit candle cluster), continuing anyway");
		}

		blocks.put(TANK_BLOCK_ID, List.of(new BlockEntry(
			new NamespacedId("createmanaindustry", "molten_salt_fuel_tank"), Map.of("lit", "true"))));

		CreateManaIndustry.LOGGER.info(
			"Mapped molten_salt_fuel_tank[lit=true] to Complementary block id {} (voxel mat {})",
			TANK_BLOCK_ID, TANK_VOXEL_MAT);
	}

	private static boolean holdsOnlyLitCandles(List<BlockEntry> entries) {
		if (entries == null || entries.isEmpty())
			return false;
		for (BlockEntry entry : entries) {
			boolean candle = "minecraft".equals(entry.id().getNamespace())
				&& entry.id().getName().startsWith("candle");
			boolean lit = "true".equals(entry.propertyPredicates().get("lit"));
			if (!candle || !lit)
				return false;
		}
		return true;
	}

	@Override
	public String transformShader(String fileName, String source) {
		switch (fileName) {
			case "lightVoxelization.glsl":
				return insertBeforeUniqueLine(source, VOXEL_IDS_ANCHOR, VOXEL_IDS_INSERT,
					"GetVoxelIDs mapping");
			case "blocklightColors.glsl":
				return insertBeforeUniqueLine(source, COLOR_FALLTHROUGH_ANCHOR, COLOR_INSERT,
					"pink blocklight color");
			default:
				return null;
		}
	}

	/**
	 * Inserts {@code insertion} on its own line directly before the line
	 * containing the (exactly once occurring) {@code uniqueAnchor} text.
	 * Returns {@code null} if the anchor is missing or ambiguous.
	 */
	private static String insertBeforeUniqueLine(String source, String uniqueAnchor, String insertion, String label) {
		int first = source.indexOf(uniqueAnchor);
		if (first < 0)
			return fail(label + ": anchor not found");
		if (source.indexOf(uniqueAnchor, first + 1) >= 0)
			return fail(label + ": anchor is not unique");

		int lineStart = source.lastIndexOf('\n', first) + 1;

		CreateManaIndustry.LOGGER.info("Patched Complementary {} for the molten salt fuel tank", label);
		return source.substring(0, lineStart) + insertion + source.substring(lineStart);
	}

	private static String fail(String reason) {
		CreateManaIndustry.LOGGER.warn("Fuel tank pink light compat skipped: {} (pack left untouched)", reason);
		return null;
	}
}