package com.iridium126.createmanaindustry.client.particles.shaderpack;

import com.iridium126.createmanaindustry.CreateManaIndustry;
import com.iridium126.createmanaindustry.client.particles.engine.ParticlePrograms;

import net.irisshaders.iris.Iris;
import net.irisshaders.iris.gl.blending.AlphaTest;
import net.irisshaders.iris.gl.blending.AlphaTestFunction;
import net.irisshaders.iris.gl.blending.BlendMode;
import net.irisshaders.iris.gl.blending.BlendModeOverride;
import net.irisshaders.iris.gl.state.FogMode;
import net.irisshaders.iris.pipeline.IrisRenderingPipeline;
import net.irisshaders.iris.pipeline.WorldRenderingPipeline;
import net.irisshaders.iris.shaderpack.loading.ProgramId;
import net.irisshaders.iris.shaderpack.preprocessor.JcppProcessor;
import net.irisshaders.iris.shaderpack.programs.ProgramFallbackResolver;
import net.irisshaders.iris.shaderpack.programs.ProgramSet;
import net.irisshaders.iris.shaderpack.programs.ProgramSource;
import net.irisshaders.iris.gl.shader.StandardMacros;
import net.irisshaders.iris.vertices.IrisVertexFormats;
import net.minecraft.client.renderer.ShaderInstance;
import top.leonx.irisveil.accessors.IrisRenderingPipelineAccessor;
import top.leonx.irisveil.accessors.ProgramDirectivesAccessor;
import top.leonx.irisveil.accessors.ProgramSourceAccessor;

import org.lwjgl.opengl.GL20;

import java.util.List;

/**
 * Compiles the CMI MODEL-particle draw into the active shader pack's lighting
 * context: resolves the pack's Block program via Iris's own fallback chain,
 * injects the SSBO-driven allay vertex program with {@link
 * ParticleVertexInjector}, and builds TWO Iris-managed {@link ShaderInstance}s
 * from the same patched sources -- one with the pack's opaque blend (the
 * cutout segment, indirect cmd2) and one forced to standard alpha blending
 * (the cloak/wings segment, cmd3; Block programs are opaque by default, so the
 * ghost segment's blend mode must be substituted).
 *
 * <p>Core flow adapted from iris-veil-compat's {@code IrisVeilProgramLinker}
 * / iris-flw-compat's {@code IrisProgramLinker} (MIT, (c) top.leonx): pipeline
 * accessor -> ProgramSet -> ProgramFallbackResolver(ProgramId.Block) -> patch
 * -> jcpp preprocess -> ProgramSource -> callCreateShader.</p>
 *
 * <p>All iris-veil-compat type references in this class require the mod to be
 * loaded -- instantiate only under {@link CreateManaIndustry#IRISVEIL_ACTIVE}.</p>
 */
public final class ShaderPackProgramCompiler {

    /** SSBO binding slots for the merged program: deliberately high so neither
     * Iris nor a coexisting Flywheel stack can claim them (see design doc §5). */
    public static final int MERGED_BINDING_GEO = 12;
    public static final int MERGED_BINDING_POOL = 13;
    public static final int MERGED_BINDING_EMITTERS = 14;
    public static final int MERGED_BINDING_SORT = 15;

    private static final String SHADER_NAME = "cmi_model_particles";

    /**
     * Standard alpha blending for the cloak/wings segment. Block programs are
     * opaque by default in most packs, so the ghost segment's blend mode must
     * be substituted explicitly (same src/alpha factors as the L0 path).
     */
    private static final BlendModeOverride GHOST_BLEND_OVERRIDE =
            new BlendModeOverride(new BlendMode(org.lwjgl.opengl.GL11.GL_SRC_ALPHA,
                    org.lwjgl.opengl.GL11.GL_ONE_MINUS_SRC_ALPHA,
                    org.lwjgl.opengl.GL11.GL_ONE,
                    org.lwjgl.opengl.GL11.GL_ONE_MINUS_SRC_ALPHA));

    private final ParticleVertexInjector injector = new ParticleVertexInjector();

    /** Lazily built merged vertex source (SSBO decls + pose chunk + main). */
    private String mergedVertexSource;

    private ShaderInstance opaqueShader;
    private ShaderInstance translucentShader;
    private Object owningPipeline; // identity of the pipeline the shaders belong to
    /** Texture unit Iris assigned to the pack fragment's gtexture sampler. */
    private int gtextureUnit = -1;
    private String lastError = "";
    private boolean failed;

