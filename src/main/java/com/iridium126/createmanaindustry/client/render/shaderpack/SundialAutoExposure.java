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
 * Resolves the constant exposure pre-compensation the Sundial HDR-scene folds
 * need. {@code Composite14} multiplies the tonemapped frame by
 * <pre>
 *     E = exp2(-log2(averageBrightness + 1e-5) * AVERAGE_EXPOSURE_STRENGTH)
 *         * 0.2 * exp2(EXPOSURE_VALUE)
 * </pre>
 * where {@code averageBrightness} nominally comes from colortex7 texel
 * {@code (0, 0)}.{@code w}. However, Sundial redirects that sampler to its
 * atmosphere-transmittance LUT for the deferred and composite stages
 * ({@code texture.deferred.colortex7} / {@code texture.composite.colortex7} in
 * {@code shaders.properties}; Iris renames the sampler onto the custom texture,
 * see {@code TextureTransformer}), and the LUT's RGB16 format has no alpha
 * channel — sampled {@code .w} is a constant 1.0. The adaptation term is
 * therefore inert and the multiply the frame actually experiences is
 * <pre>
 *     E = 0.2 * exp2(EXPOSURE_VALUE)
 * </pre>
 * Radiance folded into colortex3 passes through that constant, so the folds
 * pre-divide by it ({@link #compensationScale()}) to keep their calibrated
 * brightness stable. Reading the real colortex7 buffer instead would chase an
 * average nobody applies — that mistake made daytime mist several times too
 * dense.
 * <p>
 * {@code EXPOSURE_VALUE} is user-tunable, so its shipped literal is parsed
 * from {@code programs/composite/Composite14.frag} and overridden by the pack
 * option file through {@link ActivePackOptions#doubleValue}. Any structural
 * surprise falls back to the shipped default, never throws.
 */
public final class SundialAutoExposure {

    /** The {@code * 0.2} factor baked into Composite14's exposure multiply. */
    private static final float BASE_MULTIPLY = 0.2F;

    private static final Pattern EXPOSURE_VALUE = Pattern.compile(
            "(?m)^\\s*#define\\s+EXPOSURE_VALUE\\s*([-+]?[0-9]*\\.?[0-9]+)");

    /** Shipped default of the EXPOSURE_VALUE define, used when unparseable. */
    private static final float DEFAULT_EXPOSURE_VALUE = 0.0F;

    private static final String COMPOSITE14 = "shaders/programs/composite/Composite14.frag";

    private static String lastName;
    private static float lastScale = compensationScale(DEFAULT_EXPOSURE_VALUE);

    private SundialAutoExposure() {}

    /**
     * The constant factor the HDR-scene folds must pre-multiply their added
     * radiance by so it survives the pack's exposure multiply, cached per pack
     * name. Bounded like every other adapter's compensation.
     */
    public static float compensationScale() {
        String name = ShaderColoredLightAdapters.activePackName();
        if (!name.equals(lastName)) {
            lastName = name;
            lastScale = compensationScale(loadExposureValue(name));
        }
        return lastScale;
    }

    private static float compensationScale(float exposureValue) {
        float e = BASE_MULTIPLY * (float) Math.exp(exposureValue * Math.log(2.0));
        return Math.max(0.125F, Math.min(64.0F, 1.0F / e));
    }

    private static float loadExposureValue(String packName) {
        float exposureValue = DEFAULT_EXPOSURE_VALUE;
        try {
            if (packName == null || packName.isEmpty() || packName.equals("(internal)"))
                return exposureValue;
            Path pack = Iris.getShaderpacksDirectory().resolve(packName);
            String source = readComposite14(pack);
            if (source == null) {
                CreateManaIndustry.LOGGER.debug(
                        "Sundial auto-exposure: {} not found in '{}'; using shipped defaults",
                        COMPOSITE14, packName);
            } else {
                Matcher m = EXPOSURE_VALUE.matcher(source);
                if (m.find()) {
                    try {
                        exposureValue = Float.parseFloat(m.group(1));
                    } catch (NumberFormatException ignored) {
                        // malformed literal — keep the shipped default
                    }
                }
            }
        } catch (IOException | RuntimeException | LinkageError e) {
            CreateManaIndustry.LOGGER.warn(
                    "Sundial auto-exposure parameters unreadable; using shipped defaults", e);
        }
        // User override wins over the shipped literal (Iris persists only keys
        // the user actually changed).
        return (float) ActivePackOptions.doubleValue("EXPOSURE_VALUE", exposureValue);
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
