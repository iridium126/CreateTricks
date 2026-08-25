package com.iridium126.createmanaindustry.client.particles.shaderpack;

import com.iridium126.createmanaindustry.CreateManaIndustry;
import com.iridium126.createmanaindustry.accessor.CMIProgramDirectivesAccessor;
import com.iridium126.createmanaindustry.client.particles.engine.ParticlePrograms;

import net.irisshaders.iris.Iris;
import net.irisshaders.iris.gl.blending.AlphaTest;
import net.irisshaders.iris.gl.blending.AlphaTestFunction;
import net.irisshaders.iris.gl.blending.BlendMode;
import net.irisshaders.iris.gl.blending.BlendModeOverride;
import net.irisshaders.iris.gl.state.FogMode;
import net.irisshaders.iris.gl.shader.ShaderCompileException;
import net.irisshaders.iris.helpers.FakeChainedJsonException;
import net.irisshaders.iris.pipeline.IrisRenderingPipeline;
import net.irisshaders.iris.pipeline.WorldRenderingPipeline;
import net.irisshaders.iris.shaderpack.loading.ProgramId;
import net.irisshaders.iris.shaderpack.preprocessor.JcppProcessor;
import net.irisshaders.iris.shaderpack.programs.ProgramFallbackResolver;
import net.irisshaders.iris.shaderpack.programs.ProgramSet;
import net.irisshaders.iris.shaderpack.programs.ProgramSource;
import net.irisshaders.iris.gl.shader.StandardMacros;

import java.io.IOException;
import java.util.Map;
import net.irisshaders.iris.vertices.IrisVertexFormats;
import net.minecraft.client.renderer.ShaderInstance;
import top.leonx.irisveil.accessors.IrisRenderingPipelineAccessor;
import top.leonx.irisveil.accessors.ProgramSourceAccessor;

import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL20;

/**
 * Compiles the CMI MODEL-particle draw into the active shader pack's ENTITY
 * pipeline: resolves the pack's {@code gbuffers_entities} program via Iris's
 * own fallback chain, injects the SSBO-driven allay vertex program with
 * {@link ParticleVertexInjector}, and keeps the PACK FRAGMENT VERBATIM.
 *
 * <p>The merged pair is drawn by {@link CmiEarlyModelHook} during the gbuffer
 * phase (before any deferred composite), so encoder-style packs such as Photon
 * light the particles through their normal deferred path -- including the
 * {@code material_mask = 40} emission rule their entity vertex shaders apply
 * at full block light, which our injected full-bright lightmap deliberately
 * triggers. Forward-lit packs (Complementary et al.) simply shade us like a
 * native entity at their usual in-shader timing. Either way the particles get
 * exactly the surface treatment the pack gives a real allay.</p>
 *
 * <p>TWO merged {@link ShaderInstance}s serve the model segments from the same
 * injected sources: the cutout program inherits the pack's entity directives
 * untouched (native solid-entity look), while the ghost program pins the
 * standard translucent blend over the SAME inherited directives so cloak/wings
 * keep their L0 gradient semantics. The alpha test is NOT pinned on either:
 * the resolved Entities directives are copied onto our synthetic ProgramSources
 * via {@code withDirectiveOverride}, so name-keyed pack directives
 * ({@code alphaTest.gbuffers_entities}, ...) win inside {@code ShaderCreator}
 * exactly like a native entity draw -- matching the pack's entity discard
 * behaviour is precisely the goal ({@code GREATER 0.1} is only the fallback
 * when the pack defines none).</p>
 *
 * <p>Core flow adapted from iris-veil-compat's {@code IrisVeilProgramLinker}
 * / iris-flw-compat's {@code IrisProgramLinker} (MIT, (c) top.leonx): pipeline
 * accessor -> ProgramSet -> ProgramFallbackResolver(ProgramId.Entities) ->
 * patch -> jcpp preprocess -> ProgramSource (+ its withDirectiveOverride step)
 * -> invokeCreateShader.</p>
 *
 * <p>All iris-veil-compat type references in this class require the mod to be
 * loaded -- instantiate only under {@link CreateManaIndustry#IRISVEIL_ACTIVE}.</p>
 */
