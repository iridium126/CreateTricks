package com.iridium126.createmanaindustry.client.render;

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

        @Override
        public List<String> candidateTexts() {
            List<String> texts = new ArrayList<>();
            for (String c : new String[] {
                    "shaders/settings.glsl", "shaders/shaders.properties", "shaders.properties" }) {
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
                    if (n.endsWith(".properties") || n.endsWith("settings.glsl") || n.endsWith("settings.vsh")
                            || n.endsWith("common.glsl")) {
                        names.add(n);
                        if (names.size() >= 32)
                            break;
                    }
                }
                // settings.glsl / common.glsl first (where packs define these).
                names.sort((a, b) -> Boolean.compare(
                        b.endsWith("settings.glsl") || b.endsWith("common.glsl"),
                        a.endsWith("settings.glsl") || a.endsWith("common.glsl")));
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
    }
}
