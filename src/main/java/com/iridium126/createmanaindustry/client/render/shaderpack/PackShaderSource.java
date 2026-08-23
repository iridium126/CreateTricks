package com.iridium126.createmanaindustry.client.render.shaderpack;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/**
 * Provides the shaderpack's candidate shader sources for distortion detection,
 * handling both zipped packs and extracted folders.
 */
public interface PackShaderSource {

    /** Candidate shader file texts likely to define distortion constants. */
    List<String> candidateTexts();

    /** Creates a source for a zipped or extracted-folder pack. */
    static PackShaderSource of(Path packPath) {
        return Files.isDirectory(packPath) ? new FolderSource(packPath) : new ZipSource(packPath);
    }

    /** Extracted-folder pack — read the well-known settings files directly. */
    final class FolderSource implements PackShaderSource {
        private final Path packDir;

        FolderSource(Path packDir) {
            this.packDir = packDir;
        }

        private static final String[] CANDIDATES = {
                "shaders/settings.glsl", "shaders/shaders.properties", "shaders.properties",
                // Bliss-style packs keep their distortion math in dedicated lib files.
                "shaders/lib/Shadow_Params.glsl", "shaders/lib/Shadows.glsl",
                // Sundial keeps its option constants (shadow distortion strength)
                // in a capitalized settings-directory file.
                "shaders/settings/GlobalSettings.glsl" };

        @Override
        public List<String> candidateTexts() {
            List<String> texts = new ArrayList<>();
            for (String c : CANDIDATES) {
                Path file = packDir.resolve(c);
                if (Files.isRegularFile(file)) {
                    try {
                        texts.add(Files.readString(file, StandardCharsets.UTF_8));
                    } catch (IOException ignored) {
                        // try next
                    }
                }
            }
            return texts;
        }
    }

    /** Zipped pack — scan entries under shaders/ for settings/properties files. */
    final class ZipSource implements PackShaderSource {
        private final Path packFile;

        ZipSource(Path packFile) {
            this.packFile = packFile;
        }

        @Override
        public List<String> candidateTexts() {
            List<String> texts = new ArrayList<>();
            if (!Files.isRegularFile(packFile))
                return texts;
            try (ZipFile zip = new ZipFile(packFile.toFile())) {
                List<String> names = new ArrayList<>();
                Enumeration<? extends ZipEntry> entries = zip.entries();
                while (entries.hasMoreElements()) {
                    String n = entries.nextElement().getName();
                    if (!n.startsWith("shaders/"))
                        continue;
                    String lower = n.toLowerCase(java.util.Locale.ROOT);
                    // Case-insensitive tails — packs capitalize their settings
                    // files (Sundial: settings/GlobalSettings.glsl) — plus the
                    // dedicated settings directories some packs ship their option
                    // constants in.
                    boolean settingsLike = n.endsWith(".properties") || lower.endsWith("settings.glsl")
                            || lower.endsWith("settings.vsh") || lower.endsWith("common.glsl")
                            || lower.contains("/settings/");
                    // Distortion math often lives in dedicated lib files (Bliss:
                    // lib/Shadows.glsl, lib/Shadow_Params.glsl).
                    boolean shadowGlsl = lower.endsWith(".glsl") && lower.contains("shadow");
                    if (settingsLike || shadowGlsl) {
                        names.add(n);
                        if (names.size() >= 32)
                            break;
                    }
                }
                // settings.glsl / common.glsl first, then shadow-named glsl, then rest —
                // earlier texts win in the convention matchers.
                names.sort((a, b) -> Integer.compare(priority(b), priority(a)));
                for (String n : names) {
                    ZipEntry e = zip.getEntry(n);
                    if (e == null)
                        continue;
                    try (InputStream in = zip.getInputStream(e)) {
                        texts.add(new String(in.readAllBytes(), StandardCharsets.UTF_8));
                    } catch (IOException ignored) {
                        // try next
                    }
                }
            } catch (IOException ignored) {
                // fall through with whatever was collected
            }
            return texts;
        }

        private static int priority(String name) {
            String lower = name.toLowerCase(java.util.Locale.ROOT);
            if (lower.endsWith("settings.glsl") || lower.endsWith("common.glsl"))
                return 2;
            if (name.toLowerCase(java.util.Locale.ROOT).contains("shadow"))
                return 1;
            return 0;
        }
    }
}