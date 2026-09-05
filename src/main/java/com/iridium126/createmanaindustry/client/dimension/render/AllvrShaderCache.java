package com.iridium126.createmanaindustry.client.dimension.render;

import java.io.IOException;
import java.io.Reader;

import org.joml.Matrix4fc;
import org.joml.Vector3fc;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL20;
import org.lwjgl.opengl.GL43;

import com.iridium126.createmanaindustry.CreateManaIndustry;

import net.minecraft.client.Minecraft;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.packs.resources.ResourceManager;

/**
 * Self-hosted GLSL for the ALLVR terrain pass (doc §6.5, mirrors
 * {@code ParticlePrograms}): raw LWJGL compile from
 * {@code assets/createmanaindustry/shaders/allvr/*}, PRELUDE of
 * {@code #define}s generated from the {@link AllvrBuffers} binding constants,
 * lazy rebuild on F3+T via the reload listener.
 * <p>
 * The per-command draw-parameter builtins ({@code gl_BaseVertex /
 * gl_BaseInstance}) need GL 4.6 core or the ARB extension on 4.5 — the
 * version line is chosen by {@code AllvrRenderer}'s capability probe.
 */
public final class AllvrShaderCache {

    public static final int BIND_QUADS = AllvrBuffers.BIND_QUADS;
    public static final int BIND_CUBEINFO = AllvrBuffers.BIND_CUBEINFO;
    public static final int STATE_TBO_UNIT = AllvrBuffers.STATE_TBO_UNIT;
    public static final int BIND_NODES = AllvrBuffers.BIND_NODES;
    public static final int BIND_QUEUE = AllvrBuffers.BIND_QUEUE;
    public static final int BIND_CMD_COUNT = AllvrBuffers.BIND_CMD_COUNT;
    public static final int BIND_DISPATCH = AllvrBuffers.BIND_DISPATCH;
    public static final int BIND_COMMANDS = AllvrBuffers.BIND_COMMANDS;
    // compile-time constants — inlined by javac, so this never initializes
    // AllvrRenderStateMap (no class-init cycle from the PRELUDE builder)
    private static final int TEXELS_PER_ENTRY = AllvrRenderStateMap.TEXELS_PER_ENTRY;
    private static final int TEXELS_PER_FACE = AllvrRenderStateMap.TEXELS_PER_FACE;

    private static final String GLSL_DIR = "shaders/allvr/";

    private static volatile String versionLine = "#version 460 core\n";

    private static final String PRELUDE = buildPrelude();

    private static String buildPrelude() {
        StringBuilder sb = new StringBuilder(512);
        sb.append("// ==== ALLVR terrain constants (GENERATED from AllvrBuffers / AllvrRenderStateMap) ====\n");
        sb.append("#define BIND_QUADS ").append(BIND_QUADS).append('\n');
        sb.append("#define BIND_CUBEINFO ").append(BIND_CUBEINFO).append('\n');
        sb.append("#define STATE_TBO_UNIT ").append(STATE_TBO_UNIT).append('\n');
        sb.append("#define STATE_TEXELS ").append(TEXELS_PER_ENTRY).append('\n');
        sb.append("#define STATE_TEXELS_PER_FACE ").append(TEXELS_PER_FACE).append('\n');
        sb.append("#define BIND_NODES ").append(AllvrBuffers.BIND_NODES).append('\n');
        sb.append("#define BIND_QUEUE ").append(AllvrBuffers.BIND_QUEUE).append('\n');
        sb.append("#define BIND_CMD_COUNT ").append(AllvrBuffers.BIND_CMD_COUNT).append('\n');
        sb.append("#define BIND_DISPATCH ").append(AllvrBuffers.BIND_DISPATCH).append('\n');
        sb.append("#define BIND_COMMANDS ").append(AllvrBuffers.BIND_COMMANDS).append('\n');
        sb.append("#define MAX_QUADS_PER_COMMAND ").append(AllvrBuffers.MAX_QUADS_PER_COMMAND).append('\n');
        sb.append("#define MAX_COMMANDS ").append(AllvrBuffers.MAX_COMMANDS).append('\n');
        sb.append("#define QUEUE_CAPACITY ").append(AllvrBuffers.QUEUE_CAPACITY).append('\n');
        sb.append("#define NODE_FLAG_HAS_MESH ").append(AllvrNodeStore.FLAG_HAS_MESH).append("u\n");
        sb.append("#define NODE_FLAG_DEAD ").append(AllvrNodeStore.FLAG_DEAD).append("u\n");
        return sb.toString();
    }

    /** Matches {@code #pragma cmi_include chunks/name.glsl} lines (mirrors ParticlePrograms). */
    private static final java.util.regex.Pattern INCLUDE_PATTERN =
        java.util.regex.Pattern.compile("^\\s*#pragma\\s+cmi_include\\s+(\\S+)\\s*$", java.util.regex.Pattern.MULTILINE);