public final class ShaderPackProgramCompiler {

    /** SSBO binding slots for the merged program: deliberately high so neither
     * Iris nor a coexisting Flywheel stack can claim them (see design doc §5). */
    /** Texture units the merged programs' TBO samplers are pinned to. High,
     * clear of Iris/pack allocations, and restored after every draw. */
    public static final int MERGED_SAMPLER_UNIT_BASE = 10;

    private static final String SHADER_NAME = "cmi_model_entities";
    private static final String GHOST_SHADER_NAME = "cmi_model_entities_ghost";

    /** L0 ghost semantics pinned onto the ghost program regardless of what the
     * pack's entity blend directive carries (design doc §12.2): standard
     * translucent blend WITH alpha components, depth writes stay on elsewhere. */
    private static final BlendModeOverride GHOST_BLEND_OVERRIDE = new BlendModeOverride(new BlendMode(
            GL11.GL_SRC_ALPHA, GL11.GL_ONE_MINUS_SRC_ALPHA,
            GL11.GL_ONE, GL11.GL_ONE_MINUS_SRC_ALPHA));

    /** SSBO interface blocks for the merged programs, injected as RAW TEXT right
     * after the pack #version line. Kept out of the AST injector on purpose:
     * their survival must not depend on cross-tree declaration transplanting or
     * on any particular printer mode. jcpp and both downstream parsers leave
     * them untouched verbatim. */
    private static final String SSBO_DECLARATIONS =
        "layout(std430, binding = 12) readonly buffer CmiGeo { float v[]; };\n" +
        "layout(std430, binding = 13) readonly buffer CmiPool { vec4 data[]; };\n" +
        "layout(std430, binding = 14) readonly buffer CmiEmitters { vec4 u[]; };\n" +
        "layout(std430, binding = 15) readonly buffer CmiSorted { uvec2 kv[]; };\n";

    /** Native entity-cutout discard level, used only when the pack's entities
     * program carries no {@code alphaTest.<program>} directive of its own. */
    private static final AlphaTest FALLBACK_ALPHA_TEST =
            new AlphaTest(AlphaTestFunction.GREATER, 0.1f);

    private final ParticleVertexInjector injector = new ParticleVertexInjector();

    /** Lazily built merged vertex source (SSBO decls + pose chunk + main). */
    private String mergedVertexSource;

    private ShaderInstance entitiesShader;
    private ShaderInstance ghostShader;
    private Object owningPipeline; // identity of the pipeline the shaders belong to
    /** Fired whenever compilation restarts: previously returned program ids go stale. */
    private Runnable onRebuild;
    /** Texture unit Iris assigned to each merged program's gtexture sampler. */
    private int gtextureUnit = -1;
    private int ghostGtextureUnit = -1;
    private String lastError = "";
    private boolean failed;
    /** Warn-once latch when the blend-pin mixin is absent. */
    private boolean blendPinWarned;
    /** Patched sources of the latest attempt, dumped even on failure. */
    private String lastPatchedVertex;
    private String lastPatchedFragment;

    public boolean isReady() {
        return entitiesShader != null && ghostShader != null;
    }

    /**
     * Registers a callback invoked whenever a rebuild starts (pipeline changed).
     * Consumers that cache GL state keyed by program id -- e.g. uniform-location
     * maps -- must drop those entries here: rebuild deletes the old programs, so
     * cached ids would silently alias whatever the driver reuses.
     */
    public void setOnRebuild(Runnable listener) {
        this.onRebuild = listener;
    }

    public String lastError() {
        return this.lastError;
    }

