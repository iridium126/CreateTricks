package com.iridium126.createmanaindustry.client.dimension.iris;

import java.lang.reflect.Modifier;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.function.Function;
import java.util.function.IntSupplier;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonDeserializationContext;
import com.google.gson.JsonDeserializer;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParseException;
import com.google.gson.JsonPrimitive;
import com.iridium126.createmanaindustry.CreateManaIndustry;
import it.unimi.dsi.fastutil.ints.Int2ObjectLinkedOpenHashMap;
import it.unimi.dsi.fastutil.ints.Int2ObjectMap;
import it.unimi.dsi.fastutil.ints.Int2ObjectOpenHashMap;
import it.unimi.dsi.fastutil.objects.Object2ObjectLinkedOpenHashMap;
import net.irisshaders.iris.shaderpack.ShaderPack;
import net.irisshaders.iris.shaderpack.include.AbsolutePackPath;
import org.lwjgl.opengl.ARBDrawBuffersBlend;

import static org.lwjgl.opengl.GL11.GL_DST_ALPHA;
import static org.lwjgl.opengl.GL11.GL_DST_COLOR;
import static org.lwjgl.opengl.GL11.GL_ONE;
import static org.lwjgl.opengl.GL11.GL_ONE_MINUS_DST_ALPHA;
import static org.lwjgl.opengl.GL11.GL_ONE_MINUS_DST_COLOR;
import static org.lwjgl.opengl.GL11.GL_ONE_MINUS_SRC_ALPHA;
import static org.lwjgl.opengl.GL11.GL_ONE_MINUS_SRC_COLOR;
import static org.lwjgl.opengl.GL11.GL_SRC_ALPHA;
import static org.lwjgl.opengl.GL11.GL_SRC_ALPHA_SATURATE;
import static org.lwjgl.opengl.GL11.GL_SRC_COLOR;
import static org.lwjgl.opengl.GL11.GL_ZERO;
import static org.lwjgl.opengl.GL11.glDisable;
import static org.lwjgl.opengl.GL11.glEnable;
import static org.lwjgl.opengl.GL14.glBlendFuncSeparate;
import static org.lwjgl.opengl.GL30.glDisablei;
import static org.lwjgl.opengl.GL30.glEnablei;

/**
 * Parser for the shader-pack-side voxy adaptation ({@code shaders/<world>/voxy.json}
 * + optional {@code voxy_opaque.glsl} / {@code voxy_translucent.glsl} /
 * {@code voxy_taa.glsl} files) — ALLVR's own port of voxy's IrisShaderPatch so it
 * can coexist with a voxy installation without sharing any classes or mixin
 * fields with it (doc §13 iris integration slice, grilling decision 1a/3).
 * <p>
 * The JSON text arrives preprocessed: the caller passes iris's
 * {@code sourceProvider}, which resolves {@code #include}s (packs pull their
 * settings in via a dummy string field — Photon's voxy.json gates whole fields
 * on {@code #ifdef TAA} that way) and runs the Jcpp preprocessor with the pack
 * environment defines (ShaderPack.java:317 in iris 1.7.3). The
 * {@code unusedString} field is a parse-safe dumping ground for that reason.
 * <p>
 * Parse failures NEVER throw — the caller falls back to the unlit coexistence
 * path, and pack loading must not break because of us (voxy throws; we don't).
 */
public final class AllvrVoxyPatch {

    /** voxy.json schema version both target packs ship ("version": 1). */
    private static final int PATCH_VERSION = 1;
    /**
     * Value of the injected {@code #define VOXY} — matches voxy's
     * SHADER_DEFINE_VERSION so packs cannot distinguish the two renderers and
     * a co-installed voxy port produces byte-identical defines.
     */
    public static final int SHADER_DEFINE_VERSION = 2;

    public record BlendState(int buffer, boolean off, int sRGB, int dRGB, int sA, int dA) {
        public static final BlendState ALL_OFF = new BlendState(-1, true, 0, 0, 0, 0);
    }

