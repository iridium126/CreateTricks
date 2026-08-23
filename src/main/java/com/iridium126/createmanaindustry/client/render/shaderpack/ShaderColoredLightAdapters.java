package com.iridium126.createmanaindustry.client.render.shaderpack;

import com.iridium126.createmanaindustry.CreateManaIndustry;

import net.irisshaders.iris.Iris;

import java.util.List;

/**
 * Registry of shaderpack colored-light adapters. Add an adapter here to teach
 * the mixins a new pack's colored-light dialect; nothing else changes.
 */
public final class ShaderColoredLightAdapters {

	public static final List<ShaderColoredLightAdapter> ALL = List.of(
		new PhotonColoredLightAdapter(),
		new ComplementaryColoredLightAdapter(),
		new BlissColoredLightAdapter());

	private ShaderColoredLightAdapters() {
	}

	/** Best-effort read of the active shaderpack's display name; empty on failure. */
	public static String activePackName() {
		try {
			return Iris.getIrisConfig().getShaderPackName().orElse("");
		} catch (RuntimeException | LinkageError e) {
			CreateManaIndustry.LOGGER.debug("Could not read active shaderpack name", e);
			return "";
		}
	}
}