package com.iridium126.createmanaindustry.client.shadercompat;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import com.iridium126.createmanaindustry.CreateManaIndustry;

import it.unimi.dsi.fastutil.objects.Object2IntMap;
import it.unimi.dsi.fastutil.objects.Object2IntOpenHashMap;
import net.irisshaders.iris.shaderpack.materialmap.BlockEntry;
import net.irisshaders.iris.shaderpack.materialmap.NamespacedId;

/**
 * Photon adapter (covers stock Photon and its Clrwl variants).
 * <p>
 * Photon's colored lights use a numeric block ID table (block.properties IDs
 * 10032..10063 = light emitters) whose colors live in
 * {@code shaders/include/lighting/lpv/light_colors.glsl} as a 32-entry LUT
 * uploaded by {@code lpv/setup.csh}. Every emitter slot is taken, so instead
 * of extending the table we take over an existing slot whose color is
 * duplicated elsewhere:
 * <ul>
 *   <li>slot 10050 (index 18) currently holds the candles, colored
 *       {@code vec3(1.00, 0.57, 0.30) * 8};</li>
 *   <li>slot 10036 (torches) has the exact same color.</li>
 * </ul>
 * The candles are re-homed onto slot 10036 (visually identical, block AND
 * held-item maps), LUT index 18 is recolored pink and
 * {@code createmanaindustry:molten_salt_fuel_tank:lit=true} claims slot 10050.
 */
public final class PhotonColoredLightAdapter implements ShaderColoredLightAdapter {

	public static final int CANDLE_EMITTER_ID = 10050;
	public static final int TORCH_EMITTER_ID = 10036;
	public static final int CANDLE_LUT_INDEX = CANDLE_EMITTER_ID - 10032;
	/** Warm rose pink; matched to the froglight tier (*8.0) of Photon's LUT. */
	public static final String PINK_LUT_ENTRY = "vec3(1.00, 0.35, 0.55) * 8.0";

	private static final Pattern ARRAY_HEAD = Pattern.compile(
		"const\\s+vec3\\[32\\]\\s+light_color\\s*=\\s*vec3\\[32\\]\\s*\\(");

	private String lastPackName = null;
	private boolean lastApplies = false;

	@Override
	public boolean appliesTo(String shaderPackName) {
		if (shaderPackName.equals(lastPackName))
			return lastApplies;

		lastApplies = shaderPackName.toLowerCase().contains("photon");
		lastPackName = shaderPackName;
		if (lastApplies)
			CreateManaIndustry.LOGGER.info("Photon pack detected ({}): fuel tank pink light compat active", shaderPackName);
		return lastApplies;
	}

	@Override
	public void patchBlockIdMap(BlockIdMapContext ctx) {
		var blocks = ctx.blockProperties();
		if (blocks == null || blocks.isEmpty()) {
			CreateManaIndustry.LOGGER.warn("Fuel tank pink light compat skipped: no block.properties map");
			return;
		}

		// --- blocks: candles 10050 -> 10036, tank(lit=true) -> 10050 ----------
		// Resilient takeover: everything occupying the candle slot moves to the
		// torch slot wholesale (both slots share the exact same LUT color, so
		// whatever lives there keeps its appearance), freeing the slot for the
		// tank. Entry-level content checks proved unreliable across Iris builds.
		List<BlockEntry> occupants = blocks.get(CANDLE_EMITTER_ID);
		if (occupants == null || occupants.isEmpty()) {
			CreateManaIndustry.LOGGER.warn(
				"Fuel tank pink light compat skipped: emitter slot {} is empty (blockIds={})",
				CANDLE_EMITTER_ID, blocks.size());
			return;
		}

		List<BlockEntry> torches = blocks.get(TORCH_EMITTER_ID);
		List<BlockEntry> merged = new ArrayList<>(torches != null ? torches.size() + occupants.size() : occupants.size());
		if (torches != null)
			merged.addAll(torches);
		merged.addAll(occupants);

		blocks.put(TORCH_EMITTER_ID, List.copyOf(merged));
		blocks.put(CANDLE_EMITTER_ID, List.of(new BlockEntry(
			new NamespacedId("createmanaindustry", "molten_salt_fuel_tank"), Map.of("lit", "true"))));

		// --- items: keep held-candle light golden via the torch slot ---------
		Object2IntOpenHashMap<NamespacedId> remappedItems = new Object2IntOpenHashMap<>();
		remappedItems.defaultReturnValue(-1);
		for (Object2IntMap.Entry<NamespacedId> entry : ctx.itemIds().object2IntEntrySet()) {
			int value = entry.getIntValue();
			NamespacedId id = entry.getKey();
			if (value == CANDLE_EMITTER_ID && "minecraft".equals(id.getNamespace())
					&& id.getName().startsWith("candle"))
				value = TORCH_EMITTER_ID;
			remappedItems.put(id, value);
		}
		ctx.setItemIds(remappedItems);

		CreateManaIndustry.LOGGER.info(
			"Mapped molten_salt_fuel_tank[lit=true] to Photon emitter {} ({} occupant(s) re-homed to {})",
			CANDLE_EMITTER_ID, occupants.size(), TORCH_EMITTER_ID);
	}