    private int terrain;
    private int cullReset;
    private int traversal;
    private int cullFinalize;
    private int cmdgen;
    private int cullClamp;
    private int hizFirst;
    private int hizDownsample;
    private int revalidate;
    private volatile boolean dirty = true;

    /** Selects the GLSL version before the first compile (capability probe). */
    public static void setUseExtensionFallback(boolean useExtension) {
        versionLine = useExtension
            ? "#version 450 core\n#extension GL_ARB_shader_draw_parameters : require\n"
            : "#version 460 core\n";
    }

    public void requestRebuild() {
        this.dirty = true;
    }

    public boolean needsRebuild() {
        return this.dirty;
    }

    /** Compiles the terrain + GPU-cull programs. Render-thread only. */
    public void rebuild() {
        this.dirty = false;
        if (this.terrain != 0) {
            GL20.glDeleteProgram(this.terrain);
            this.terrain = 0;
        }
        for (int p : new int[] {this.cullReset, this.traversal, this.cullFinalize, this.cmdgen,
                this.cullClamp, this.hizFirst, this.hizDownsample, this.revalidate}) {
            if (p != 0) {
                GL20.glDeleteProgram(p);
            }
        }
        this.cullReset = this.traversal = this.cullFinalize = this.cmdgen = 0;
        this.cullClamp = 0;
        this.hizFirst = this.hizDownsample = this.revalidate = 0;
        this.terrain = link(GLSL_DIR + "terrain.vsh", GLSL_DIR + "terrain.fsh");
        this.cullReset = compileCompute(GLSL_DIR + "gpu_cull_reset.comp");
        this.traversal = compileCompute(GLSL_DIR + "gpu_cull_traversal.comp");
        this.cullFinalize = compileCompute(GLSL_DIR + "gpu_cull_finalize.comp");
        this.cmdgen = compileCompute(GLSL_DIR + "gpu_cull_cmdgen.comp");
        this.cullClamp = compileCompute(GLSL_DIR + "gpu_cull_clamp.comp");
        this.hizFirst = compileCompute(GLSL_DIR + "gpu_hiz_first.comp");
        this.hizDownsample = compileCompute(GLSL_DIR + "gpu_hiz_downsample.comp");
        this.revalidate = compileCompute(GLSL_DIR + "gpu_cull_revalidate.comp");
        if (this.terrain == 0) {
            this.dirty = true;
            CreateManaIndustry.LOGGER.error("[Allvr] terrain program compile FAILED (will retry)");
        } else {
            CreateManaIndustry.LOGGER.info("[Allvr] terrain program compiled: {}", this.terrain);
        }
        if (this.gpuReady()) {
            CreateManaIndustry.LOGGER.info("[Allvr] GPU-cull programs compiled (reset/traversal/finalize/cmdgen/clamp)");
        }
        if (this.hizReady()) {
            CreateManaIndustry.LOGGER.info("[Allvr] HiZ programs compiled (first/downsample/revalidate)");
        }
    }

    public int terrain() {
        return this.terrain;
    }

    public int cullReset() {
        return this.cullReset;
    }

    public int traversal() {
        return this.traversal;
    }

    public int cullFinalize() {
        return this.cullFinalize;
    }

    public int cmdgen() {
        return this.cmdgen;
    }

    public int cullClamp() {
        return this.cullClamp;
    }

    public int hizFirst() {
        return this.hizFirst;
    }

    public int hizDownsample() {
        return this.hizDownsample;
    }

    public int revalidate() {
        return this.revalidate;
    }

    public boolean ready() {
        return this.terrain != 0;
    }

    /** All five GPU-cull programs linked (GPU-cull path completeness). */
    public boolean gpuReady() {
        return this.cullReset != 0 && this.traversal != 0 && this.cullFinalize != 0 && this.cmdgen != 0
            && this.cullClamp != 0;
    }

    /** All three HiZ programs linked; false degrades 4b to frustum-only (4a). */
    public boolean hizReady() {
        return this.hizFirst != 0 && this.hizDownsample != 0 && this.revalidate != 0;
    }

    // ------------------------------------------------------------------
    // uniform helpers (program must be in use)
    // ------------------------------------------------------------------

    public static void uniformMat4(int prog, String name, Matrix4fc m) {
        GL20.glUniformMatrix4fv(location(prog, name), false, m.get(new float[16]));
    }

    public static void uniformIVec3(int prog, String name, int x, int y, int z) {
        GL20.glUniform3i(location(prog, name), x, y, z);
    }

    public static void uniformVec3(int prog, String name, Vector3fc v) {
        GL20.glUniform3f(location(prog, name), v.x(), v.y(), v.z());
    }

    public static void uniformVec3(int prog, String name, float x, float y, float z) {
        GL20.glUniform3f(location(prog, name), x, y, z);
    }

