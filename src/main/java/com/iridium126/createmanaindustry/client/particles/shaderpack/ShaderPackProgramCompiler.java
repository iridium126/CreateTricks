package com.iridium126.createmanaindustry.client.particles.shaderpack;

import com.iridium126.createmanaindustry.CreateManaIndustry;
import com.iridium126.createmanaindustry.accessor.CMIProgramDirectivesAccessor;
import com.iridium126.createmanaindustry.client.particles.engine.ParticlePrograms;

import com.mojang.blaze3d.shaders.Uniform;

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
import java.util.regex.Pattern;
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
 * {@link ParticleVertexInjector}, and keeps the PACK FRAGMENT otherwise
 * verbatim (the one exception: the hurt-overlay conversion below).
 *
 * <p>Hurt overlay: the merged vertex block declares {@code out vec4
 * entityColor;} carrying the per-instance vanilla overlay state (red texel
 * semantics — {@code (1,0,0, 77/255)} while the hurt timer or the corpse
 * countdown runs), mirroring Iris's {@code EntityPatcher} value contract. When
 * the pack can consume it (no entity geometry/tessellation stage, vertex stage
 * does not read entityColor itself), the compiler converts the pack's shared
 * {@code uniform vec4 entityColor;} declaration into the matching {@code in}
 * on the fragment side post-include, so the pack's own
 * {@code mix(color.rgb, entityColor.rgb, entityColor.a)} line applies the
 * constant 30.2% pure-red wash exactly like it does for a real allay. Packs
 * whose entity program ignores entityColor never flash — same as their real
 * entities. Every other case falls back to a vanilla-parameterised vertex-tint
 * approximation riding {@code cmi_Tint} (documented deviation).</p>
 *
 * <p>The merged pair is drawn by {@link CMIPackEntityMergeHook} during the gbuffer
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
 * <p>Two structural notes on the merged sources: the pack's optional geometry /
 * tessellation stages are carried over verbatim by {@link #makeSource} (only
 * the vertex stage is injected), and each created ShaderInstance is seeded with
 * a dummy {@code ModelViewMat} via {@link #seedDummyModelViewUniform} -- which
 * requires {@code META-INF/accesstransformer.cfg} to strip {@code final} from
 * {@code ShaderInstance.MODEL_VIEW_MATRIX}, letting vanilla {@code apply()}
 * run to completion exactly as for native programs.</p>
 *
 * <p>All iris-veil-compat type references in this class require the mod to be
 * loaded -- instantiate only under {@link CreateManaIndustry#IRISVEIL_ACTIVE}.</p>
 */
public final class ShaderPackProgramCompiler {

    /** Texture units the merged programs' TBO samplers are pinned to. Reserved
     * inside Iris's sampler allocator by mixin/irisveil/MixinProgramSamplers,
     * so pack samplers can never collide with them; unbound after every draw. */
    public static final int MERGED_SAMPLER_UNIT_BASE = 10;

    private static final String SHADER_NAME = "cmi_model_entities";
    private static final String GHOST_SHADER_NAME = "cmi_model_entities_ghost";
    private static final String SHADOW_SHADER_NAME = "cmi_model_shadow";

    /** Shadow-path alpha-test fallbacks, PER RESOLVED VARIANT. The entities
     * variant mirrors Iris's native SHADOW_ENTITIES_CUTOUT key exactly
     * ({@code GREATER 0.1}), so particle shadows discard like native entity
     * shadows when the pack defines no alphaTest.shadow of its own; the plain
     * terrain-fallback variant keeps the reference linker's ALWAYS (its native
     * sub-keys span OFF/NON_ZERO/ONE_TENTH with no single faithful value). */
    private static final AlphaTest FALLBACK_ALPHA_TEST_SHADOW_ENTITIES =
            new AlphaTest(AlphaTestFunction.GREATER, 0.1f);
    private static final AlphaTest FALLBACK_ALPHA_TEST_SHADOW_PLAIN = AlphaTest.ALWAYS;

    /** L0 ghost semantics pinned onto the ghost program regardless of what the
     * pack's entity blend directive carries (design doc §12.2): standard
     * translucent blend WITH alpha components, depth writes stay on elsewhere. */
    private static final BlendModeOverride GHOST_BLEND_OVERRIDE = new BlendModeOverride(new BlendMode(
            GL11.GL_SRC_ALPHA, GL11.GL_ONE_MINUS_SRC_ALPHA,
            GL11.GL_ONE, GL11.GL_ONE_MINUS_SRC_ALPHA));

    /** Native entity-cutout discard level, used only when the pack's entities
     * program carries no {@code alphaTest.<program>} directive of its own. */
    private static final AlphaTest FALLBACK_ALPHA_TEST =
            new AlphaTest(AlphaTestFunction.GREATER, 0.1f);

    /** The pack-side {@code entityColor} uniform declaration as it appears
     * after include resolution (shared uniforms include, both stages). */
    private static final Pattern ENTITY_COLOR_UNIFORM =
            Pattern.compile("uniform\\s+vec4\\s+entityColor\\s*;");

    private final ParticleVertexInjector injector = new ParticleVertexInjector();

    /** Lazily built merged vertex source (SSBO decls + pose chunk + main). */
    private String mergedVertexSource;
    /** Which hurt-overlay variant the cached merged source was built for. */
    private boolean mergedSourceOverlay;

    private ShaderInstance entitiesShader;
    private ShaderInstance ghostShader;
    /** Tri-state: true compiled, false sticky-failed/unavailable, null pending. */
    private Boolean shadowReady;
    private ShaderInstance shadowShader;
    private int shadowGtextureUnit = -1;
    private String shadowLastError = "";
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

            // Hurt-overlay path decision (MUST precede buildMergedSource — its
            // cmi_Tint line differs between the variants). EXACT: the merged
            // vertex block declares `out vec4 entityColor;` with the per-
            // instance vanilla overlay state and the pack's own fragment code
            // mixes it (Iris EntityPatcher value semantics). Only possible when
            // the pack ships no entity geometry/tessellation stage (no
            // passthrough implemented) and its VERTEX stage does not consume
            // entityColor itself (our out declaration would collide with its
            // uniform). FALLBACK: no entityColor at all; cmi_Tint carries a
            // vanilla-parameterised approximation (constant post-hit, no fade).
            //
            // The sources below are the ones Iris already include-resolved and
            // preprocessed AT PACK LOAD (ShaderPack's sourceProvider), so every
            // check/conversion here is pure regex. NEVER re-run JcppProcessor
            // on them: its guard throws on the hoisting sentinels its own load
            // pass leaves inside comments whenever a pack ships #version or
            // #extension inside /* */ (Photon's global.glsl does) —
            // "Some shader author is trying to exploit internal Iris
            // implementation details, stop!".
            boolean overlayExact = refProgram.getGeometrySource().isEmpty()
                    && refProgram.getTessControlSource().isEmpty();
            if (overlayExact) {
                String withoutDecl = ENTITY_COLOR_UNIFORM.matcher(vertRef).replaceAll("");
                if (withoutDecl.contains("entityColor"))
                    overlayExact = false; // pack vertex code reads it — cannot replace
            }

            // Pre-strip the pack's entityColor uniform from the vertex source:
            // the injector would otherwise skip OUR `out vec4 entityColor;`
            // declaration as a name clash against the parsed pack tree (the
            // uniform arrives through the shared uniforms include), leaving the
            // merged assignment referencing an undeclared name.
            String packVertex = overlayExact
                    ? ENTITY_COLOR_UNIFORM.matcher(vertRef).replaceAll("")
                    : vertRef;

            String patched = this.injector.patch(packVertex, this.buildMergedSource(overlayExact), SHADER_NAME);
            if (patched == packVertex)
                return fail("vertex injection failed");
            patched = JcppProcessor.glslPreprocessSource(patched,
                    StandardMacros.createStandardEnvironmentDefines());

            // Fragment side: convert the pack's own entityColor declaration
            // into the matching input so its mix(color.rgb, entityColor.rgb,
            // entityColor.a) line consumes our varying. Packs whose entity
            // program never mentions entityColor are unchanged — they simply
            // never flash, exactly like the real entities they draw.
            String fragResolved = overlayExact
                    ? ENTITY_COLOR_UNIFORM.matcher(fragRef).replaceAll("in vec4 entityColor;")
                    : fragRef;
            CreateManaIndustry.LOGGER.info("[CMI particles] pack entity merge: hurt overlay via {}",
                    overlayExact ? "pack entityColor chain (exact)" : "vertex tint approximation");

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
            this.entitiesShader = this.compileMerged(accessor, programSet, properties,
                    patched, fragResolved, refProgram, SHADER_NAME, refProgram.getDirectives(), null);
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
            this.ghostShader = this.compileMerged(accessor, programSet, properties,
                    patched, fragResolved, refProgram, GHOST_SHADER_NAME, ghostDirectives, GHOST_BLEND_OVERRIDE);
            if (this.ghostShader == null)
                return fail("Iris returned no ghost shader instance");
            this.ghostGtextureUnit = findSamplerUnit(this.ghostShader);

            // Third program: the S-track. Compiled last and fully isolated --
            // any failure here only costs particle shadows, never the merge.
            this.compileShadow(accessor, programSet, properties, resolver);

            CreateManaIndustry.LOGGER.info(
                    "[CMI particles] pack entity merge ready ({}+{}, gtexture {}/{}, inherited alphaTest={})",
                    SHADER_NAME, GHOST_SHADER_NAME, this.gtextureUnit, this.ghostGtextureUnit,
                    inheritedAlpha);
            return true;
        } catch (Exception e) {
            this.entitiesShader = null;
            this.ghostShader = null;
            this.gtextureUnit = -1;
            this.ghostGtextureUnit = -1;
            return fail(describeCompileFailure(e));
        }
    }

    public ShaderInstance entitiesShader() {
        return this.entitiesShader;
    }

    public ShaderInstance ghostShader() {
        return this.ghostShader;
    }

    /** The merged shadow-map program; null when the shadow track is down. */
    public ShaderInstance shadowShader() {
        return this.shadowShader;
    }

    /** Texture unit Iris assigned to the shadow fragment's main sampler. */
    public int shadowGtextureUnit() {
        return this.shadowGtextureUnit;
    }

    /** Whether the merged shadow program is compiled and usable this pipeline. */
    public boolean shadowReady() {
        return Boolean.TRUE.equals(this.shadowReady);
    }

    /** Why the shadow track is down (sticky per pipeline); empty when up. */
    public String shadowLastError() {
        return this.shadowLastError;
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
        this.shadowReady = null;
        this.shadowShader = null;
        this.shadowGtextureUnit = -1;
        this.shadowLastError = "";
        this.owningPipeline = null;
        // The merged source embeds the allay_pose.glsl chunk text; a resource
        // reload may have changed it. Keeping the cache would short-circuit
        // buildMergedSource and silently pin the merged path to pre-reload
        // chunk text while the self-drawn path (ParticlePrograms rebuild) picks
        // up the new one.
        this.mergedVertexSource = null;
        this.mergedSourceOverlay = false;
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
            CreateManaIndustry.LOGGER.warn("[CMI particles] pack entity merge unavailable: {}; staying on the self-drawn path", reason);
        this.failed = true;
        this.lastError = reason;
        return false;
    }

    /**
     * Builds the S-track program from the pack's SHADOW pipeline (same merged
     * vertex main, same injector rewrites; the shadow modelview maps the same
     * camera-relative level space, see CmiPackEntityMergeHook). Deliberately
     * NOT part of {@link #fail}'s sticky semantics: a down shadow track never
     * degrades the gbuffer pair -- particles simply cast no shadows.
     */
    private void compileShadow(IrisRenderingPipelineAccessor accessor, ProgramSet programSet,
                               net.irisshaders.iris.shaderpack.properties.ShaderProperties properties,
                               ProgramFallbackResolver resolver) {
        this.shadowShader = null;
        this.shadowGtextureUnit = -1;
        try {
            // Entities-first: native entity shadows are drawn through the pack's
            // shadow_entities program (ShaderKey.SHADOW_ENTITIES_CUTOUT), whose
            // vertex stage consumes ONLY gl_ModelViewMatrix/gl_ProjectionMatrix --
            // exactly the matrices we upload -- and skips terrain-only extras
            // (waving-animation round-trips through named shadowModelView/
            // cameraPosition uniforms, COLORED_LIGHTS voxelization). The plain
            // shadow (terrain) program is only the fallback for packs without it.
            var refProgram = resolver.resolve(ProgramId.ShadowEntities).orElse(null);
            ProgramId shadowVariant = ProgramId.ShadowEntities;
            if (refProgram == null) {
                refProgram = resolver.resolve(ProgramId.Shadow).orElse(null);
                shadowVariant = ProgramId.Shadow;
            }
            if (refProgram == null)
                { shadowDown("pack has neither shadow_entities nor shadow program source"); return; }
            var vertRef = refProgram.getVertexSource().orElse(null);
            var fragRef = refProgram.getFragmentSource().orElse(null);
            if (vertRef == null || fragRef == null)
                { shadowDown("shadow program source missing vertex or fragment"); return; }

            // Plain overlay variant: shadows are depth-only and the shadow
            // program's own entityColor handling (if any) is left untouched —
            // our out declaration must not collide with its uniform include.
            String patched = this.injector.patch(vertRef, this.buildMergedSource(false), SHADOW_SHADER_NAME);
            if (patched == vertRef)
                { shadowDown("vertex injection failed"); return; }
            patched = JcppProcessor.glslPreprocessSource(patched,
                    StandardMacros.createStandardEnvironmentDefines());

            // Route-A again: inherit the resolved Shadow directives wholesale so
            // alphaTest.shadow/blend.shadow behave exactly as for native draws;
            // absent fields fall through to the VARIANT fallback chosen above.
            var source = this.makeSource(programSet, properties, patched, fragRef, refProgram)
                    .withDirectiveOverride(refProgram.getDirectives());
            AlphaTest shadowFallback = shadowVariant == ProgramId.ShadowEntities
                    ? FALLBACK_ALPHA_TEST_SHADOW_ENTITIES : FALLBACK_ALPHA_TEST_SHADOW_PLAIN;
            this.shadowShader = accessor.invokeCreateShadowShader(SHADOW_SHADER_NAME, source, shadowVariant,
                    shadowFallback, IrisVertexFormats.TERRAIN, false, false, false, false);
            if (this.shadowShader == null)
                { shadowDown("Iris returned no shadow shader instance"); return; }
            seedDummyModelViewUniform(this.shadowShader);

            this.shadowGtextureUnit = findSamplerUnit(this.shadowShader);
            this.shadowReady = true;
            this.shadowLastError = "";
            CreateManaIndustry.LOGGER.info("[CMI particles] pack entity merge: shadow track ready ({}, gtexture {})",
                    shadowVariant == ProgramId.ShadowEntities ? "shadow_entities" : "shadow",
                    this.shadowGtextureUnit);
        } catch (Exception e) {
            this.shadowShader = null;
            this.shadowGtextureUnit = -1;
            shadowDown(describeCompileFailure(e));
        }
    }

    /** Sticky shadow-track failure record; never affects the gbuffer pair. */
    private void shadowDown(String reason) {
        this.shadowReady = false;
        this.shadowLastError = reason;
        CreateManaIndustry.LOGGER.info("[CMI particles] shadow track unavailable (particles cast no shadows): {}", reason);
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
                                         String patchedVertex, String fragRef, ProgramSource refProgram, String name,
                                         net.irisshaders.iris.shaderpack.properties.ProgramDirectives directives,
                                         BlendModeOverride pinBlend) throws IOException {
        var source = this.makeSource(programSet, properties, patchedVertex, fragRef, refProgram)
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
        if (shader != null)
            seedDummyModelViewUniform(shader);
        CreateManaIndustry.LOGGER.info("[CMI particles] invokeCreateShader({}) -> {}",
                name, shader == null ? "null" : "ShaderInstance#" + shader.getId());
        return shader;
    }

    /**
     * Builds one synthetic ProgramSource that carries the PACK's optional
     * geometry / tessellation stages over verbatim -- same as iris-flw-compat's
     * {@code programSourceOverrideVertexSource}: only the vertex stage is
     * injected here, while a geometry/tess stage consumes the pack varyings our
     * rewritten vertex writes, so dropping those sources would silently lose
     * packs that ship an entity geometry pipeline. The constructor blend
     * argument stays null -- the effective blend comes from the wholesale
     * directive inheritance at the call sites.
     */
    private ProgramSource makeSource(ProgramSet programSet, net.irisshaders.iris.shaderpack.properties.ShaderProperties properties,
                                     String vertex, String fragment, ProgramSource refProgram) {
        return new ProgramSource(SHADER_NAME, vertex,
                refProgram.getGeometrySource().orElse(null),
                refProgram.getTessControlSource().orElse(null),
                refProgram.getTessEvalSource().orElse(null),
                fragment, programSet, properties, null);
    }

    /**
     * Seeds a dummy {@code ModelViewMat} uniform so vanilla
     * {@link ShaderInstance#apply()} survives on merged programs whose generated
     * JSON declares only {@code iris_*} matrices -- without it the vanilla
     * static handle stays null and apply() NPEs mid-upload, skipping every
     * later ExtendedShader side effect. Same runtime assignment as
     * iris-flw-compat's IrisFlwCompatGlProgram constructor; requires the
     * accesstransformer.cfg final-strip on MODEL_VIEW_MATRIX. Type 10 is
     * blaze3d's abstracted matrix4x4 with 16 floats -- apply() uploads the real
     * values on every use.
     */
    private static void seedDummyModelViewUniform(ShaderInstance shader) {
        if (shader.MODEL_VIEW_MATRIX == null)
            shader.MODEL_VIEW_MATRIX = new Uniform("ModelViewMat", 10, 16, shader);
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
     *
     * <p>{@code withEntityColor} selects the hurt-overlay variant: true
     * declares {@code out vec4 entityColor;} and writes the per-instance
     * vanilla overlay state (the compiler converts the pack's uniform
     * declarations into the matching varying inputs); false omits entityColor
     * entirely and lets {@code cmi_Tint} carry the vanilla-parameterised
     * approximation.</p>
     */
    private String buildMergedSource(boolean withEntityColor) {
        if (this.mergedVertexSource != null && this.mergedSourceOverlay == withEntityColor)
            return this.mergedVertexSource;

        String chunk = ParticlePrograms.loadPlain("shaders/particles/chunks/allay_pose.glsl");
        if (chunk.isEmpty())
            throw new IllegalStateException("cannot load chunks/allay_pose.glsl");

        StringBuilder sb = new StringBuilder(8192);
        // Placeholder header only -- the injector floors the PACK header
        // separately and parses this block under the same profile. Highest
        // language need in this block is usamplerBuffer/floatBitsToUint
        // (GLSL 330); no SSBOs remain since the TBO migration.
        sb.append("#version 400 core\n");
        // constants ride the AST as plain const declarations -- #define lines
        // vanish when declarations transplant across glsl-transformer trees
        sb.append("const uint MODEL_VERTEX_FLOATS = ").append(ParticlePrograms.modelVertexFloats()).append("u;\n");
        sb.append("const uint VEC4_PER_EMITTER = 20u;\n");
        // rest-pose model above-feet height in blocks -- the vanilla-size scale
        // divisor shared with model.vsh / hit.comp (emitted as a plain const
        // here because #defines do not survive the AST transplant)
        sb.append("const float MODEL_ABOVE_FEET = ").append(ParticlePrograms.modelAboveFeet()).append(";\n");
        // particle data arrives through TBO views: plain samplerBuffer uniforms
        // survive every in-game pass, interface blocks demonstrably do not
        sb.append("uniform samplerBuffer cmi_Geo;\n");
        sb.append("uniform samplerBuffer cmi_Pool;\n");
        sb.append("uniform samplerBuffer cmi_Emitters;\n");
        sb.append("uniform usamplerBuffer cmi_Sorted;\n");
        // held item (partId 7): the display transform constant (JOML-computed,
        // see HeldItemGeometry) as a plain const — #defines do not survive the
        // AST transplant — and the per-tier sword atlas rects. uWave /
        // uWaveTarget / uTimeSec arrive through chunks/allay_pose.glsl's
        // top-included allay_storm chunk (declared next to the shared
        // cmiStormWaveClaim predicate), so they must NOT be re-declared here.
        sb.append("const mat4 CMI_HELD_DISPLAY = ").append(ParticlePrograms.heldItemDisplayMatrix()).append(";\n");
        sb.append("""
                uniform float uWaveTier[4];  // held-sword material id per slot (0 = none)
                uniform vec4 uHeldItemUV[7]; // per-tier atlas rects {uvMin.xy, uvMax.xy}, slot 0 unused
                """);
        sb.append("""
                uniform vec3 cmi_CameraPos;
                uniform float cmi_FadeDist;

                // Sort-buffer metadata tail slot (index == capacity +
                // CARRIER_CAP, see ParticleBuffers allocation). capture.comp
                // stores THIS generation's exact MODEL item count there, inside
                // the same buffer as the permutation itself, so the count
                // consumed below is structurally always the same generation as
                // the items -- an aborted frame leaves count and permutation
                // stale together instead of pairing a fresh count with a stale
                // array.
                uniform int cmi_MetaSlot;
                // Fixed base of the held-item CARRIER region in the same buffer
                // (== the particle capacity; keygen appends carrier instances
                // there, never radix-touched).
                uniform int cmi_CarrierBase;
                // Segment selector: 0 = ghost segment, keep the array's native
                // back-to-front order for correct blending. 1 = cutout segment:
                // iterate the MODEL partition NEAREST first, so early-Z rejects
                // every occluded fragment before the pack's fragment shader runs
                // (dense-swarm fragment pressure collapses from k shaded layers
                // per pixel to ~1). 2 = held-item CARRIER segment: instances
                // index the carrier region at cmi_CarrierBase (forward, exact
                // count).
                uniform int cmi_SegmentMode;

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
        if (withEntityColor) {
            // Consumed by the pack fragment stage's own entityColor mix line;
            // ShaderPackProgramCompiler converts the pack's uniform declaration
            // into the matching `in` post-include. Value semantics mirror Iris
            // EntityPatcher: (overlayColor.rgb, 1 - overlayColor.a).
            sb.append("out vec4 entityColor;\n\n");
        }
        sb.append(chunk).append('\n');
        sb.append("""
                void main() {
                    // Resolve this instance through the MODEL partition of the
                    // sort array. The partition is depth-ordered far -> near (far
                    // items carry the small key); the cutout segment reverses its
                    // slot so instances arrive NEAREST first across the indirect
                    // draw, letting early-Z collapse dense overlaps from k shaded
                    // layers per pixel to ~1. N_model comes from the metadata tail
                    // slot of this same buffer, so index math can never mix
                    // generations (see the uniform comment above). The CARRIER
                    // segment bypasses the partition: its exact-count instances
                    // index the fixed-base carrier region behind the radix output.
                    uint iid = uint(gl_InstanceID);
                    uvec2 item;
                    if (cmi_SegmentMode == 2) {
                        item = texelFetch(cmi_Sorted, int(cmi_CarrierBase + iid)).xy;
                    } else {
                        uint nModel = max(texelFetch(cmi_Sorted, cmi_MetaSlot).x, 1u);
                        // Stale-count net: on an aborted frame the indirect draw count
                        // can exceed the committed partition's N; clamp every index
                        // into [0, N) so texelFetch stays defined and any extra
                        // invocation degrades to a harmless duplicate instance.
                        uint fwd = min(iid, nModel - 1u);
                        uint sortSlot = (cmi_SegmentMode == 1 && iid < nModel)
                            ? (nModel - 1u - iid)   // cutout: nearest item first
                            : fwd;                  // ghost: farthest item first
                        item = texelFetch(cmi_Sorted, int(sortSlot)).xy;
                    }
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
                    // storm members carry their IDENTITY in p0.w — the header's
                    // storm slot 18.x selects the constant 1.0 multiplier
                    float pmul = texelFetch(cmi_Emitters, int(hb + 18u)).x > 0.5 ? 1.0 : p0.w;
                    float size = mix(sizeStart, sizeEnd, pow(life, sizeEase)) * pmul;
                    float scale = (2.0 * size) / MODEL_ABOVE_FEET;

                    int anim = int(texelFetch(cmi_Emitters, int(hb + 17u)).x);
                    // Storm members: inner-band members near the chased anchor
                    // periodically run full vanilla dance cycles.
                    vec4 stormA = texelFetch(cmi_Emitters, int(hb + 18u));
                    // HP-death corpse: per-particle death pose + roll timer
                    // (time since death; update.comp counts hp 0 -> -1 over the
                    // vanilla 20-tick window while age keeps the idle sway).
                    // hp <= 0 (not < 0): the kill frame itself already counts
                    // as dead, matching update.comp's corpse predicate.
                    bool corpse = p3.y <= 0.0;
                    // ---- carried item (held sword): the carrier segment ------
                    // Only partId-7 vertices arrive through the carrier command
                    // (cmi_SegmentMode == 2), and keygen admitted each instance
                    // through the SAME shared cmiStormWaveClaim predicate used
                    // below — a member renders its sword exactly when it sits in
                    // the carrier partition. Storm wave-squad members carry the
                    // claim's tier (corpses never carry — vanilla drops the held
                    // item on death); non-storm debug emitters carry their
                    // header item, corpses included, like the old full-mesh
                    // path. Body vertices never run this block: the per-vertex
                    // item invocations for every non-carrier instance are gone.
                    float carryRamp = 0.0;
                    float itemId = 0.0;
                    if (pid == 7 && !gone) {
                        float waveTier = 0.0;
                        if (!corpse && stormA.x > 0.5) {
                            int k = cmiStormWaveClaim(p3.z);
                            if (k >= 0) {
                                carryRamp = cmiStormCarryRamp(uTimeSec, uWave[k].z, uWave[k].w);
                                waveTier = uWaveTier[k];
                            }
                        }
                        // held-item id: a wave claim overrides the header slot
                        // (storm specs keep 17.z = 0, so the paths never stack)
                        itemId = waveTier > 0.5 ? waveTier
                                : texelFetch(cmi_Emitters, int(hb + 17u)).z;
                        // defensive only: keygen guarantees an item for every
                        // carrier instance; a mismatch means upstream corruption
                        gone = itemId <= 0.5;
                    }
                    if (stormA.x > 0.5 && !gone) {
                        anim = cmiStormAnimOverride(
                                distance(p0.xyz, texelFetch(cmi_Emitters, int(hb + 19u)).xyz),
                                stormA.y, p3.z, uTimeSec);
                    }
                    if (corpse)
                        anim = 3;
                    float sinceDeath = corpse ? -p3.y : 0.0;
                    float yaw;
                    mat4 M = gone ? mat4(1.0) : cmiAllayPartTransform(p3.x, p3.z, p1.xyz, anim, pid, yaw, carryRamp);

                    vec3 local = gone ? vec3(0.0) : vec3(texelFetch(cmi_Geo, int(vb)).x, texelFetch(cmi_Geo, int(vb + 1u)).x, texelFetch(cmi_Geo, int(vb + 2u)).x);
                    vec2 uv = gone ? vec2(0.5) : vec2(texelFetch(cmi_Geo, int(vb + 3u)).x, texelFetch(cmi_Geo, int(vb + 4u)).x);
                    if (pid == 7 && !gone) {
                        // canonical sprite UV -> the carrier tier's atlas frame
                        int tier = clamp(int(itemId + 0.5), 0, 6);
                        uv = mix(uHeldItemUV[tier].xy, uHeldItemUV[tier].zw, uv);
                    }
                    vec3 pm = (M * vec4(local, 1.0)).xyz / 16.0;
                    // vanilla chain after the parts: T(0,-1.501,0) then
                    // S(-1,-1,1), then the death roll (setupRotations' Rz,
                    // INNER to the facing yaw -- PoseStack mulPose
                    // post-multiplies, so the roll consumes the already-flipped
                    // feet-relative vector and the corpse tips onto its OWN
                    // side), then Ry(180deg - yaw) * scale. Keep this in sync
                    // with model.vsh.
                    vec3 flipped = vec3(-pm.x, 1.501 - pm.y, pm.z);
                    if (anim == 3)
                        flipped = cmiDeathRoll(flipped, sinceDeath);
                    flipped *= scale;
                    float ry = CMI_PI - yaw;
                    vec3 worldOff = vec3(cos(ry) * flipped.x + sin(ry) * flipped.z,
                                         flipped.y,
                                         -sin(ry) * flipped.x + cos(ry) * flipped.z);
                    vec3 nPart = mat3(M) * CMI_FACE_NORMALS[normalCode];
                    vec3 nFlipped = vec3(-nPart.x, -nPart.y, nPart.z);
                    if (anim == 3)
                        nFlipped = cmiDeathRoll(nFlipped, sinceDeath);
                    vec3 nWorld = vec3(cos(ry) * nFlipped.x + sin(ry) * nFlipped.z,
                                       nFlipped.y,
                                       -sin(ry) * nFlipped.x + cos(ry) * nFlipped.z);
                    vec3 world = p0.xyz + worldOff;
                    cmi_NormalLevel = nWorld;

                    cmi_VertexLevel = gone
                        ? vec4(0.0, 0.0, 1e9, 1.0)
                        : vec4(world - cmi_CameraPos, 1.0);
                    cmi_TexCoord0v = vec4(uv, 0.0, 1.0);
                    cmi_LightCoordv = vec4(240.0, 240.0, 0.0, 1.0); // block light 15 + full sky

                    vec4 kfr[8];
                    for (int i = 0; i < 8; i++) kfr[i] = gone ? vec4(1.0) : texelFetch(cmi_Emitters, int(hb + 8u + uint(i)));
                    vec4 tint = cmiKeyframeColor(kfr, int(gone ? 1 : texelFetch(cmi_Emitters, int(hb + 6u)).z), life);
                    vec3 baseTint = tint.rgb * p2.rgb;
                """);
        if (withEntityColor) {
            sb.append("""
                    // vanilla hurt overlay via the pack's own entityColor chain:
                    // mirror Iris EntityPatcher value semantics (entityColor =
                    // (overlayColor.rgb, 1 - overlayColor.a) with the STATIC red
                    // overlay texel 255,0,0/178), so the pack fsh applies its
                    // mix(color.rgb, entityColor.rgb, entityColor.a) -- the
                    // constant 30.2% pure-red wash, no fade, exactly what a real
                    // entity gets under this pack. Active while the hurt timer
                    // (p2.w) runs or the corpse countdown (hp <= 0) runs.
                    entityColor = (!gone && (p2.w > 0.0 || p3.y <= 0.0))
                        ? vec4(1.0, 0.0, 0.0, 77.0 / 255.0)
                        : vec4(0.0);
                    cmi_Tint = vec4(baseTint, 1.0);
                    """);
        } else {
            sb.append("""
                    // fallback (no entityColor chain available): constant
                    // vanilla-flavoured approximation riding the vertex tint --
                    // pre-diffuse and multiplicative, a documented deviation
                    // (the exact path applies the overlay post-diffuse in the
                    // pack's own fragment code instead)
                    cmi_Tint = vec4((!gone && (p2.w > 0.0 || p3.y <= 0.0))
                        ? mix(baseTint, vec3(1.0, 0.0, 0.0), 77.0 / 255.0)
                        : baseTint, 1.0);
                    """);
        }
        sb.append("""
                }
                """);

        this.mergedSourceOverlay = withEntityColor;
        this.mergedVertexSource = sb.toString();
        return this.mergedVertexSource;
    }
}