	@Override
	public String transformShader(String fileName, String source) {
		if (!"light_colors.glsl".equals(fileName))
			return null;
		return patchLightColors(source);
	}

	/**
	 * Rewrites the light color LUT so index {@link #CANDLE_LUT_INDEX} is pink.
	 * The whole array body is rebuilt rather than patched by line number:
	 * entries are located structurally and the count must be exactly 32.
	 * Returns {@code null} to signal "leave the source untouched".
	 */
	private static String patchLightColors(String source) {
		Matcher head = ARRAY_HEAD.matcher(source);
		if (!head.find())
			return fail("light color array header not found");

		int bodyStart = head.end();
		int bodyEnd = source.indexOf(");", bodyStart);
		if (bodyEnd < 0)
			return fail("light color array terminator not found");

		String body = source.substring(bodyStart, bodyEnd);
		String[] lines = body.split("\\R", -1);

		int[] entryLineIndices = new int[32];
		int count = 0;
		for (int i = 0; i < lines.length && count < entryLineIndices.length; i++) {
			String trimmed = lines[i].trim();
			if (trimmed.startsWith("vec3(") && !trimmed.startsWith("//"))
				entryLineIndices[count++] = i;
		}

		if (count != 32)
			return fail("expected 32 LUT entries, found " + count);

		int target = entryLineIndices[CANDLE_LUT_INDEX];
		// Entries carry a trailing comment, so any comma sits BEFORE it; detect
		// the separator structurally instead of via endsWith(","). Every entry
		// except the array's last element must be comma-separated.
		String trimmedTarget = lines[target].trim();
		String indent = lines[target].substring(0, lines[target].indexOf(trimmedTarget));
		boolean isLastEntry = CANDLE_LUT_INDEX == 31;
		String comma = isLastEntry ? "" : ",";
		lines[target] = indent + PINK_LUT_ENTRY + comma + " // Molten salt fuel tank (Create: Mana Industry)";

		// Preserve the source's own line endings.
		String newline = body.contains("\r\n") ? "\r\n" : "\n";
		StringBuilder rebuiltBody = new StringBuilder(body.length() + 64);
		for (int i = 0; i < lines.length; i++) {
			rebuiltBody.append(lines[i]);
			if (i < lines.length - 1)
				rebuiltBody.append(newline);
		}

		CreateManaIndustry.LOGGER.info("Recolored Photon light LUT index {} to pink for the molten salt fuel tank",
			CANDLE_LUT_INDEX);
		return source.substring(0, bodyStart) + rebuiltBody + source.substring(bodyEnd);
	}

	private static String fail(String reason) {
		CreateManaIndustry.LOGGER.warn("Fuel tank pink light compat skipped: {} (pack left untouched)", reason);
		return null;
	}
}