    public static void uniformFloat(int prog, String name, float v) {
        GL20.glUniform1f(location(prog, name), v);
    }

    public static void uniformInt(int prog, String name, int v) {
        GL20.glUniform1i(location(prog, name), v);
    }

    private static int location(int prog, String name) {
        return GL20.glGetUniformLocation(prog, name);
    }

    // ------------------------------------------------------------------
    // compile skeleton (mirrors ParticlePrograms)
    // ------------------------------------------------------------------

    /** Compiles a single compute shader into a standalone compute program. */
    private static int compileCompute(String path) {
        String src = load(path);
        if (src == null) {
            return 0;
        }
        int shader = compileStage(versionLine + PRELUDE + src, GL43.GL_COMPUTE_SHADER);
        if (shader == 0) {
            return 0;
        }
        int prog = GL20.glCreateProgram();
        GL20.glAttachShader(prog, shader);
        GL20.glLinkProgram(prog);
        GL20.glDeleteShader(shader);
        if (GL20.glGetProgrami(prog, GL20.GL_LINK_STATUS) == GL11.GL_FALSE) {
            CreateManaIndustry.LOGGER.error("[Allvr] compute link failed ({}): {}", path,
                GL20.glGetProgramInfoLog(prog));
            GL20.glDeleteProgram(prog);
            return 0;
        }
        return prog;
    }

    private static int link(String vshPath, String fshPath) {
        String vs = load(vshPath);
        String fs = load(fshPath);
        if (vs == null || fs == null) {
            return 0;
        }
        int vsh = compileStage(versionLine + PRELUDE + vs, GL20.GL_VERTEX_SHADER);
        int fsh = compileStage(versionLine + PRELUDE + fs, GL20.GL_FRAGMENT_SHADER);
        if (vsh == 0 || fsh == 0) {
            if (vsh != 0) {
                GL20.glDeleteShader(vsh);
            }
            if (fsh != 0) {
                GL20.glDeleteShader(fsh);
            }
            return 0;
        }
        int prog = GL20.glCreateProgram();
        GL20.glAttachShader(prog, vsh);
        GL20.glAttachShader(prog, fsh);
        GL20.glLinkProgram(prog);
        GL20.glDeleteShader(vsh);
        GL20.glDeleteShader(fsh);
        if (GL20.glGetProgrami(prog, GL20.GL_LINK_STATUS) == GL11.GL_FALSE) {
            CreateManaIndustry.LOGGER.error("[Allvr] terrain link failed: {}", GL20.glGetProgramInfoLog(prog));
            GL20.glDeleteProgram(prog);
            return 0;
        }
        return prog;
    }

    private static int compileStage(String source, int type) {
        int shader = GL20.glCreateShader(type);
        if (shader == 0) {
            return 0;
        }
        GL20.glShaderSource(shader, source);
        GL20.glCompileShader(shader);
        if (GL20.glGetShaderi(shader, GL20.GL_COMPILE_STATUS) == GL11.GL_FALSE) {
            CreateManaIndustry.LOGGER.error("[Allvr] shader compile failed (type {}): {}", type,
                GL20.glGetShaderInfoLog(shader));
            GL20.glDeleteShader(shader);
            return 0;
        }
        return shader;
    }

    private static String load(String path) {
        return loadResolved(path, 0);
    }

    /**
     * Loads a bundled shader file as text, or null on failure. Lines of the
     * form {@code #pragma cmi_include chunks/name.glsl} (path relative to
     * {@code shaders/allvr/}) are replaced with the referenced file's content,
     * recursively up to a small depth bound — the shared-source mechanism that
     * keeps the node decode + HiZ test single-sourced across the traversal and
     * revalidate kernels (mirrors ParticlePrograms).
     */
    private static String loadResolved(String path, int depth) {
        ResourceManager rm = Minecraft.getInstance().getResourceManager();
        ResourceLocation id = CreateManaIndustry.modLoc(path);
        String raw;
        try (Reader r = rm.openAsReader(id)) {
            StringBuilder sb = new StringBuilder(2048);
            char[] buf = new char[4096];
            int n;
            while ((n = r.read(buf)) != -1) {
                sb.append(buf, 0, n);
            }
            raw = sb.toString();
        } catch (IOException e) {
            CreateManaIndustry.LOGGER.error("[Allvr] cannot read shader {}", id, e);
            return null;
        }
        if (depth >= 4 || !INCLUDE_PATTERN.matcher(raw).find()) {
            return raw;
        }
        java.util.regex.Matcher m = INCLUDE_PATTERN.matcher(raw);
        StringBuffer out = new StringBuffer(raw.length());
        while (m.find()) {
            String included = loadResolved(GLSL_DIR + m.group(1), depth + 1);
            m.appendReplacement(out, java.util.regex.Matcher.quoteReplacement(included != null ? included : ""));
        }
        m.appendTail(out);
        return out.toString();
    }

    AllvrShaderCache() {
    }
}
