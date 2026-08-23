package com.iridium126.createmanaindustry.client.render.shaderpack;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Enumeration;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

import com.iridium126.createmanaindustry.CreateManaIndustry;

import net.irisshaders.iris.Iris;

/**
 * Resolves Sundial's auto-exposure parameters so the HDR-scene injection folds
 * can pre-compensate the radiance they add. {@code Composite14} multiplies the
 * tonemapped frame by
 * <pre>
 *     E = exp2(-log2(averageBrightness + 1e-5) * AVERAGE_EXPOSURE_STRENGTH)
 *         * 0.2 * exp2(EXPOSURE_VALUE)
 * </pre>
 * where {@code averageBrightness} is the adapted scalar Composite10 leaves in
 * colortex7 texel {@code (0, 0)}.{@code w}. Radiance we fold into colortex3
 * passes through that multiply like native scene light, which makes fixed
 * calibrated values fade out in bright daylight — multiplying them by
 * {@code 1/E} (center-pixel terms; the vignette is deliberately not mirrored)
 * keeps their on-screen weight stable across day, night and weather.
 * <p>
 * The shipped defines are parsed from {@code programs/composite/Composite14.frag}
 * (the only file carrying them); user overrides come from the pack option file
 * through {@link ActivePackOptions#doubleValue}. Any structural surprise falls
 * back to the shipped defaults, never throws.
 */
public final class SundialAutoExposure {

    /** {@link #resolveForCurrentPack()} result: the two exposure defines. */
    public record Params(float strength, float exposureValue) {}

    private static final Pattern STRENGTH = Pattern.compile(
            "#define\\s+AVERAGE_EXPOSURE_STRENGTH\\s*([-+]?[0-9]*\\.?[0-9]+)");
    private static final Pattern EXPOSURE_VALUE = Pattern.compile(
            "(?m)^\\s*#define\\s+EXPOSURE_VALUE\\s*([-+]?[0-9]*\\.?[0-9]+)");

    /** Shipped defaults of the two defines, used when unparseable. */
    private static final float DEFAULT_STRENGTH = 0.60F;
    private static final float DEFAULT_EXPOSURE_VALUE = 0.0F;

    private static final String COMPOSITE14 = "shaders/programs/composite/Composite14.frag";

    private static String lastName;
    private static Params lastParams =
            new Params(DEFAULT_STRENGTH, DEFAULT_EXPOSURE_VALUE);

    private SundialAutoExposure() {}

    /** The active pack's exposure parameters, cached per pack name. */
    public static Params resolveForCurrentPack() {
        String name = ShaderColoredLightAdapters.activePackName();
        if (name.equals(lastName))
            return lastParams;
        lastName = name;
        lastParams = load(name);
        return lastParams;
    }

    private static Params load(String packName) {
        float strength = DEFAULT_STRENGTH;
        float exposureValue = DEFAULT_EXPOSURE_VALUE;
        try {
            if (packName == null || packName.isEmpty() || packName.equals("(internal)"))
                return new Params(strength, exposureValue);
            Path pack = Iris.getShaderpacksDirectory().resolve(packName);
            String source = readComposite14(pack);
            if (source == null) {
                CreateManaIndustry.LOGGER.debug(
                        "Sundial auto-exposure: {} not found in '{}'; using shipped defaults",
                        COMPOSITE14, packName);
            } else {
                strength = match(STRENGTH, source, strength);
                exposureValue = match(EXPOSURE_VALUE, source, exposureValue);
            }
        } catch (IOException | RuntimeException | LinkageError e) {
            CreateManaIndustry.LOGGER.warn(
                    "Sundial auto-exposure parameters unreadable; using shipped defaults", e);
        }
        // User overrides win over the shipped literals (Iris persists only keys
        // the user actually changed).
        strength = (float) ActivePackOptions.doubleValue("AVERAGE_EXPOSURE_STRENGTH", strength);
        exposureValue = (float) ActivePackOptions.doubleValue("EXPOSURE_VALUE", exposureValue);
        CreateManaIndustry.LOGGER.info(
                "Sundial auto-exposure compensation params: strength={}, exposureValue={}",
                strength, exposureValue);
        return new Params(strength, exposureValue);
    }

    private static float match(Pattern pattern, String source, float fallback) {
        Matcher m = pattern.matcher(source);
        if (!m.find())
            return fallback;
        try {
            return Float.parseFloat(m.group(1));
        } catch (NumberFormatException e) {
            return fallback;
        }
    }

    /** Reads {@value #COMPOSITE14} from a zipped or extracted pack, or null. */
    private static String readComposite14(Path pack) throws IOException {
        if (Files.isDirectory(pack)) {
            Path file = pack.resolve(COMPOSITE14);
            return Files.isRegularFile(file) ? Files.readString(file, StandardCharsets.UTF_8) : null;
        }
        if (!Files.isRegularFile(pack))
            return null;
        try (ZipFile zip = new ZipFile(pack.toFile())) {
            Enumeration<? extends ZipEntry> entries = zip.entries();
            while (entries.hasMoreElements()) {
                ZipEntry entry = entries.nextElement();
                if (!entry.isDirectory() && entry.getName().equalsIgnoreCase(COMPOSITE14)) {
                    return new String(zip.getInputStream(entry).readAllBytes(), StandardCharsets.UTF_8);
                }
            }
        }
        return null;
    }
}
