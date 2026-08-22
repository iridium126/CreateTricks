package com.iridium126.createmanaindustry.client.render;

import java.util.Locale;

import com.iridium126.createmanaindustry.CreateManaIndustry;
import com.iridium126.createmanaindustry.client.shadercompat.ShaderColoredLightAdapters;

/**
 * Per-shaderpack mist injection profile. Packs disagree about which colour
 * buffer holds the working scene colour at the after-translucent point where
 * the Iris hook draws:
 * <ul>
 * <li>Most packs (BSL, Photon, Complementary) accumulate the frame in
 * {@code colortex0}, so drawing there is picked up by their composite chain.</li>
 * <li>Bliss-family packs keep their scene colour in the gbuffer layers and
 * use {@code colortex0} as a clouds/volumetric-effects buffer that a composite
 * pass overwrites <em>before</em> any later pass reads it — a scene-colour draw
 * there never reaches the screen. Those packs get the translucent-layer
 * profile instead: the mist is composed into {@code colortex2}, the translucent
 * colour layer their composite chain merges into the frame.</li>
 * </ul>
 * Detection is name-based for now ({@code bliss}), cached per pack name.
 */
public final class MistInjectionProfiles {

    public enum Profile {
        /** Draw into colortex0 and composite over the scene colour (default). */
        SCENE_COLOR,
        /** Draw into colortex2 with premultiplied under-operator semantics. */
        TRANSLUCENT_LAYER
    }

    private static String lastPackName;
    private static Profile lastProfile = Profile.SCENE_COLOR;

    private MistInjectionProfiles() {}

    /** The injection profile for the currently active shaderpack. */
    public static Profile activeProfile() {
        String name = ShaderColoredLightAdapters.activePackName();
        if (name.equals(lastPackName))
            return lastProfile;
        Profile profile = name.toLowerCase(Locale.ROOT).contains("bliss")
                ? Profile.TRANSLUCENT_LAYER
                : Profile.SCENE_COLOR;
        CreateManaIndustry.LOGGER.info("Mist injection profile for shaderpack '{}': {}", name,
                profile == Profile.TRANSLUCENT_LAYER ? "translucent-layer (Bliss-family)" : "scene-color");
        lastPackName = name;
        lastProfile = profile;
        return profile;
    }
}