    private static final class BlendStateDeserializer implements JsonDeserializer<Int2ObjectMap<BlendState>> {
        private static int parseType(String type) {
            type = type.toUpperCase();
            if (!type.startsWith("GL_")) {
                type = "GL_" + type;
            }
            return switch (type) {
                case "GL_ZERO" -> GL_ZERO;
                case "GL_ONE" -> GL_ONE;
                case "GL_SRC_COLOR" -> GL_SRC_COLOR;
                case "GL_ONE_MINUS_SRC_COLOR" -> GL_ONE_MINUS_SRC_COLOR;
                case "GL_SRC_ALPHA" -> GL_SRC_ALPHA;
                case "GL_ONE_MINUS_SRC_ALPHA" -> GL_ONE_MINUS_SRC_ALPHA;
                case "GL_DST_ALPHA" -> GL_DST_ALPHA;
                case "GL_ONE_MINUS_DST_ALPHA" -> GL_ONE_MINUS_DST_ALPHA;
                case "GL_DST_COLOR" -> GL_DST_COLOR;
                case "GL_ONE_MINUS_DST_COLOR" -> GL_ONE_MINUS_DST_COLOR;
                case "GL_SRC_ALPHA_SATURATE" -> GL_SRC_ALPHA_SATURATE;
                default -> {
                    CreateManaIndustry.LOGGER.warn("[Allvr] unknown blend option {}", type);
                    yield -1;
                }
            };
        }

        @Override
        public Int2ObjectMap<BlendState> deserialize(JsonElement json, java.lang.reflect.Type typeOfT,
                                                     JsonDeserializationContext context) throws JsonParseException {
            Int2ObjectMap<BlendState> ret = new Int2ObjectOpenHashMap<>();
            if (json == null) {
                return ret;
            }
            try {
                if (json.isJsonPrimitive() && json.getAsString().equalsIgnoreCase("off")) {
                    ret.put(-1, BlendState.ALL_OFF);
                    return ret;
                }
                for (var entry : json.getAsJsonObject().entrySet()) {
                    int buffer = Integer.parseInt(entry.getKey());
                    var val = entry.getValue();
                    BlendState state = null;
                    List<String> bs = null;
                    if (val.isJsonArray()) {
                        bs = val.getAsJsonArray().asList().stream().map(JsonElement::getAsString).toList();
                    } else if (val.isJsonPrimitive()) {
                        var str = val.getAsString();
                        if (str.equalsIgnoreCase("off")) {
                            state = new BlendState(buffer, true, 0, 0, 0, 0);
                        } else {
                            bs = List.of(str.split(" "));
                        }
                    } else {
                        CreateManaIndustry.LOGGER.warn("[Allvr] unknown blend state {}", val);
                    }
                    if (bs != null) {
                        int[] v = bs.stream().mapToInt(BlendStateDeserializer::parseType).toArray();
                        // short specs read as "off" (voxy's same lenient handling)
                        state = v.length < 4
                            ? new BlendState(buffer, true, -1, -1, -1, -1)
                            : new BlendState(buffer, false, v[0], v[1], v[2], v[3]);
                    }
                    if (state != null) {
                        ret.put(buffer, state);
                    }
                }
                return ret;
            } catch (Exception e) {
                CreateManaIndustry.LOGGER.error("[Allvr] failed to parse blend state: {}", json, e);
                return ret;
            }
        }
    }

    private static final class SamplerDeserializer implements JsonDeserializer<Object2ObjectLinkedOpenHashMap<String, String>> {
        private static String defaultType(String name) {
            return name.matches("shadowtex.*") ? "sampler2DShadow" : "sampler2D";
        }

        @Override
        public Object2ObjectLinkedOpenHashMap<String, String> deserialize(JsonElement json, java.lang.reflect.Type typeOfT,
                                                                          JsonDeserializationContext context) throws JsonParseException {
            Object2ObjectLinkedOpenHashMap<String, String> ret = new Object2ObjectLinkedOpenHashMap<>();
            if (json == null) {
                return ret;
            }
            try {
                if (json.isJsonArray()) {
                    for (var entry : json.getAsJsonArray()) {
                        var name = entry.getAsString();
                        ret.put(name, defaultType(name));
                    }
                } else {
                    for (var entry : json.getAsJsonObject().entrySet()) {
                        String type = entry.getValue().isJsonNull() ? defaultType(entry.getKey())
                            : entry.getValue().getAsString();
                        ret.put(entry.getKey(), type);
                    }
                }
            } catch (Exception e) {
                CreateManaIndustry.LOGGER.error("[Allvr] failed to parse samplers", e);
            }
            return ret;
        }
    }