    /**
     * Builds (or rebuilds, after a pipeline change) the merged program.
     * Returns false on any failure -- the early hook then stands down and the
     * late-window self-drawn fallback takes over; this class records the reason
     * for /cmip shaderpack status.
     */
    public boolean ensureCompiled() {
        WorldRenderingPipeline pipeline = Iris.getPipelineManager().getPipelineNullable();
        if (!(pipeline instanceof IrisRenderingPipeline irisPipeline))
            return false;
        // Sticky per-pipeline state: ready -> reuse, failed -> stay failed until
        // the pipeline INSTANCE changes (pack switch / reload). Prevents re-running
        // the expensive AST/jcpp attempt every frame against the same broken pack.
        if (this.owningPipeline == pipeline)
            return this.isReady();

        this.reset();
        this.failed = false;
        this.lastError = "";
        this.owningPipeline = pipeline; // claimed up front for the sticky semantics above
        if (this.onRebuild != null)
            this.onRebuild.run(); // old program ids are gone from here on
        try {
            var accessor = (IrisRenderingPipelineAccessor) pipeline;
            ProgramSet programSet = accessor.getProgramSet();

            var resolver = new ProgramFallbackResolver(programSet);
            var refProgram = resolver.resolve(ProgramId.Entities).orElse(null);
            if (refProgram == null)
                return fail("pack has no Entities program source (fallback chain exhausted)");
            var vertRef = refProgram.getVertexSource().orElse(null);
            var fragRef = refProgram.getFragmentSource().orElse(null);
            if (vertRef == null || fragRef == null)
                return fail("Entities program source missing vertex or fragment");

            String patched = this.injector.patch(vertRef, this.buildMergedSource(), SHADER_NAME);
            if (patched == vertRef)
                return fail("vertex injection failed");
            patched = JcppProcessor.glslPreprocessSource(patched,
                    StandardMacros.createStandardEnvironmentDefines());
            this.lastPatchedVertex = patched;
            if (CreateManaIndustry.LOGGER.isInfoEnabled()) {
                String[] lines = patched.split("\n", -1);
                int ssboLine = -1;
                for (int i = 0; i < lines.length; i++)
                    if (lines[i].contains("buffer CmiPool")) { ssboLine = i + 1; break; }
                CreateManaIndustry.LOGGER.info(
                        "[CMI particles] merged vsh prepared: chars={}, lines={}, ssboDeclLine={}",
                        patched.length(), lines.length, ssboLine);
            }
            this.lastPatchedFragment = fragRef;

            var properties = ((ProgramSourceAccessor) refProgram).getShaderProperties();

            var inheritedAlpha = refProgram.getDirectives().getAlphaTestOverride()
                    .map(String::valueOf)
                    .orElse("none -> fallback " + FALLBACK_ALPHA_TEST);

            // Route-A directive inheritance: the synthetic names never match
            // name-keyed pack properties (ProgramDirectives resolves
            // alphaTest.<name>/blend.<name> against source.getName()), so both
            // programs receive the resolved Entities directives wholesale and
            // ShaderCreator honours them exactly as for a native entity draw;
            // absent fields fall through unchanged (alpha -> FALLBACK_ALPHA_TEST,
            // blend -> ProgramId.Entities' built-in default).
            this.entitiesShader = this.compileMergedWithRescue(accessor, programSet, properties,
                    patched, fragRef, SHADER_NAME, refProgram.getDirectives(), null);
            if (this.entitiesShader == null)
                return fail("Iris returned no shader instance");
            this.gtextureUnit = findSamplerUnit(this.entitiesShader);

            // Ghost segment: same sources and directive inheritance, but the
            // standard translucent blend is PINNED so cloak/wings keep their L0
            // gradient semantics regardless of the pack's entity blend. The pin
            // targets a PRIVATE directives copy -- withDirectiveOverride hands
            // every source the same original instance otherwise.
            var ghostDirectives = refProgram.getDirectives()
                    .withOverriddenDrawBuffers(refProgram.getDirectives().getDrawBuffers());
            this.ghostShader = this.compileMergedWithRescue(accessor, programSet, properties,
                    patched, fragRef, GHOST_SHADER_NAME, ghostDirectives, GHOST_BLEND_OVERRIDE);
            if (this.ghostShader == null)
                return fail("Iris returned no ghost shader instance");
            this.ghostGtextureUnit = findSamplerUnit(this.ghostShader);

            dumpPatchedSources(patched, fragRef);

            CreateManaIndustry.LOGGER.info(
                    "[CMI particles] early entities merge ready ({}+{}, gtexture {}/{}, inherited alphaTest={})",
                    SHADER_NAME, GHOST_SHADER_NAME, this.gtextureUnit, this.ghostGtextureUnit,
                    inheritedAlpha);
            return true;
        } catch (Exception e) {
            this.entitiesShader = null;
            this.ghostShader = null;
            this.gtextureUnit = -1;
            this.ghostGtextureUnit = -1;
            // Dump whatever got patched before the failure: a GLSL type error
            // introduced by identifier rewriting is invisible in logs alone.
            dumpPatchedSources(this.lastPatchedVertex, this.lastPatchedFragment);
            dumpTransformCache();
            this.lastPatchedVertex = null;
            this.lastPatchedFragment = null;
            return fail(describeCompileFailure(e));
        }
    }

