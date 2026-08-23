package com.iridium126.createmanaindustry.client.render.shaderpack;

import java.util.List;
import java.util.Map;

import com.iridium126.createmanaindustry.CreateManaIndustry;

import net.irisshaders.iris.shaderpack.materialmap.BlockEntry;
import net.irisshaders.iris.shaderpack.materialmap.NamespacedId;

/**
 * iterationT adapter for the molten salt fuel tank's lit glow.
 * <p>
 * iterationT routes terrain emissives through a fixed material-id table, not a
 * floodfilled light-propagation volume (the pack has no voxel pipeline at all):
 * block.properties ids reach Terrain_VS.glsl as mc_Entity.x, where a switch maps
 * them to v_materialIDs (MATID_* defines in Lib/Settings.glsl); the id survives
 * in colortex5 and CalculateMasks() in GbufferData.glsl turns it back into
 * per-material flags that Blocklight.glsl consumes — a slot in lightSourceMask
 * so sources are not darkened by their own lightmap clamp, and per-mask weights
 * in TextureLighting() whose emission colour is the global colorTorchlight.
 * <p>
 * The pack's own modern-emissive cluster (soul fire 7100, amethyst 7101,
 * oxidized bulb 7102) shows exactly which hooks an emissive needs, and this
 * adapter mirrors them for the tank with fresh values:
 * <ul>
 *   <li>block id 7110 — first free id above the 710x cluster;</li>
 *   <li>material id 29 — first free slot after MATID_OXIDIZED_BULB (28) and
 *       below the particle range (40);</li>
 *   <li>a mask.tank flag next to its siblings in both the struct and
 *       CalculateMasks();</li>
 *   <li>a lightSourceMask term, a texture-emission weight between the torch and
 *       glowstone tiers, and a pink override of the otherwise global torch
 *       colour so the glowing salt reads as this mod's rose pink.</li>
 * </ul>
 * Scope note: without a floodfill there is no way to tint neighbours' lighting,
 * so the adaptation covers the tank's own surface emission only — everything
 * the pack itself offers an emissive hook for.
 * <p>
 * This adapter additionally carries the volumetric-layer bridge for the pack:
 * iterationT's gbuffer colour holds unlit albedo and its lighting pass writes
 * the lit frame elsewhere, so scene-colour draws of this mod's effects would
 * be relit as surface albedo (visually collapsing in shade and crushed by the
 * RGBA8 precision). Instead both effects render into unused spare buffers —
 * the mist layer into colortex9 and the fuel rod ring into colortex10 — and a
 * pair of pure insertions teaches {@code composite.fsh} to declare the
 * samplers and merge those layers over its finished lit image, routing them
 * through the pack's bloom, TAA, auto-exposure and AgX tonemap like native
 * radiance.
 * <p>
 * All shader edits are pure insertions anchored on unique lines; any anchor
 * drift across pack versions fails open.
 */
public final class IterationTColoredLightAdapter implements ShaderColoredLightAdapter {

	/** Fresh block.properties id claimed by the tank's lit states. */
	public static final int TANK_BLOCK_ID = 7110;
	/** Fresh MATID the block id converts to (Settings.glsl table slot). */
	public static final float TANK_MATID = 29.0F;
	/** Warm rose pink, matched across this mod's other pack adapters. */
	public static final String PINK_COLOR_LITERAL = "vec3(1.00, 0.35, 0.55)";
	/** Between the torch (0.03) and glowstone (0.016) emission weight tiers. */
	public static final float TANK_EMISSION_WEIGHT = 0.02F;

	/** Global-scope anchor for the mist sampler declaration in composite.fsh. */
	private static final String OUTPUT_DECL_ANCHOR =
		"layout(location = 0) out vec4 compositeOutput1;";

	/**
	 * The tail of composite.fsh's main(): the lit-scene assignment at the end of
	 * the non-sky branch plus that branch's closing brace. Anchoring on the pair
	 * and inserting right after it places the mist merge inside main(), after
	 * BOTH branch exits — so mist composites over solid geometry and over sky
	 * alike — while staying clear of main's own closing brace (a bare block at
	 * translation-unit level would not parse).
	 */
	private static final String COMPOSITE_TAIL_ANCHOR =
		"\t\tcompositeOutput1 = vec4(finalComposite, 0.0);\n\t}";

	/**
	 * Radiance scale converting this mod's calibrated mist units into the
	 * pack's raw linear light space. colortex1 stores
	 * {@code LinearToCurve(litRadiance / MAIN_OUTPUT_FACTOR)} (2048), and the
	 * pack's own auto-exposure weighting operates on curve-linear luminances in
	 * the 6.25e-8 .. 1.55e-6 band — i.e. typical scene luminance sits around
	 * 1e-6 there. A scale of 1e-5 puts a fully-emissive dense mist near
	 * sunlit-surface brightness while thin wisps stay subtle, matching how the
	 * same fluids read under this mod's other pack adapters.
	 */
	public static final float MIST_RADIANCE_SCALE = 1.0e-5F;