    private static final class SsboDeserializer implements JsonDeserializer<Int2ObjectOpenHashMap<String>> {
        @Override
        public Int2ObjectOpenHashMap<String> deserialize(JsonElement json, java.lang.reflect.Type typeOfT,
                                                         JsonDeserializationContext context) throws JsonParseException {
            Int2ObjectOpenHashMap<String> ret = new Int2ObjectOpenHashMap<>();
            if (json == null) {
                return ret;
            }
            try {
                for (var entry : json.getAsJsonObject().entrySet()) {
                    ret.put(Integer.parseInt(entry.getKey()), entry.getValue().getAsString());
                }
            } catch (Exception e) {
                CreateManaIndustry.LOGGER.error("[Allvr] failed to parse ssbos", e);
            }
            return ret;
        }
    }

    /** voxy.json schema (Gson-bound). Field names are the pack-side contract. */
    private static final class PatchJson {
        public int version;
        public int[] opaqueDrawBuffers;
        public int[] translucentDrawBuffers;
        public String[] uniforms;
        public Object2ObjectLinkedOpenHashMap<String, String> samplers;
        public String opaquePatchData;
        public String translucentPatchData;
        public Int2ObjectOpenHashMap<String> ssbos;
        public Int2ObjectMap<BlendState> blending;
        public String taaOffset;
        public boolean excludeLodsFromVanillaDepth;
        public float[] renderScale;
        public boolean useViewportDims;
        public String unusedString;

        String checkValid() {
            if (this.opaquePatchData == null) {
                return "opaquePatchData missing";
            }
            if (this.uniforms == null) {
                return "uniforms missing";
            }
            if (this.opaqueDrawBuffers == null) {
                return "opaqueDrawBuffers missing";
            }
            if (this.translucentDrawBuffers == null) {
                return "translucentDrawBuffers missing";
            }
            return null;
        }
    }

    private static final Gson GSON = new GsonBuilder()
        .excludeFieldsWithModifiers(Modifier.PRIVATE)
        .registerTypeAdapter(Int2ObjectMap.class, new BlendStateDeserializer())
        .registerTypeAdapter(Object2ObjectLinkedOpenHashMap.class, new SamplerDeserializer())
        .registerTypeAdapter(Int2ObjectOpenHashMap.class, new SsboDeserializer())
        .setLenient()
        .create();

    private final PatchJson json;
    private final Int2ObjectMap<BlendState> blending;
    private final Int2ObjectMap<String> ssbos;

    private AllvrVoxyPatch(PatchJson json) {
        this.json = json;
        this.blending = json.blending == null ? new Int2ObjectOpenHashMap<>() : json.blending;
        this.ssbos = json.ssbos == null ? new Int2ObjectOpenHashMap<>() : json.ssbos;
    }

    // ---- accessors ---------------------------------------------------------

    public String getPatchOpaqueSource() {
        return this.json.opaquePatchData;
    }

    public String getPatchTranslucentSource() {
        return this.json.translucentPatchData;
    }

    public String getTAAShift() {
        return this.json.taaOffset;
    }

    public String[] getUniformList() {
        return this.json.uniforms;
    }

    public Object2ObjectLinkedOpenHashMap<String, String> getSamplerSet() {
        return this.json.samplers;
    }

    public int[] getOpaqueTargets() {
        return this.json.opaqueDrawBuffers;
    }

    public int[] getTranslucentTargets() {
        return this.json.translucentDrawBuffers;
    }

    /** Whether LOD terrain should leave the vanilla depth buffer alone. */
    public boolean excludeFromVanillaDepth() {
        return this.json.excludeLodsFromVanillaDepth;
    }

    public float[] getRenderScale() {
        if (this.json.renderScale == null || this.json.renderScale.length == 0) {
            return new float[] {1, 1};
        }
        if (this.json.renderScale.length == 1) {
            return new float[] {this.json.renderScale[0], this.json.renderScale[0]};
        }
        return new float[] {Math.max(0.01f, this.json.renderScale[0]), Math.max(0.01f, this.json.renderScale[1])};
    }

    public boolean useViewportDims() {
        return this.json.useViewportDims;
    }

    public Int2ObjectMap<String> getSsbos() {
        return this.ssbos;
    }