    public ShaderInstance entitiesShader() {
        return this.entitiesShader;
    }

    public ShaderInstance ghostShader() {
        return this.ghostShader;
    }

    public int gtextureUnit() {
        return this.gtextureUnit;
    }

    public int ghostGtextureUnit() {
        return this.ghostGtextureUnit;
    }

    /** Drops compiled state (pipeline changed, reload, or failure). */
    public void reset() {
        this.entitiesShader = null;
        this.ghostShader = null;
        this.gtextureUnit = -1;
        this.ghostGtextureUnit = -1;
        this.owningPipeline = null;
    }

    /**
     * Builds a failure reason that survives Iris's exception wrapping. Vanilla
     * wraps {@link ShaderCompileException} into a {@link FakeChainedJsonException}
     * whose message is ALWAYS empty -- the infamous trailing
     * "{@code Invalid shaders/core/<name>.json: }" with nothing after the colon.
     * The real payload (failing stage filename + full driver/transformer log)
     * lives deeper, so walk the chain, prefer the true exception, and splice
     * every level's message into one bounded string for the log and
     * {@code /cmip shaderpack status}.
     */
    private static String describeCompileFailure(Throwable e) {
        Throwable primary = e;
        for (Throwable t = e; t != null; t = t.getCause()) {
            if (t instanceof FakeChainedJsonException fake) {
                primary = fake.getTrueException();
                break;
            }
            if (t instanceof ShaderCompileException) {
                primary = t;
                break;
            }
            if (t.getCause() == t)
                break;
        }

        StringBuilder sb = new StringBuilder();
        for (Throwable t = primary; t != null && sb.length() < 4000; t = t.getCause()) {
            if (sb.length() > 0)
                sb.append(" | caused by ");
            sb.append(t.getClass().getSimpleName());
            String msg = t.getMessage();
            if (msg != null && !msg.isBlank())
                sb.append(": ").append(msg.replace('\n', ' '));
            if (t.getCause() == t)
                break;
        }
        if (sb.length() >= 4000)
            sb.append(" ...[truncated]");
        return sb.toString();
    }

    static void logTransformFailure(String shaderName, Exception e) {
        CreateManaIndustry.LOGGER.error("[CMI particles] AST injection failed for {}", shaderName, e);
    }

    private boolean fail(String reason) {
        if (!this.failed)
            CreateManaIndustry.LOGGER.warn("[CMI particles] early entities merge unavailable: {}; staying on the self-drawn path", reason);
        this.failed = true;
        this.lastError = reason;
        return false;
    }