    public boolean isReady() {
        return opaqueShader != null && translucentShader != null;
    }

    public String lastError() {
        return this.lastError;
    }

    /**
     * Builds (or rebuilds, after a pipeline change) the merged programs.
     * Returns false on any failure -- the hook then falls back to L0 and this
     * class records the reason for /cmip shaderpack status.
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
        try {
            var accessor = (IrisRenderingPipelineAccessor) pipeline;
            ProgramSet programSet = accessor.getProgramSet();

            var resolver = new ProgramFallbackResolver(programSet);
            var refProgram = resolver.resolve(ProgramId.Block).orElse(null);
            if (refProgram == null)
                return fail("pack has no Block program source (fallback chain exhausted)");
            var vertRef = refProgram.getVertexSource().orElse(null);
            var fragRef = refProgram.getFragmentSource().orElse(null);
            if (vertRef == null || fragRef == null)
                return fail("Block program source missing vertex or fragment");

            String patched = this.injector.patch(vertRef, this.buildMergedSource(), SHADER_NAME);
            if (patched == vertRef)
                return fail("vertex injection failed");
            patched = JcppProcessor.glslPreprocessSource(patched,
                    StandardMacros.createStandardEnvironmentDefines());

            var properties = ((ProgramSourceAccessor) refProgram).getShaderProperties();
            BlendModeOverride packBlend = ((ProgramSourceAccessor) refProgram).getBlendModeOverride();

            this.opaqueShader = accessor.invokeCreateShader(
                    SHADER_NAME + "_opaque",
                    this.makeSource(programSet, properties, packBlend, patched, fragRef, "opaque"),
                    ProgramId.Block, AlphaTest.ALWAYS,
                    IrisVertexFormats.TERRAIN, FogMode.OFF,
                    false, false, false, false, false);
            // The ghost segment needs real alpha blending; Block programs default
            // to the pack's opaque blend, so substitute the standard translucent mode.
            this.translucentShader = accessor.invokeCreateShader(
                    SHADER_NAME + "_translucent",
                    this.makeSource(programSet, properties, GHOST_BLEND_OVERRIDE, patched, fragRef, "translucent"),
                    ProgramId.Block, AlphaTest.ALWAYS,
                    IrisVertexFormats.TERRAIN, FogMode.OFF,
                    false, false, false, false, false);

            if (this.opaqueShader == null || this.translucentShader == null)
                return fail("Iris returned no shader instance");

            this.gtextureUnit = findSamplerUnit(this.opaqueShader);
            CreateManaIndustry.LOGGER.info(
                    "[CMI particles] shader-pack path ready ({} gtexture unit {})",
                    SHADER_NAME, this.gtextureUnit);
            return true;
        } catch (Exception e) {
            this.opaqueShader = null;
            this.translucentShader = null;
            this.gtextureUnit = -1;
            return fail(e.toString());
        }
    }

    public ShaderInstance opaqueShader() {
        return this.opaqueShader;
    }

    public ShaderInstance translucentShader() {
        return this.translucentShader;
    }

    public int gtextureUnit() {
        return this.gtextureUnit;
    }

    /** Drops compiled state (pipeline changed, reload, or failure). */
    public void reset() {
        this.opaqueShader = null;
        this.translucentShader = null;
        this.gtextureUnit = -1;
        this.owningPipeline = null;
    }

    static void logTransformFailure(String shaderName, Exception e) {
        CreateManaIndustry.LOGGER.error("[CMI particles] AST injection failed for {}", shaderName, e);
    }

    private boolean fail(String reason) {
        if (!this.failed)
            CreateManaIndustry.LOGGER.warn("[CMI particles] shader-pack path unavailable: {}; staying on the self-drawn path", reason);
        this.failed = true;
        this.lastError = reason;
        return false;
    }