    /** GL blend-state setup for the translucent pass (pack-declared). */
    public Runnable createBlendSetup() {
        if (this.blending.isEmpty()) {
            return () -> {};
        }
        var BS = this.blending;
        return () -> {
            var init = BS.get(-1);
            if (init != null) {
                if (init.off()) {
                    glDisable(org.lwjgl.opengl.GL11.GL_BLEND);
                } else {
                    glEnable(org.lwjgl.opengl.GL11.GL_BLEND);
                    glBlendFuncSeparate(init.sRGB(), init.dRGB(), init.sA(), init.dA());
                }
            }
            for (var entry : BS.int2ObjectEntrySet()) {
                if (entry.getIntKey() == -1) {
                    continue;
                }
                var s = entry.getValue();
                if (s.off()) {
                    glDisablei(org.lwjgl.opengl.GL11.GL_BLEND, s.buffer());
                } else {
                    glEnablei(org.lwjgl.opengl.GL11.GL_BLEND, s.buffer());
                    ARBDrawBuffersBlend.glBlendFuncSeparateiARB(s.buffer(), s.sRGB(), s.dRGB(), s.sA(), s.dA());
                }
            }
        };
    }

    // ---- parsing -----------------------------------------------------------

    /**
     * Parses the pack's voxy adaptation. {@code sourceProvider} is iris's
     * include-resolving + Jcpp-preprocessing reader, so the JSON text arrives
     * with pack settings conditionals already resolved. Returns null when the
     * pack ships no adaptation, or on any parse failure (logged, never thrown).
     */
    public static AllvrVoxyPatch makePatch(ShaderPack pack, AbsolutePackPath directory,
                                           Function<AbsolutePackPath, String> sourceProvider) {
        String patchText;
        try {
            patchText = sourceProvider.apply(directory.resolve("voxy.json"));
        } catch (Exception e) {
            CreateManaIndustry.LOGGER.debug("[Allvr] no voxy.json in shader pack ({})", e.toString());
            return null;
        }
        if (patchText == null || patchText.isBlank()) {
            return null;
        }

        PatchJson parsed = null;
        try {
            // Escape backslashes, then escape any quotation marks that live in
            // comment tails — packs embed GLSL (with // comments and quotes)
            // inside JSON string fields (voxy's same workaround)
            patchText = patchText.replace("\\", "\\\\");
            {
                StringBuilder builder = new StringBuilder(patchText.length());
                for (var line : patchText.split("\n")) {
                    int idx = line.indexOf("//");
                    if (idx != -1) {
                        builder.append(line, 0, idx);
                        builder.append(line.substring(idx).replace("\"", "\\\""));
                    } else {
                        builder.append(line);
                    }
                    builder.append('\n');
                }
                patchText = builder.toString();
            }
            // Complementary's chunk-fade marker is plain GLSL, not valid JSON
            patchText = patchText.replaceAll("void _cfi_ignoreMarker\\(\\) \\{\\}", "");

            parsed = GSON.fromJson(patchText, PatchJson.class);
            if (parsed == null) {
                throw new IllegalStateException("voxy.json parsed to null (malformed json)");
            }

            // External patch files override the JSON-inline fields when present
            var opaque = sourceProvider.apply(directory.resolve("voxy_opaque.glsl"));
            if (opaque != null) {
                parsed.opaquePatchData = opaque;
            }
            var translucent = sourceProvider.apply(directory.resolve("voxy_translucent.glsl"));
            if (translucent != null) {
                parsed.translucentPatchData = translucent;
            }
            var taa = sourceProvider.apply(directory.resolve("voxy_taa.glsl"));
            if (taa != null) {
                parsed.taaOffset = taa;
            }

            var invalid = parsed.checkValid();
            if (invalid != null) {
                throw new IllegalStateException("voxy.json not valid: " + invalid);
            }
            if (parsed.version != PATCH_VERSION) {
                throw new IllegalStateException(
                    "voxy.json version mismatch: expected " + PATCH_VERSION + " got " + parsed.version);
            }
        } catch (Exception e) {
            CreateManaIndustry.LOGGER.error("[Allvr] failed to parse voxy.json — falling back to the unlit "
                + "coexistence path for this pack", e);
            return null;
        }
        return new AllvrVoxyPatch(parsed);
    }
}