    /**
     * compileMerged + one-shot SSBO rescue: when the driver rejects the merged
     * program with C1503s on our block names although the prepared input carried
     * them, something inside the in-game transform pass stripped the blocks and
     * cached the stripped text. Re-inject the blocks into EVERY cached source
     * derived from us and retry once -- the retry is then a pure cache hit on
     * repaired text.
     */
    private ShaderInstance compileMergedWithRescue(IrisRenderingPipelineAccessor accessor, ProgramSet programSet,
                                                   net.irisshaders.iris.shaderpack.properties.ShaderProperties properties,
                                                   String patchedVertex, String fragRef, String name,
                                                   net.irisshaders.iris.shaderpack.properties.ProgramDirectives directives,
                                                   BlendModeOverride pinBlend) throws IOException {
        try {
            return compileMerged(accessor, programSet, properties, patchedVertex, fragRef, name, directives, pinBlend);
        } catch (ShaderCompileException sce) {
            dumpTransformCache();
            int repaired = repairCachedSources();
            if (repaired <= 0)
                throw sce;
            CreateManaIndustry.LOGGER.warn(
                    "[CMI particles] {} lost its SSBO declarations inside the in-game transform pass; re-injected into {} cached source(s), retrying",
                    name, repaired);
            return compileMerged(accessor, programSet, properties, patchedVertex, fragRef, name, directives, pinBlend);
        }
    }

    /**
     * Builds one merged program: Route-A directive inheritance via
     * {@code withDirectiveOverride}, optional blend pinning through the
     * {@link CMIProgramDirectivesAccessor} getter short-circuit, then Iris's
     * private createShader. {@code directives} must be a PRIVATE copy whenever
     * {@code pinBlend != null} -- the shared original would flip BOTH programs.
     */
    private ShaderInstance compileMerged(IrisRenderingPipelineAccessor accessor, ProgramSet programSet,
                                         net.irisshaders.iris.shaderpack.properties.ShaderProperties properties,
                                         String patchedVertex, String fragRef, String name,
                                         net.irisshaders.iris.shaderpack.properties.ProgramDirectives directives,
                                         BlendModeOverride pinBlend) throws IOException {
        var source = this.makeSource(programSet, properties, patchedVertex, fragRef)
                .withDirectiveOverride(directives);
        if (pinBlend != null) {
            if (source.getDirectives() instanceof CMIProgramDirectivesAccessor mutable) {
                mutable.createmanaindustry$setBlendModeOverride(pinBlend);
            } else if (!this.blendPinWarned) {
                this.blendPinWarned = true;
                CreateManaIndustry.LOGGER.warn(
                        "[CMI particles] blend-pin mixin inactive; ghost falls back to inherited entity blend");
            }
        }
        ShaderInstance shader = accessor.invokeCreateShader(name, source, ProgramId.Entities,
                FALLBACK_ALPHA_TEST, IrisVertexFormats.TERRAIN, FogMode.OFF,
                false, false, false, false, false);
        CreateManaIndustry.LOGGER.info("[CMI particles] invokeCreateShader({}) -> {}",
                name, shader == null ? "null" : "ShaderInstance#" + shader.getId());
        return shader;
    }

    private ProgramSource makeSource(ProgramSet programSet, net.irisshaders.iris.shaderpack.properties.ShaderProperties properties,
                                     String vertex, String fragment) {
        return new ProgramSource(SHADER_NAME, vertex,
                null, null, null, fragment, programSet, properties, null);
    }