    private ProgramSource makeSource(ProgramSet programSet, net.irisshaders.iris.shaderpack.properties.ShaderProperties properties,
                                     BlendModeOverride blend, String vertex, String fragment, String suffix) {
        return new ProgramSource(SHADER_NAME + "_" + suffix, vertex,
                null, null, null, fragment, programSet, properties, blend);
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
        sb.append("#version 330 core\n"); // placeholder; the injector bumps it to the pack version
        sb.append("#define VEC4_PER_PARTICLE 4u\n");
        sb.append("#define VEC4_PER_EMITTER ").append(20).append("u\n");
        sb.append("#define MODEL_VERTEX_FLOATS ").append(ParticlePrograms.modelVertexFloats()).append('\n');
        sb.append("""
                layout(std430, binding = 13) readonly buffer CmiPool { vec4 data[]; };
                layout(std430, binding = 14) readonly buffer CmiEmitters { vec4 u[]; };
                layout(std430, binding = 15) readonly buffer CmiSorted { uvec2 kv[]; };
                layout(std430, binding = 12) readonly buffer CmiGeo { float v[]; };

                uniform vec3 cmi_CameraPos;
                uniform float cmi_FadeDist;
                uniform mat4 cmi_ModelViewMat;

                // results consumed by the pack code through the injector's rewrites
                vec4 cmi_VertexView;   // view-space vertex (view transform pre-baked)
                vec4 cmi_TexCoord0v;   // atlas UV as a gl_MultiTexCoord0 stand-in
                ivec4 cmi_LightCoordv; // full-bright lightmap (block light 15)
                vec3 cmi_NormalLevel;  // level-space pose normal
                vec4 cmi_Tint;         // emitter colour keyframes x per-particle tint

                """);
        sb.append(chunk).append('\n');
        sb.append("""
                void main() {
                    // resolve this instance through the MODEL partition of the sort array
                    uvec2 item = cmiSorted.kv[gl_InstanceID];
                    bool gone = (item.y & 3u) != 0u; // foreign-type item: upstream corruption guard
                    uint inst = item.y >> 2u;
                    uint base = inst * 4u;
                    vec4 p0 = gone ? vec4(0.0) : cmiPool.data[base + 0u];
                    vec4 p1 = gone ? vec4(0.0) : cmiPool.data[base + 1u];
                    vec4 p2 = gone ? vec4(0.0) : cmiPool.data[base + 2u];
                    vec4 p3 = gone ? vec4(0.0) : cmiPool.data[base + 3u];

                    uint vb = uint(gl_VertexID) * MODEL_VERTEX_FLOATS;
                    int pid = int(cmiGeo.v[vb + 5u]);
                    int normalCode = int(cmiGeo.v[vb + 6u]);

                    float life = clamp(p3.x / max(p3.y, 1e-5), 0.0, 1.0);
                    uint eid = floatBitsToUint(p3.w);
                    uint hb = eid * VEC4_PER_EMITTER;

                    // per-instance fade early-out: collapse offscreen consistently per triangle
                    gone = gone || distance(p0.xyz, cmi_CameraPos) > cmi_FadeDist + 24.0;

                    float sizeStart = gone ? 0.0 : cmiEmitters.u[hb + 5u].z;
                    float sizeEnd = gone ? 0.0 : cmiEmitters.u[hb + 5u].w;
                    float sizeEase = max(cmiEmitters.u[hb + 6u].x, 0.001);
                    float size = mix(sizeStart, sizeEnd, pow(life, sizeEase)) * p0.w;
                    float scale = (2.0 * size) / 0.625;

                    int anim = int(cmiEmitters.u[hb + 17u].x);
                    float yaw;
                    mat4 M = gone ? mat4(1.0) : cmiAllayPartTransform(p3.x, p3.z, p1.xyz, anim, pid, yaw);

                    vec3 local = gone ? vec3(0.0) : vec3(cmiGeo.v[vb], cmiGeo.v[vb + 1u], cmiGeo.v[vb + 2u]);
                    vec2 uv = gone ? vec2(0.5) : vec2(cmiGeo.v[vb + 3u], cmiGeo.v[vb + 4u]);
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

                    cmi_VertexView = gone
                        ? vec4(0.0, 0.0, 1e9, 1.0)
                        : cmi_ModelViewMat * vec4(world - cmi_CameraPos, 1.0);
                    cmi_TexCoord0v = vec4(uv, 0.0, 1.0);
                    cmi_LightCoordv = ivec4(240, 240, 0, 1); // block light 15 + full sky

                    vec4 kfr[8];
                    for (int i = 0; i < 8; i++) kfr[i] = gone ? vec4(1.0) : cmiEmitters.u[hb + 8u + uint(i)];
                    vec4 tint = cmiKeyframeColor(kfr, int(gone ? 1 : cmiEmitters.u[hb + 6u].z), life);
                    cmi_Tint = vec4(tint.rgb * p2.rgb, 1.0);
                }
                """);

        this.mergedVertexSource = sb.toString();
        return this.mergedVertexSource;
    }
}