	/**
	 * Radiance scale for the fuel rod ring layer (colortex10). Same units and
	 * reasoning as {@link #MIST_RADIANCE_SCALE}; set slightly hotter so the
	 * reactor ring reads as the most intense emitter in its scene, matching
	 * how it reads under this mod's other pack adapters.
	 */
	public static final float RING_RADIANCE_SCALE = 2.0e-5F;

	private static final String MIST_MERGE_BLOCK =
		"\n"
			+ "\t// [CMI] layer merge — begin (injected by Create: Mana Industry)\n"
			+ "\t{\n"
			+ "\t\tvec4 cmiMist = texelFetch(colortex9, texelCoord, 0);\n"
			+ "\t\tvec3 cmiRing = texelFetch(colortex10, texelCoord, 0).rgb;\n"
			+ "\t\tif (cmiMist.a > 0.0001 || cmiRing != vec3(0.0)){\n"
			+ "\t\t\tvec3 cmiScene = CurveToLinear(compositeOutput1.rgb);\n"
			+ "\t\t\tif (cmiMist.a > 0.0001)\n"
			+ "\t\t\t\tcmiScene = cmiScene * (1.0 - cmiMist.a) + cmiMist.rgb * "
			+ scaleLiteral(MIST_RADIANCE_SCALE) + ";\n"
			+ "\t\t\tcmiScene += cmiRing * " + scaleLiteral(RING_RADIANCE_SCALE) + ";\n"
			+ "\t\t\tcompositeOutput1 = vec4(LinearToCurve(cmiScene), compositeOutput1.a);\n"
			+ "\t\t}\n"
			+ "\t}\n"
			+ "\t// [CMI] layer merge — end";

	private static final NamespacedId TANK_ID =
		new NamespacedId("createmanaindustry", "molten_salt_fuel_tank");

