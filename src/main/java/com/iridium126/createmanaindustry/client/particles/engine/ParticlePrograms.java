package com.iridium126.createmanaindustry.client.particles.engine;

import java.io.IOException;
import java.io.Reader;

import com.iridium126.createmanaindustry.CreateManaIndustry;

import net.minecraft.client.Minecraft;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.packs.resources.ResourceManager;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL20;
import org.lwjgl.opengl.GL43;

/**
 * Self-hosted GLSL programs for the particle engine, compiled with raw LWJGL
 * from the mod's bundled sources ({@code assets/createmanaindustry/shaders/
 * particles/*}). This deliberately bypasses Veil's {@code ShaderManager}, whose
 * compute programs turned out inert in this environment (dispatch with no GL
 * error and no kernel execution). Because we compile the GLSL ourselves, the
 * {@code layout(binding=N)} qualifiers are honored directly by GL, and the
 * programs work with or without Veil loaded.
 * <p>
 * Pipeline: reset -> update -> emit (fast path) or
 * reset -> update -> emit -> keygen -> radix{hist,scan,scatter} x1 (sorted
 * path: single-pass counting sort over an inverted 8-bit depth band, i.e.
 * back-to-front), then the model / textured-sprite / additive render programs.
 * <p>
 * A common {@link #PRELUDE} of {@code #define} constants is injected ahead of
 * every shader source; it is GENERATED from the {@code ParticleBuffers}
 * constants so the Java side stays the single source of truth for bindings,
 * indirect-buffer layout indices and structural sizes.
 * <p>
 * A {@code #version} header is prepended (raw GL requires one; Veil used to
 * inject it). Programs are rebuilt by {@link #rebuild()} — called lazily on the
 * render thread whenever {@link #needsRebuild()} is true, which the resource
 * reload listener flips so F3+T recompiles shaders.
 */
public final class ParticlePrograms {

    private static final String GLSL_DIR = "shaders/particles/";
    private static final String VERSION = "#version 450 core\n";

    /**
     * Common GLSL header injected ahead of every shader source: engine-wide
     * constants generated from the {@code ParticleBuffers} constants so there
     * is exactly one place to change a binding or an indirect-layout index.
     * Plain {@code #define}s rather than {@code const}s because they must stay
     * legal inside {@code layout()} qualifiers on every driver (macro
     * substitution is textual and needs no constant-expression support).
     */
    private static final String PRELUDE = buildPrelude();

    private static String buildPrelude() {
        StringBuilder sb = new StringBuilder(768);
        sb.append("// ==== CMI particle engine common constants ====\n");
        sb.append("// ==== GENERATED from ParticleBuffers by ParticlePrograms -- edit THERE, not here ====\n");
        sb.append("#define BIND_POOL_READ ").append(ParticleBuffers.PARTICLE_BB_READ).append('\n');
        sb.append("#define BIND_POOL_WRITE ").append(ParticleBuffers.PARTICLE_BB_WRITE).append('\n');
        sb.append("#define BIND_INDIRECT ").append(ParticleBuffers.INDIRECT_BB).append('\n');
        sb.append("#define BIND_COUNTER ").append(ParticleBuffers.COUNTER_BB).append('\n');
        sb.append("#define BIND_EMITCMD ").append(ParticleBuffers.EMIT_BB).append('\n');
        sb.append("#define BIND_EMITTER ").append(ParticleBuffers.EMITTER_BB).append('\n');
        sb.append("#define BIND_SORT_READ ").append(ParticleBuffers.SORTREAD_BINDING).append('\n');
        sb.append("#define BIND_SORT_WRITE ").append(ParticleBuffers.SORTWRITE_BINDING).append('\n');
        sb.append("#define BIND_ORDER_ADD ").append(ParticleBuffers.ORDERADD_BINDING).append('\n');
        sb.append("#define BIND_HIST ").append(ParticleBuffers.HIST_BINDING).append('\n');
        sb.append("#define BIND_OFFSETS ").append(ParticleBuffers.OFFSET_BINDING).append('\n');
        sb.append("#define BIND_BAKEMETA ").append(ParticleBuffers.BAKEMETA_BINDING).append('\n');
        sb.append("#define BIND_MODELGEO ").append(ParticleBuffers.MODELGEO_BINDING).append('\n');
        sb.append("#define BIND_PREV_COUNTER ").append(ParticleBuffers.PREVCOUNTER_BINDING).append('\n');
        sb.append("#define BIND_ORDER_OPAQUE ").append(ParticleBuffers.ORDEROPAQUE_BINDING).append('\n');
        sb.append("#define INDIRECT_COMMANDS ").append(ParticleBuffers.INDIRECT_COMMANDS).append('\n');
        sb.append("#define INDIRECT_STRIDE ").append(ParticleBuffers.INDIRECT_STRIDE).append('\n');
        sb.append("#define INDIRECT_UINTS ").append(ParticleBuffers.INDIRECT_UINTS).append('\n');
        sb.append("#define IDX_CNT_ADD ").append(ParticleBuffers.IDX_CNT_ADD).append('\n');
        sb.append("#define IDX_CNT_SPRITE ").append(ParticleBuffers.IDX_CNT_SPRITE).append('\n');
        sb.append("#define IDX_CNT_MODELOP ").append(ParticleBuffers.IDX_CNT_MODELOP).append('\n');
        sb.append("#define IDX_CNT_XLU ").append(ParticleBuffers.IDX_CNT_XLU).append('\n');
        sb.append("#define IDX_CNT_ALPHA ").append(ParticleBuffers.IDX_CNT_ALPHA).append('\n');
        sb.append("#define VEC4_PER_PARTICLE ").append(ParticleBuffers.VEC4_PER_PARTICLE).append("u\n");
        sb.append("#define VEC4_PER_EMITTER ").append(ParticleBuffers.VEC4_PER_EMITTER).append("u\n");
        sb.append("#define RADIX_BINS ").append(ParticleBuffers.RADIX_BINS).append("u\n");
        sb.append("#define DEPTH_BANDS ").append(ParticleBuffers.DEPTH_BANDS).append("u\n");
        sb.append("#define BAND_NEAR ").append(ParticleBuffers.BAND_NEAR).append('\n');
        sb.append("#define MODEL_VERTEX_FLOATS ").append(AllayModelGeometry.VERTEX_FLOATS).append('\n');
        return sb.toString();
    }