    /** Re-inserts the SSBO declarations into every cached transform output that
     * came from us (cmi_ globals present) but lost its blocks. Returns how many
     * sources were repaired. */
    private static int repairCachedSources() {
        try {
            Class<?> tp = Class.forName("net.irisshaders.iris.pipeline.transform.TransformPatcher");
            java.lang.reflect.Field f = tp.getDeclaredField("cache");
            f.setAccessible(true);
            @SuppressWarnings("unchecked")
            Map<Object, Object> cache = (Map<Object, Object>) f.get(null);
            int repaired = 0;
            for (Object v : cache.values()) {
                if (!(v instanceof Map<?, ?>))
                    continue;
                @SuppressWarnings("unchecked")
                Map<Object, Object> programs = (Map<Object, Object>) v;
                for (Map.Entry<Object, Object> pe : programs.entrySet()) {
                    if (pe.getValue() instanceof String s
                            && s.contains("cmi_VertexLevel")
                            && !s.contains("buffer CmiPool")) {
                        pe.setValue(injectSsboDeclarations(s));
                        repaired++;
                    }
                }
            }
            return repaired;
        } catch (Throwable t) {
            CreateManaIndustry.LOGGER.debug("[CMI particles] could not repair transform cache", t);
            return 0;
        }
    }

    /** Idempotently ensures the merged vertex source carries the SSBO block
     * declarations, inserting them directly after the #version line. */
    private static String injectSsboDeclarations(String source) {
        if (source.contains("buffer CmiPool"))
            return source;
        java.util.regex.Matcher m =
                java.util.regex.Pattern.compile("#version[^\n]*\n").matcher(source);
        if (m.find())
            return source.substring(0, m.end()) + SSBO_DECLARATIONS + source.substring(m.end());
        return SSBO_DECLARATIONS + source;
    }

    /**
     * GAME-SIDE GROUND TRUTH: after a GL-stage compile failure, the exact text
     * NVIDIA saw sits in TransformPatcher's result cache (transform succeeded,
     * cache.put ran, THEN the driver rejected it). Reflectively dumps every
     * cached vertex string so we can diff prepared-input vs actually-compiled.
     * Best-effort: any reflection miss is logged at debug and ignored.
     */
    @SuppressWarnings("unchecked")
    private static void dumpTransformCache() {
        try {
            Class<?> tp = Class.forName("net.irisshaders.iris.pipeline.transform.TransformPatcher");
            java.lang.reflect.Field f = tp.getDeclaredField("cache");
            f.setAccessible(true);
            Map<Object, Object> cache = (Map<Object, Object>) f.get(null);
            java.nio.file.Path dir = java.nio.file.Path.of("patched_shaders", "cache");
            java.nio.file.Files.createDirectories(dir);
            int dumped = 0;
            boolean sawBlocks = false;
            for (Map.Entry<Object, Object> en : cache.entrySet()) {
                if (!(en.getValue() instanceof Map<?, ?> programs))
                    continue;
                for (Object o : programs.values()) {
                    if (!(o instanceof String s) || !s.contains("void main"))
                        continue;
                    sawBlocks |= s.contains("buffer CmiPool");
                    java.nio.file.Files.writeString(
                            dir.resolve("cache_" + (dumped++) + ".vsh"), s);
                }
            }
            CreateManaIndustry.LOGGER.info(
                    "[CMI particles] transform cache dump: {} vertices, anyBufferBlocks={}", dumped, sawBlocks);
        } catch (Throwable t) {
            CreateManaIndustry.LOGGER.debug("[CMI particles] could not dump transform cache", t);
        }
    }

    /**
     * Dumps the merged sources next to the game directory for offline diffing
     * while the merge path is under runtime verification (same convention as
     * iris-veil-compat's linker). Best-effort: failures never affect rendering.
     */
    private static void dumpPatchedSources(String vertex, String fragment) {
        try {
            java.nio.file.Path dir = java.nio.file.Path.of("patched_shaders");
            java.nio.file.Files.createDirectories(dir);
            java.nio.file.Files.writeString(dir.resolve(SHADER_NAME + ".vsh"), vertex);
            if (fragment != null)
                java.nio.file.Files.writeString(dir.resolve(SHADER_NAME + ".fsh"), fragment);
        } catch (Exception e) {
            CreateManaIndustry.LOGGER.debug("[CMI particles] could not dump patched shaders", e);
        }
    }