	@Override
	public boolean appliesTo(String shaderPackName) {
		return shaderPackName.toLowerCase().contains("iterationt");
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
			"Mapped molten_salt_fuel_tank[lit=true] to iterationT block id {} (material id {})",
			TANK_BLOCK_ID, TANK_MATID);
	}

	@Override
	public String transformShader(String fileName, String source) {
		switch (fileName) {
			case "Settings.glsl":
				return insertBefore(source, "#define MATID_OXIDIZED_BULB",
					"\t#define MATID_TANK\t\t\t\t" + matIdLiteral(TANK_MATID)
						+ " // Molten Salt Fuel Tank [CMI]\n",
					"MATID_TANK define");
			case "Terrain_VS.glsl":
				return insertBefore(source, "case 7102:",
					"\t\tcase " + TANK_BLOCK_ID + ":\n"
						+ "\t\t\tv_materialIDs = MATID_TANK;\n"
						+ "\t\t\tbreak;\n\n",
					"block id material mapping");
			case "GbufferData.glsl":
				String structPatched = insertBefore(source, "float oxidizedBulb;",
					"\tfloat tank; // Molten Salt Fuel Tank [CMI]\n",
					"mask struct field");
				if (structPatched == null)
					return null;
				return insertBefore(structPatched, "mask.oxidizedBulb",
					"\tmask.tank\t\t\t\t= float(materialIDs == MATID_TANK); // Molten Salt Fuel Tank [CMI]\n",
					"mask assignment");
			case "Blocklight.glsl":
				return patchBlocklight(source);
			case "composite.fsh":
				return patchComposite(source);
			default:
				return null;
		}
	}

	/**
	 * Applies the three Blocklight.glsl edits in one pass: the light-source
	 * exemption (same effect as adding mask.tank to the saturate()d sum above,
	 * expressed as one self-contained statement anchored before its consumer —
	 * anchoring inside the multi-line saturate() argument list would splice a
	 * statement into an expression), the texture-emission weight beside its
	 * sibling terms, and the pink override right after the statement that
	 * assigns every other emissive the global torch colour. Any anchor surprise
	 * leaves the whole file untouched.
	 */
	private static String patchBlocklight(String source) {
		String patched = insertBefore(source,
			"lightmap = min(lightmap, 1.0 - 0.13 * lightSourceMask);",
			"\tlightSourceMask = max(lightSourceMask, mask.tank); // Molten Salt Fuel Tank [CMI]\n",
			"light source exemption");
		if (patched == null)
			return null;
		patched = insertBefore(patched, "* 4.0;",
			"\t\t\t  blockLightingMask += \tmask.tank \t\t\t\t*" + TANK_EMISSION_WEIGHT
				+ "; // Molten Salt Fuel Tank [CMI]\n",
			"emission weight");
		if (patched == null)
			return null;
		return insertAfter(patched, "if (blockLightingMask > 0.0) blockLighting = colorTorchlight;",
			"\t\tif (mask.tank > 0.5) blockLighting = " + PINK_COLOR_LITERAL
				+ "; // Molten Salt Fuel Tank [CMI]\n",
			"pink emission colour");
	}

	/**
	 * Applies the two composite.fsh edits in one pass: the global-scope sampler
	 * declarations for both CMI layers (colortex9 mist, colortex10 fuel rod
	 * ring) beside the output declaration, and the layer-merge block between
	 * the lighting branch's close and main's own — after both branch exits, so
	 * sky and solid pixels composite alike. Any anchor surprise leaves the
	 * whole file untouched.
	 */
	private static String patchComposite(String source) {
		String declared = insertAfter(source, OUTPUT_DECL_ANCHOR,
			"\nuniform sampler2D colortex9; // [CMI] mist layer\n"
				+ "uniform sampler2D colortex10; // [CMI] fuel rod ring layer\n",
			"layer sampler declarations");
		if (declared == null)
			return null;
		return insertAfterMatch(declared, COMPOSITE_TAIL_ANCHOR, MIST_MERGE_BLOCK, "layer merge");
	}

	/**
	 * Inserts {@code insertion} directly after the END of the (exactly once
	 * occurring) multi-line {@code anchor} text — unlike {@link #insertAfter},
	 * which anchors on a single line and inserts after its newline. Used for the
	 * composite.fsh tail, whose insertion point sits between the anchor's last
	 * two lines. Returns {@code null} if the anchor is missing or ambiguous.
	 */
	private static String insertAfterMatch(String source, String anchor, String insertion, String label) {
		int at = uniqueIndexOf(source, anchor, label);
		if (at < 0)
			return null;

		int insertAt = at + anchor.length();
		String newline = source.contains("\r\n") ? "\r\n" : "\n";
		String anchoredInsertion = insertion.replace("\n", newline);
		CreateManaIndustry.LOGGER.info("Patched iterationT {} for the molten salt fuel tank", label);
		return source.substring(0, insertAt) + anchoredInsertion + source.substring(insertAt);
	}

	/** Renders {@code 1.0e-5} from {@code 1.0e-5F}, matching GLSL literal style. */
	private static String scaleLiteral(float value) {
		return String.valueOf(value).replace("E", "e");
	}

	/** Renders {@code 29.0} from {@code 29}, matching the pack's define style. */
	private static String matIdLiteral(float value) {
		return value == Math.floor(value) ? (int) value + ".0" : String.valueOf(value);
	}

	/**
	 * Inserts the insertion on its own line directly before the line containing
	 * the (exactly once occurring) anchor text. Returns null if the anchor is
	 * missing or ambiguous.
	 */
	private static String insertBefore(String source, String anchor, String insertion, String label) {
		int at = uniqueIndexOf(source, anchor, label);
		if (at < 0)
			return null;

		int lineStart = source.lastIndexOf("\n", at) + 1;
		CreateManaIndustry.LOGGER.info("Patched iterationT {} for the molten salt fuel tank", label);
		return source.substring(0, lineStart) + insertion + source.substring(lineStart);
	}

	/**
	 * Inserts the insertion directly after the line containing the (exactly once
	 * occurring) anchor text. Returns null if the anchor is missing or ambiguous.
	 */
	private static String insertAfter(String source, String anchor, String insertion, String label) {
		int at = uniqueIndexOf(source, anchor, label);
		if (at < 0)
			return null;

		int lineEnd = source.indexOf("\n", at);
		int insertAt = lineEnd < 0 ? source.length() : lineEnd + 1;
		CreateManaIndustry.LOGGER.info("Patched iterationT {} for the molten salt fuel tank", label);
		return source.substring(0, insertAt) + insertion + source.substring(insertAt);
	}

	/** Index of the anchor when it occurs exactly once, else -1 with a warning. */
	private static int uniqueIndexOf(String source, String anchor, String label) {
		int first = source.indexOf(anchor);
		if (first < 0) {
			CreateManaIndustry.LOGGER.warn(
				"Fuel tank pink light compat skipped: iterationT {} anchor not found", label);
			return -1;
		}
		if (source.indexOf(anchor, first + 1) >= 0) {
			CreateManaIndustry.LOGGER.warn(
				"Fuel tank pink light compat skipped: iterationT {} anchor is ambiguous", label);
			return -1;
		}
		return first;
	}
}