    private int reset;
    private int update;
    private int emit;
    private int keygen;
    private int radixHist;
    private int radixScan;
    private int radixScatter;
    private int capture;
    private int render;          // additive billboards (soft circle)
    private int texturedRender;  // textured sprite billboards: uMode 0 blended / 1 OPAQUE cutout
    private int modelRender;     // instanced allay models via one merged multi-draw

    private volatile boolean dirty = true;

    /** Marks the programs stale; {@link #rebuild()} is safe to call any time. */
    public void requestRebuild() {
        this.dirty = true;
    }

    public boolean needsRebuild() {
        return this.dirty;
    }

    /** Compiles/links all programs from the mod's bundled GLSL. Render-thread only. */
    public void rebuild() {
        this.dirty = false;
        this.delete();
        this.reset = compileCompute(GLSL_DIR + "reset.comp");
        this.update = compileCompute(GLSL_DIR + "update.comp");
        this.emit = compileCompute(GLSL_DIR + "emit.comp");
        this.keygen = compileCompute(GLSL_DIR + "keygen.comp");
        this.radixHist = compileCompute(GLSL_DIR + "radix_hist.comp");
        this.radixScan = compileCompute(GLSL_DIR + "radix_scan.comp");
        this.radixScatter = compileCompute(GLSL_DIR + "radix_scatter.comp");
        this.capture = compileCompute(GLSL_DIR + "capture.comp");
        this.render = link(GLSL_DIR + "additive.vsh", GLSL_DIR + "additive.fsh");
        this.texturedRender = link(GLSL_DIR + "textured.vsh", GLSL_DIR + "textured.fsh");
        this.modelRender = link(GLSL_DIR + "model.vsh", GLSL_DIR + "model.fsh");
        if (!this.ready()) {
            CreateManaIndustry.LOGGER.error("[CMI particles] program rebuild FAILED: "
                    + "reset={} update={} emit={} keygen={} hist={} scan={} scatter={} capture={} "
                    + "render={} textured={} model={}",
                    this.reset, this.update, this.emit, this.keygen,
                    this.radixHist, this.radixScan, this.radixScatter, this.capture,
                    this.render, this.texturedRender, this.modelRender);
        } else {
            CreateManaIndustry.LOGGER
                    .info("[CMI particles] programs compiled: reset={} update={} emit={} keygen={} "
                            + "hist={} scan={} scatter={} capture={} render={} textured={} model={}",
                            this.reset, this.update, this.emit, this.keygen,
                            this.radixHist, this.radixScan, this.radixScatter, this.capture,
                            this.render, this.texturedRender, this.modelRender);
        }
    }

    public boolean ready() {
        return this.reset != 0 && this.update != 0 && this.emit != 0 && this.render != 0
                && this.texturedRender != 0 && this.modelRender != 0
                && this.keygen != 0 && this.radixHist != 0 && this.radixScan != 0
                && this.radixScatter != 0 && this.capture != 0;
    }

    public int reset() {
        return this.reset;
    }