    /** Finds which texture unit Iris bound the pack fragment's main sampler to. */
    private static int findSamplerUnit(ShaderInstance shader) {
        int progId = shader.getId();
        for (String name : new String[] {"gtexture", "tex", "texture0"}) {
            int loc = GL20.glGetUniformLocation(progId, name);
            if (loc >= 0)
                return GL20.glGetUniformi(progId, loc);
        }
        return -1;
    }

    // ------------------------------------------------------------------
    // Merged vertex source assembly
    // ------------------------------------------------------------------

    /**
     * Assembles the CMI-side merged vertex program: header (SSBOs on the high
     * binding slots, injected uniforms, shared globals), the shared pose chunk,
     * and a main body that resolves the instance from the MODEL partition of
     * the sort array and writes the fixed-function replacement globals. The
     * injector prepends this main ahead of the pack's, whose entire vertex
     * pipeline then consumes our results.
     */
    private String buildMergedSource() {
        if (this.mergedVertexSource != null)
            return this.mergedVertexSource;

        String chunk = ParticlePrograms.loadPlain("shaders/particles/chunks/allay_pose.glsl");
        if (chunk.isEmpty())
            throw new IllegalStateException("cannot load chunks/allay_pose.glsl");

        StringBuilder sb = new StringBuilder(8192);
        sb.append("#version 430 core\n"); // feature-level placeholder (SSBOs); the injector rewrites the PACK header, not this one
        // constants ride the AST as plain const declarations -- #define lines
        // vanish when declarations transplant across glsl-transformer trees
        sb.append("const uint MODEL_VERTEX_FLOATS = ").append(ParticlePrograms.modelVertexFloats()).append("u;\n");
        sb.append("const uint VEC4_PER_EMITTER = 20u;\n");
        // particle data arrives through TBO views: plain samplerBuffer uniforms
        // survive every in-game pass, interface blocks demonstrably do not
        sb.append("uniform samplerBuffer cmi_Geo;\n");
        sb.append("uniform samplerBuffer cmi_Pool;\n");
        sb.append("uniform samplerBuffer cmi_Emitters;\n");
        sb.append("uniform usamplerBuffer cmi_Sorted;\n");
        sb.append("""
                uniform vec3 cmi_CameraPos;
                uniform float cmi_FadeDist;

                // results consumed by the pack code through the injector's rewrites
                vec4 cmi_VertexLevel;  // camera-relative level-space vertex
                vec4 cmi_TexCoord0v;   // atlas UV as a gl_MultiTexCoord0 stand-in
                // FLOAT, matching the compatibility-profile gl_MultiTexCoord1
                // attribute: pack code freely mixes .xy with float math (e.g.
                // Photon's clamp01(gl_MultiTexCoord1.xy * rcp(240.0))); an ivec4
                // here produced illegal ivec*float and failed the whole merge.
                vec4 cmi_LightCoordv; // full-bright lightmap (block light 15)
                vec3 cmi_NormalLevel;  // level-space pose normal
                vec4 cmi_Tint;         // emitter colour keyframes x per-particle tint

                """);
        sb.append(chunk).append('\n');
        sb.append("""
                void main() {
                    // resolve this instance through the MODEL partition of the sort array
                    uvec2 item = texelFetch(cmi_Sorted, int(gl_InstanceID)).xy;
                    bool gone = (item.y & 3u) != 0u; // foreign-type item: upstream corruption guard
                    uint inst = item.y >> 2u;
                    uint base = inst * 4u;
                    vec4 p0 = gone ? vec4(0.0) : texelFetch(cmi_Pool, int(base + 0u));
                    vec4 p1 = gone ? vec4(0.0) : texelFetch(cmi_Pool, int(base + 1u));
                    vec4 p2 = gone ? vec4(0.0) : texelFetch(cmi_Pool, int(base + 2u));
                    vec4 p3 = gone ? vec4(0.0) : texelFetch(cmi_Pool, int(base + 3u));

                    uint vb = uint(gl_VertexID) * MODEL_VERTEX_FLOATS;
                    int pid = int(texelFetch(cmi_Geo, int(vb + 5u)).x);
                    int normalCode = int(texelFetch(cmi_Geo, int(vb + 6u)).x);

                    float life = clamp(p3.x / max(p3.y, 1e-5), 0.0, 1.0);
                    uint eid = floatBitsToUint(p3.w);
                    uint hb = eid * VEC4_PER_EMITTER;

                    // per-instance fade early-out: collapse offscreen consistently per triangle
                    gone = gone || distance(p0.xyz, cmi_CameraPos) > cmi_FadeDist + 24.0;

                    float sizeStart = gone ? 0.0 : texelFetch(cmi_Emitters, int(hb + 5u)).z;
                    float sizeEnd = gone ? 0.0 : texelFetch(cmi_Emitters, int(hb + 5u)).w;
                    float sizeEase = max(texelFetch(cmi_Emitters, int(hb + 6u)).x, 0.001);
                    float size = mix(sizeStart, sizeEnd, pow(life, sizeEase)) * p0.w;
                    float scale = (2.0 * size) / 0.625;

                    int anim = int(texelFetch(cmi_Emitters, int(hb + 17u)).x);
                    float yaw;
                    mat4 M = gone ? mat4(1.0) : cmiAllayPartTransform(p3.x, p3.z, p1.xyz, anim, pid, yaw);

                    vec3 local = gone ? vec3(0.0) : vec3(texelFetch(cmi_Geo, int(vb)).x, texelFetch(cmi_Geo, int(vb + 1u)).x, texelFetch(cmi_Geo, int(vb + 2u)).x);
                    vec2 uv = gone ? vec2(0.5) : vec2(texelFetch(cmi_Geo, int(vb + 3u)).x, texelFetch(cmi_Geo, int(vb + 4u)).x);
                    vec3 pm = (M * vec4(local, 1.0)).xyz / 16.0;
                    vec3 flipped = vec3(-pm.x, 1.501 - pm.y, pm.z) * scale;
                    float ry = CMI_PI - yaw;
                    vec3 world = p0.xyz + vec3(cos(ry) * flipped.x + sin(ry) * flipped.z,
                                               flipped.y,
                                               -sin(ry) * flipped.x + cos(ry) * flipped.z);

                    vec3 nPart = mat3(M) * CMI_FACE_NORMALS[normalCode];
                    vec3 nFlipped = vec3(-nPart.x, -nPart.y, nPart.z);
                    cmi_NormalLevel = vec3(cos(ry) * nFlipped.x + sin(ry) * nFlipped.z,
                                           nFlipped.y,
                                           -sin(ry) * nFlipped.x + cos(ry) * nFlipped.z);

                    cmi_VertexLevel = gone
                        ? vec4(0.0, 0.0, 1e9, 1.0)
                        : vec4(world - cmi_CameraPos, 1.0);
                    cmi_TexCoord0v = vec4(uv, 0.0, 1.0);
                    cmi_LightCoordv = vec4(240.0, 240.0, 0.0, 1.0); // block light 15 + full sky

                    vec4 kfr[8];
                    for (int i = 0; i < 8; i++) kfr[i] = gone ? vec4(1.0) : texelFetch(cmi_Emitters, int(hb + 8u + uint(i)));
                    vec4 tint = cmiKeyframeColor(kfr, int(gone ? 1 : texelFetch(cmi_Emitters, int(hb + 6u)).z), life);
                    cmi_Tint = vec4(tint.rgb * p2.rgb, 1.0);
                }
                """);

        this.mergedVertexSource = sb.toString();
        return this.mergedVertexSource;
    }
}
