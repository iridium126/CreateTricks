package com.iridium126.createmanaindustry.client.render.mist;

import java.util.Locale;

import com.iridium126.createmanaindustry.CreateManaIndustry;
import com.iridium126.createmanaindustry.client.render.shaderpack.ShaderColoredLightAdapters;

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
 * <li>Sundial rebuilds the visible image even further from the source: its
 * deferred stage accumulates the lit scene into {@code colortex3}, and
 * {@code Composite0} zeroes {@code colortex0.rgb} outright (only the weather
 * alpha survives there) before {@code Composite14} tonemaps {@code colortex3}
 * into the display buffer. A scene-colour draw is therefore dead on arrival;
 * the HDR-scene profile folds the mist into {@code colortex3} instead, where
 * the pack's own cloud compositing, SSR, TAA, bloom and tonemapping process it
 * exactly like native scene radiance.</li>
 * </ul>
 * Detection is name-based for now ({@code bliss}, {@code sundial}), cached per
 * pack name.
 */
public final class MistInjectionProfiles {

    public enum Profile {
        /** Draw into colortex0 and composite over the scene colour (default). */
        SCENE_COLOR,
        /** Draw into colortex2 with premultiplied under-operator semantics. */
        TRANSLUCENT_LAYER,
        /** Fold into the deferred-lit HDR scene buffer (Sundial colortex3). */
        HDR_SCENE
    }

    private static String lastPackName;
    private static Profile lastProfile = Profile.SCENE_COLOR;

    private MistInjectionProfiles() {}

    /** The injection profile for the currently active shaderpack. */
    public static Profile activeProfile() {
        String name = ShaderColoredLightAdapters.activePackName();
        if (name.equals(lastPackName))
            return lastProfile;
        String lower = name.toLowerCase(Locale.ROOT);
        Profile profile;
        if (lower.contains("bliss"))
            profile = Profile.TRANSLUCENT_LAYER;
        else if (lower.contains("sundial"))
            profile = Profile.HDR_SCENE;
        else
            profile = Profile.SCENE_COLOR;
        CreateManaIndustry.LOGGER.info("Mist injection profile for shaderpack '{}': {}", name,
                switch (profile) {
                    case TRANSLUCENT_LAYER -> "translucent-layer (Bliss-family)";
                    case HDR_SCENE -> "hdr-scene (Sundial-family)";
                    case SCENE_COLOR -> "scene-color";
                });
        lastPackName = name;
        lastProfile = profile;
        return profile;
    }
}