    public int update() {
        return this.update;
    }

    public int emit() {
        return this.emit;
    }

    public int keygen() {
        return this.keygen;
    }

    public int radixHist() {
        return this.radixHist;
    }

    public int radixScan() {
        return this.radixScan;
    }

    public int radixScatter() {
        return this.radixScatter;
    }

    public int capture() {
        return this.capture;
    }

    public int render() {
        return this.render;
    }

    public int texturedRender() {
        return this.texturedRender;
    }

    public int modelRender() {
        return this.modelRender;
    }

    // ------------------------------------------------------------------
    // GL helpers
    // ------------------------------------------------------------------

    /** Compiles a single compute shader into a standalone compute program. */
    private static int compileCompute(String path) {
        String src = load(path);
        if (src == null)
            return 0;
        int shader = compileStage(VERSION + PRELUDE + src, GL43.GL_COMPUTE_SHADER);
        if (shader == 0)
            return 0;
        int prog = GL20.glCreateProgram();
        GL20.glAttachShader(prog, shader);
        GL20.glLinkProgram(prog);
        GL20.glDeleteShader(shader);
        if (GL20.glGetProgrami(prog, GL20.GL_LINK_STATUS) == GL11.GL_FALSE) {
            CreateManaIndustry.LOGGER.error("[CMI particles] compute link failed ({}): {}", path,
                    GL20.glGetProgramInfoLog(prog));
            GL20.glDeleteProgram(prog);
            return 0;
        }
        return prog;
    }

    private static int link(String vshPath, String fshPath) {
        String vs = load(vshPath);
        String fs = load(fshPath);
        if (vs == null || fs == null)
            return 0;
        int vsh = compileStage(VERSION + PRELUDE + vs, GL20.GL_VERTEX_SHADER);
        int fsh = compileStage(VERSION + PRELUDE + fs, GL20.GL_FRAGMENT_SHADER);
        if (vsh == 0 || fsh == 0) {
            if (vsh != 0)
                GL20.glDeleteShader(vsh);
            if (fsh != 0)
                GL20.glDeleteShader(fsh);
            return 0;
        }
        int prog = GL20.glCreateProgram();
        GL20.glAttachShader(prog, vsh);
        GL20.glAttachShader(prog, fsh);
        GL20.glLinkProgram(prog);
        GL20.glDeleteShader(vsh);
        GL20.glDeleteShader(fsh);
        if (GL20.glGetProgrami(prog, GL20.GL_LINK_STATUS) == GL11.GL_FALSE) {
            CreateManaIndustry.LOGGER.error("[CMI particles] render link failed ({}): {}", vshPath,
                    GL20.glGetProgramInfoLog(prog));
            GL20.glDeleteProgram(prog);
            return 0;
        }
        return prog;
    }

    private static int compileStage(String source, int type) {
        int shader = GL20.glCreateShader(type);
        if (shader == 0) {
            CreateManaIndustry.LOGGER.error("[CMI particles] glCreateShader({}) returned 0", type);
            return 0;
        }
        GL20.glShaderSource(shader, source);
        GL20.glCompileShader(shader);
        if (GL20.glGetShaderi(shader, GL20.GL_COMPILE_STATUS) == GL11.GL_FALSE) {
            CreateManaIndustry.LOGGER.error("[CMI particles] shader compile failed (type {}): {}", type,
                    GL20.glGetShaderInfoLog(shader));
            GL20.glDeleteShader(shader);
            return 0;
        }
        return shader;
    }

    /** Loads a bundled shader file as text, or null on failure. */
    private static String load(String path) {
        ResourceManager rm = Minecraft.getInstance().getResourceManager();
        ResourceLocation id = CreateManaIndustry.modLoc(path);
        try (Reader r = rm.openAsReader(id)) {
            StringBuilder sb = new StringBuilder();
            char[] buf = new char[4096];
            int n;
            while ((n = r.read(buf)) != -1)
                sb.append(buf, 0, n);
            return sb.toString();
        } catch (IOException e) {
            CreateManaIndustry.LOGGER.error("[CMI particles] cannot read shader {}", id, e);
            return null;
        }
    }

    /** Deletes all program ids. Render-thread only. */
    public void delete() {
        for (int p : new int[] {
                this.reset, this.update, this.emit, this.keygen,
                this.radixHist, this.radixScan, this.radixScatter, this.capture,
                this.render, this.texturedRender, this.modelRender }) {
            if (p != 0)
                GL20.glDeleteProgram(p);
        }
        this.reset = this.update = this.emit = this.keygen = 0;
        this.radixHist = this.radixScan = this.radixScatter = this.capture = 0;
        this.render = this.texturedRender = this.modelRender = 0;
    }
}
