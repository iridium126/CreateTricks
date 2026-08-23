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
 * Pipeline: reset -> update -> emit (fast additive path) or
 * reset -> update -> emit -> keygen -> radix{hist,scan,scatter} x1 (sorted
 * path: single-pass counting sort over an inverted 8-bit depth band, i.e.
 * back-to-front), then the additive render program and/or the textured
 * alpha render program.
 * <p>
 * A {@code #version} header is prepended (raw GL requires one; Veil used to
 * inject it). Programs are rebuilt by {@link #rebuild()} — called lazily on the
 * render thread whenever {@link #needsRebuild()} is true, which the resource
 * reload listener flips so F3+T recompiles shaders.
 */
public final class ParticlePrograms {

    private static final String GLSL_DIR = "shaders/particles/";
    private static final String VERSION = "#version 450 core\n";

    private int reset;
    private int update;
    private int emit;
    private int keygen;
    private int radixHist;
    private int radixScan;
    private int radixScatter;
    private int capture;
    private int render;        // additive billboards
    private int alphaRender;   // textured alpha billboards

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
        this.alphaRender = link(GLSL_DIR + "alpha.vsh", GLSL_DIR + "alpha.fsh");
        if (!this.ready()) {
            CreateManaIndustry.LOGGER.error("[CMI particles] program rebuild FAILED: "
                    + "reset={} update={} emit={} keygen={} hist={} scan={} scatter={} capture={} render={} alpha={}",
                    this.reset, this.update, this.emit, this.keygen,
                    this.radixHist, this.radixScan, this.radixScatter, this.capture,
                    this.render, this.alphaRender);
        } else {
            CreateManaIndustry.LOGGER
                    .info("[CMI particles] programs compiled: reset={} update={} emit={} keygen={} "
                            + "hist={} scan={} scatter={} capture={} render={} alpha={}",
                            this.reset, this.update, this.emit, this.keygen,
                            this.radixHist, this.radixScan, this.radixScatter, this.capture,
                            this.render, this.alphaRender);
        }
    }

    public boolean ready() {
        return this.reset != 0 && this.update != 0 && this.emit != 0 && this.render != 0
                && this.alphaRender != 0
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

    public int alphaRender() {
        return this.alphaRender;
    }

    // ------------------------------------------------------------------
    // GL helpers
    // ------------------------------------------------------------------

    /** Compiles a single compute shader into a standalone compute program. */
    private static int compileCompute(String path) {
        String src = load(path);
        if (src == null)
            return 0;
        int shader = compileStage(VERSION + src, GL43.GL_COMPUTE_SHADER);
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
        int vsh = compileStage(VERSION + vs, GL20.GL_VERTEX_SHADER);
        int fsh = compileStage(VERSION + fs, GL20.GL_FRAGMENT_SHADER);
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
                this.render, this.alphaRender }) {
            if (p != 0)
                GL20.glDeleteProgram(p);
        }
        this.reset = this.update = this.emit = this.keygen = 0;
        this.radixHist = this.radixScan = this.radixScatter = this.capture = 0;
        this.render = this.alphaRender = 0;
    }
}
