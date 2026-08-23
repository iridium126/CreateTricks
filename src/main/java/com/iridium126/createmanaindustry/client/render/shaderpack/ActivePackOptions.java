package com.iridium126.createmanaindustry.client.render.shaderpack;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.util.HashMap;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import com.iridium126.createmanaindustry.CreateManaIndustry;

import net.irisshaders.iris.Iris;

/**
 * Access to the active shaderpack's persisted option overrides — the
 * {@code "<packfile>.txt"} file Iris writes beside the pack (Java-properties
 * style {@code KEY=value} lines; only non-default values are stored).
 * <p>
 * Raw shader sources cannot answer "is this option currently enabled": a
 * commented-out {@code //#define} in the shipped pack says nothing about the
 * user's runtime choice, because Iris rewrites the define only in the source it
 * compiles, never on disk. Reading the option file closes that gap — e.g.
 * Bliss' {@code DISTANT_HORIZONS_SHADOWMAP} toggle silently {@code #undef}s the
 * pack's shadow-map distortion while every raw-source matcher still sees the
 * original {@code #define}.
 * <p>
 * Values are cached per pack name + option-file timestamp so per-frame callers
 * stay file-IO free while still picking up option changes made mid-session.
 */
public final class ActivePackOptions {

    /** One boolean {@code KEY=value} line of an Iris option overrides file. */
    private static final Pattern FLAG = Pattern.compile(
            "^\\s*([A-Za-z_][A-Za-z0-9_]*)\\s*=\\s*(true|false)\\s*$");

    private static String lastName = null;
    private static long lastOptionsMtime = -1L;
    private static Map<String, Boolean> flags = Map.of();

    private ActivePackOptions() {}

    /**
     * Whether the given boolean pack option is enabled for the active pack.
     * Absent keys are off (Iris persists only values that differ from the
     * shipped default).
     */
    public static boolean isEnabled(String key) {
        refreshIfNeeded();
        return flags.getOrDefault(key, false);
    }

    private static void refreshIfNeeded() {
        String name = ShaderColoredLightAdapters.activePackName();
        long optionsMtime = optionsMtime(name);
        if (name.equals(lastName) && optionsMtime == lastOptionsMtime)
            return;
        lastName = name;
        lastOptionsMtime = optionsMtime;
        flags = load(name);
    }

    /** The option file's last-modified time, or {@code -1L} when unavailable. */
    private static long optionsMtime(String packName) {
        Path options = optionsPath(packName);
        if (options == null)
            return -1L;
        try {
            FileTime mtime = Files.getLastModifiedTime(options);
            return mtime.toMillis();
        } catch (IOException | RuntimeException e) {
            return -1L;
        }
    }

    private static Map<String, Boolean> load(String packName) {
        Path options = optionsPath(packName);
        if (options == null)
            return Map.of();
        try {
            Map<String, Boolean> parsed = new HashMap<>();
            for (String line : Files.readAllLines(options, StandardCharsets.UTF_8)) {
                Matcher m = FLAG.matcher(line);
                if (m.matches())
                    parsed.put(m.group(1), Boolean.parseBoolean(m.group(2)));
            }
            return Map.copyOf(parsed);
        } catch (IOException | RuntimeException | LinkageError e) {
            CreateManaIndustry.LOGGER.warn("Could not read shaderpack option overrides", e);
            return Map.of();
        }
    }

    /** {@code "<shaderpacks>/<packfile>.txt"}, or {@code null} when unresolvable. */
    private static Path optionsPath(String packName) {
        if (packName == null || packName.isEmpty() || packName.equals("(internal)"))
            return null;
        try {
            Path pack = Iris.getShaderpacksDirectory().resolve(packName);
            return pack.resolveSibling(pack.getFileName() + ".txt");
        } catch (RuntimeException | LinkageError e) {
            return null;
        }
    }
}
