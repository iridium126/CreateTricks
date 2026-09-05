package com.iridium126.createmanaindustry.client.dimension.iris;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.OptionalInt;
import java.util.Set;
import java.util.function.IntConsumer;
import java.util.function.IntSupplier;
import java.util.function.LongConsumer;
import java.util.function.Supplier;
import java.util.stream.Collectors;

import com.google.common.collect.ImmutableSet;
import com.iridium126.createmanaindustry.CreateManaIndustry;
import it.unimi.dsi.fastutil.ints.Int2ObjectLinkedOpenHashMap;
import it.unimi.dsi.fastutil.ints.Int2ObjectMap;
import it.unimi.dsi.fastutil.longs.Long2ObjectFunction;
import kroppeb.stareval.function.FunctionReturn;
import kroppeb.stareval.function.Type;
import net.irisshaders.iris.gl.buffer.ShaderStorageBufferHolder;
import net.irisshaders.iris.gl.image.ImageHolder;
import net.irisshaders.iris.gl.sampler.GlSampler;
import net.irisshaders.iris.gl.sampler.SamplerHolder;
import net.irisshaders.iris.gl.state.FogMode;
import net.irisshaders.iris.gl.state.ValueUpdateNotifier;
import net.irisshaders.iris.gl.texture.InternalTextureFormat;
import net.irisshaders.iris.gl.texture.TextureType;
import net.irisshaders.iris.gl.uniform.BooleanUniform;
import net.irisshaders.iris.gl.uniform.DynamicLocationalUniformHolder;
import net.irisshaders.iris.gl.uniform.FloatSupplier;
import net.irisshaders.iris.gl.uniform.LocationalUniformHolder;
import net.irisshaders.iris.gl.uniform.Uniform;
import net.irisshaders.iris.gl.uniform.UniformHolder;
import net.irisshaders.iris.gl.uniform.UniformType;
import net.irisshaders.iris.gl.uniform.UniformUpdateFrequency;
import net.irisshaders.iris.pipeline.IrisRenderingPipeline;
import net.irisshaders.iris.targets.RenderTarget;
import net.irisshaders.iris.targets.RenderTargets;
import net.irisshaders.iris.uniforms.CommonUniforms;
import net.irisshaders.iris.uniforms.custom.CustomUniforms;
import net.irisshaders.iris.uniforms.custom.cached.BooleanCachedUniform;
import net.irisshaders.iris.uniforms.custom.cached.CachedUniform;
import net.irisshaders.iris.uniforms.custom.cached.Float2VectorCachedUniform;
import net.irisshaders.iris.uniforms.custom.cached.Float3VectorCachedUniform;
import net.irisshaders.iris.uniforms.custom.cached.Float4MatrixCachedUniform;
import net.irisshaders.iris.uniforms.custom.cached.Float4VectorCachedUniform;
import net.irisshaders.iris.uniforms.custom.cached.FloatCachedUniform;
import net.irisshaders.iris.uniforms.custom.cached.Int2VectorCachedUniform;
import net.irisshaders.iris.uniforms.custom.cached.Int3VectorCachedUniform;
import net.irisshaders.iris.uniforms.custom.cached.IntCachedUniform;
import org.joml.Matrix4f;
import org.joml.Vector2f;
import org.joml.Vector2i;
import org.joml.Vector3f;
import org.joml.Vector3i;
import org.joml.Vector4f;
import org.lwjgl.system.MemoryUtil;

import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.texture.DynamicTexture;

import static org.lwjgl.opengl.ARBUniformBufferObject.glBindBufferBase;
import static org.lwjgl.opengl.GL33C.glBindSampler;
import static org.lwjgl.opengl.GL43C.GL_SHADER_STORAGE_BUFFER;
import static org.lwjgl.opengl.ARBDirectStateAccess.glBindTextureUnit;

import com.iridium126.createmanaindustry.mixin.allvriris.accessor.AllvrCustomUniformsAccessor;
import com.iridium126.createmanaindustry.mixin.allvriris.accessor.AllvrIrisRenderingPipelineAccessor;
import com.iridium126.createmanaindustry.mixin.allvriris.accessor.AllvrLightTextureAccessor;

/**
 * ALLVR's resolution of the pack's voxy adaptation against a live iris pipeline
 * — the draw-target textures, the shared uniform UBO layout (pack names → std140
 * offsets, backed by iris's own CommonUniforms + CustomUniforms suppliers), the
 * sampler bindings, the SSBO bindings and the blend setup (doc §13 iris slice).
 * Port of voxy's IrisVoxyRenderPipelineData, decoupled from its render pipeline:
 * the terrain FBO and its depth textures belong to the draw-mounting slice and
 * arrive via {@link #setDepthTextures}.
 * <p>
 * Uniform values are written into a persistently-mapped-style buffer via the
 * {@link StructLayout#updater()} LongConsumer (memory writes at the resolved
 * std140 offsets) — the draw slice uploads it per frame and binds it at the
 * layout's declared binding point.
 */
public final class AllvrIrisPipelineData {

    /** GL binding points — ALLVR-owned, outside vanilla/iris ranges (voxy uses 7/10/6). */
    public static final int UNIFORM_UBO_BINDING = 8;
    public static final int SSBO_BINDING_BASE = 12;
    public static final int SAMPLER_BINDING_BASE = 8;

    public final int[] opaqueDrawTargets;
    public final int[] translucentDrawTargets;
    /** Dimensions of the first opaque target — the terrain FBO's viewport
     *  (smaller than the main target when the pack runs TAAU). */
    public final int opaqueWidth;
    public final int opaqueHeight;
    private final AllvrVoxyPatch patch;
    private final String opaquePatch;
    private final String translucentPatch;
    private final StructLayout uniforms;
    private final Runnable blendingSetup;
    private final ImageSet imageSet;
    private final SSBOSet ssboSet;
    public final boolean renderToVanillaDepth;
    public final float[] resolutionScale;
    public final boolean useViewportDims;
    public final String taaOffset;

    /**
     * The depth textures of ALLVR's own terrain FBO (opaque/translucent), wired
     * by the draw-mounting slice; resolved to 0 (unbound) until then.
     */
    private IntSupplier opaqueDepthTexture = () -> 0;
    private IntSupplier translucentDepthTexture = () -> 0;

    private AllvrIrisPipelineData(AllvrVoxyPatch patch, int[] opaqueDrawTargets, int[] translucentDrawTargets,
                                  StructLayout uniformSet, Runnable blendingSetup, ImageSet imageSet, SSBOSet ssboSet,
                                  int opaqueWidth, int opaqueHeight) {
        this.patch = patch;
        this.opaqueDrawTargets = opaqueDrawTargets;
        this.translucentDrawTargets = translucentDrawTargets;
        this.opaqueWidth = opaqueWidth;
        this.opaqueHeight = opaqueHeight;
        this.opaquePatch = patch.getPatchOpaqueSource();
        this.translucentPatch = patch.getPatchTranslucentSource();
        this.uniforms = uniformSet;
        this.blendingSetup = blendingSetup;
        this.imageSet = imageSet;
        this.ssboSet = ssboSet;
        this.renderToVanillaDepth = !patch.excludeFromVanillaDepth();
        this.taaOffset = patch.getTAAShift();
        this.resolutionScale = patch.getRenderScale();
        this.useViewportDims = patch.useViewportDims();
    }

    public AllvrVoxyPatch getPatch() {
        return this.patch;
    }

    public String getPatchOpaqueSource() {
        return this.opaquePatch;
    }

    public String getPatchTranslucentSource() {
        return this.translucentPatch;
    }

    public StructLayout getUniforms() {
        return this.uniforms;
    }

    public Runnable getBlender() {
        return this.blendingSetup;
    }

    public ImageSet getImageSet() {
        return this.imageSet;
    }

    public SSBOSet getSsboSet() {
        return this.ssboSet;
    }

    public boolean hasTAA() {
        return this.taaOffset != null;
    }

    public String getTAAShift() {
        return this.taaOffset;
    }

    public void setDepthTextures(IntSupplier opaque, IntSupplier translucent) {
        this.opaqueDepthTexture = opaque;
        this.translucentDepthTexture = translucent;
    }

    public IntSupplier getOpaqueDepthTexture() {
        return this.opaqueDepthTexture;
    }

    public IntSupplier getTranslucentDepthTexture() {
        return this.translucentDepthTexture;
    }

    // ------------------------------------------------------------------
    // construction
    // ------------------------------------------------------------------

    public static AllvrIrisPipelineData buildPipeline(IrisRenderingPipeline ipipe, AllvrVoxyPatch patch,
                                                      CustomUniforms cu, ShaderStorageBufferHolder ssboHolder) {
        var uniforms = createUniformLayoutStructAndUpdater(createUniformSet(cu, patch));
        var imageSet = createImageSet(ipipe, patch);
        var ssboSet = createSSBOLayouts(patch.getSsbos(), ssboHolder);

        var flipped = ipipe.getFlippedAfterPrepare();
        RenderTargets rt = ((AllvrIrisRenderingPipelineAccessor) ipipe).getRenderTargets();
        int[] opaqueWidthHeight = {0, 0};
        var opaqueDrawTargets = getDrawBuffers(patch.getOpaqueTargets(), flipped, rt, opaqueWidthHeight);
        var translucentDrawTargets = getDrawBuffers(patch.getTranslucentTargets(), flipped, rt, null);

        var out = new AllvrIrisPipelineData(patch, opaqueDrawTargets, translucentDrawTargets, uniforms,
            patch.createBlendSetup(), imageSet, ssboSet, opaqueWidthHeight[0], opaqueWidthHeight[1]);
        CreateManaIndustry.LOGGER.info(
            "[Allvr] voxy patch resolved: opaque buffers {} translucent {} uniforms {} samplers {} ssbos {} taa {} taaU {} size {}x{}",
            opaqueDrawTargets.length, translucentDrawTargets.length,
            uniforms != null ? uniforms.size() / 4 : 0,
            imageSet != null ? "bound" : "none",
            ssboSet != null ? "bound" : "none", out.hasTAA(), patch.taaUEnabled(),
            opaqueWidthHeight[0], opaqueWidthHeight[1]);
        return out;
    }

    private static int[] getDrawBuffers(int[] targets, ImmutableSet<Integer> stageWritesToAlt, RenderTargets rt,
                                        int[] firstDimsOut) {
        int[] targetTextures = new int[targets.length];
        for (int i = 0; i < targets.length; i++) {
            RenderTarget target = rt.getOrCreate(targets[i]);
            if (i == 0 && firstDimsOut != null) {
                firstDimsOut[0] = target.getWidth();
                firstDimsOut[1] = target.getHeight();
            }
            targetTextures[i] = stageWritesToAlt.contains(targets[i]) ? target.getAltTexture() : target.getMainTexture();
        }
        return targetTextures;
    }

    /**
     * Live read of iris's block → material-id map (the patch's {@code customId}
     * — Photon derives its material mask from {@code customId − 10000}). Read
     * at resolution time, not build time, so late population is still seen.
     */
    public it.unimi.dsi.fastutil.objects.Object2IntMap<net.minecraft.world.level.block.state.BlockState> getCustomIds() {
        try {
            return net.irisshaders.iris.shaderpack.materialmap.WorldRenderingSettings.INSTANCE.getBlockStateIds();
        } catch (Throwable t) {
            return null;
        }
    }

    // ------------------------------------------------------------------
    // uniform UBO layout (pack names → std140 struct, voxy's algorithm)
    // ------------------------------------------------------------------

    public record StructLayout(int size, String layout, LongConsumer updater) {}

    private static String convertToGlslType(UniformType type) {
        return switch (type) {
            case INT -> "int";
            case FLOAT -> "float";
            case MAT3 -> "mat3";
            case MAT4 -> "mat4";
            case VEC2 -> "vec2";
            case VEC2I -> "ivec2";
            case VEC3 -> "vec3";
            case VEC3I -> "ivec3";
            case VEC4 -> "vec4";
            case VEC4I -> "ivec4";
        };
    }

    private static int packed(int size, int align) {
        return size << 5 | align;
    }

    private static int getSizeAndAlignment(UniformType type) {
        return switch (type) {
            case INT, FLOAT -> packed(1, 1);
            case MAT3 -> packed(4 + 4 + 3, 4); // rows are vec3-padded to vec4
            case MAT4 -> packed(4 * 4, 4);
            case VEC2, VEC2I -> packed(2, 2);
            case VEC3, VEC3I -> packed(3, 4);
            case VEC4, VEC4I -> packed(4, 4);
        };
    }

    private static int getUniformOrdering(UniformType type) {
        return switch (type) {
            case MAT4, VEC4, VEC4I -> 0;
            case VEC2, VEC2I -> 1;
            case VEC3, VEC3I, MAT3 -> 2;
            case INT, FLOAT -> 3;
        };
    }

    @SuppressWarnings("unchecked")
    private static StructLayout createUniformLayoutStructAndUpdater(List<UniformWritingHolder> uniforms) {
        if (uniforms.isEmpty()) {
            return null;
        }

        List<UniformWritingHolder>[] ordering = new List[] {new ArrayList<>(), new ArrayList<>(),
            new ArrayList<>(), new ArrayList<>()};
        for (var uniform : uniforms) {
            ordering[getUniformOrdering(uniform.type())].add(uniform);
        }

        int pos = 0;
        Int2ObjectLinkedOpenHashMap<UniformWritingHolder> layout = new Int2ObjectLinkedOpenHashMap<>();
        for (var uniform : ordering[0]) { // align 4
            layout.put(pos, uniform);
            pos += getSizeAndAlignment(uniform.type()) >> 5;
        }
        if (!ordering[1].isEmpty() && (ordering[1].size() & 1) == 0) {
            for (var uniform : ordering[1]) {
                layout.put(pos, uniform);
                pos += getSizeAndAlignment(uniform.type()) >> 5;
            }
            ordering[1].clear();
        }
        for (var uniform : ordering[2]) { // size odd, alignment 4
            layout.put(pos, uniform);
            pos += getSizeAndAlignment(uniform.type()) >> 5;
            if (!ordering[3].isEmpty()) {
                uniform = ordering[3].removeFirst();
                layout.put(pos, uniform);
                pos += getSizeAndAlignment(uniform.type()) >> 5;
            } else {
                pos += 1;
            }
        }
        for (var uniform : ordering[1]) {
            layout.put(pos, uniform);
            pos += getSizeAndAlignment(uniform.type()) >> 5;
        }
        for (var uniform : ordering[3]) {
            layout.put(pos, uniform);
            pos += getSizeAndAlignment(uniform.type()) >> 5;
        }

        if (layout.size() != uniforms.size()) {
            throw new IllegalStateException();
        }

        String structLayout;
        {
            StringBuilder struct = new StringBuilder("{\n");
            for (var pair : layout.int2ObjectEntrySet()) {
                struct.append('\t').append(convertToGlslType(pair.getValue().type())).append(' ')
                    .append(pair.getValue().name()).append(";\n");
            }
            struct.append('}');
            structLayout = struct.toString();
        }

        LongConsumer[] updaters = new LongConsumer[uniforms.size()];
        int i = 0;
        for (var pair : layout.int2ObjectEntrySet()) {
            updaters[i++] = pair.getValue().writingFactory().get(pair.getIntKey() * 4L);
        }
        LongConsumer updater = ptr -> {
            for (var u : updaters) {
                u.accept(ptr);
            }
        };
        return new StructLayout(pos * 4, structLayout, updater);
    }

    private record UniformWritingHolder(String name, UniformType type, Long2ObjectFunction<LongConsumer> writingFactory) {}

    private static LongConsumer createWriter(long offset, FunctionReturn ret, CachedUniform uniform) {
        if (uniform instanceof BooleanCachedUniform bcu) {
            return ptr -> {
                ptr += offset;
                bcu.writeTo(ret);
                MemoryUtil.memPutInt(ptr, ret.booleanReturn ? 1 : 0);
            };
        } else if (uniform instanceof FloatCachedUniform fcu) {
            return ptr -> {
                ptr += offset;
                fcu.writeTo(ret);
                MemoryUtil.memPutFloat(ptr, ret.floatReturn);
            };
        } else if (uniform instanceof IntCachedUniform icu) {
            return ptr -> {
                ptr += offset;
                icu.writeTo(ret);
                MemoryUtil.memPutInt(ptr, ret.intReturn);
            };
        } else if (uniform instanceof Float2VectorCachedUniform v2fcu) {
            return ptr -> {
                ptr += offset;
                v2fcu.writeTo(ret);
                ((Vector2f) ret.objectReturn).getToAddress(ptr);
            };
        } else if (uniform instanceof Float3VectorCachedUniform v3fcu) {
            return ptr -> {
                ptr += offset;
                v3fcu.writeTo(ret);
                ((Vector3f) ret.objectReturn).getToAddress(ptr);
            };
        } else if (uniform instanceof Float4VectorCachedUniform v4fcu) {
            return ptr -> {
                ptr += offset;
                v4fcu.writeTo(ret);
                ((Vector4f) ret.objectReturn).getToAddress(ptr);
            };
        } else if (uniform instanceof Int2VectorCachedUniform v2icu) {
            return ptr -> {
                ptr += offset;
                v2icu.writeTo(ret);
                ((Vector2i) ret.objectReturn).getToAddress(ptr);
            };
        } else if (uniform instanceof Int3VectorCachedUniform v3icu) {
            return ptr -> {
                ptr += offset;
                v3icu.writeTo(ret);
                ((Vector3i) ret.objectReturn).getToAddress(ptr);
            };
        } else if (uniform instanceof Float4MatrixCachedUniform f4mcu) {
            return ptr -> {
                ptr += offset;
                f4mcu.writeTo(ret);
                ((Matrix4f) ret.objectReturn).getToAddress(ptr);
            };
        } else {
            throw new IllegalStateException("Unknown uniform type " + uniform.getClass().getName());
        }
    }

    private static List<UniformWritingHolder> createUniformSet(CustomUniforms cu, AllvrVoxyPatch patch) {
        // A DynamicLocationalUniformHolder shim (voxy's same workaround): iris's
        // dynamic uniform set + the pack's CustomUniforms are matched against the
        // patch's requested names, writing into our std140 buffer instead of
        // glGetUniformLocation'd program uniforms
        List<UniformWritingHolder> uniforms = new ArrayList<>();
        Set<String> seenUniforms = new HashSet<>();
        DynamicLocationalUniformHolder uniformBuilder = new DynamicLocationalUniformHolder() {
            @Override
            public DynamicLocationalUniformHolder uniform1i(UniformUpdateFrequency updateFrequency, String name, IntSupplier value) {
                return this.uniform1i(name, value, null);
            }

            @Override
            public DynamicLocationalUniformHolder uniform1i(String name, IntSupplier value, ValueUpdateNotifier notifier) {
                this.injectDynamicUniformType(name, UniformType.INT, offset -> ptr ->
                    MemoryUtil.memPutInt(ptr + offset, value.getAsInt()));
                return this;
            }

            @Override
            public DynamicLocationalUniformHolder uniform1f(UniformUpdateFrequency updateFrequency, String name, FloatSupplier value) {
                return this.uniform1f(name, value, null);
            }

            @Override
            public DynamicLocationalUniformHolder uniform1f(String name, FloatSupplier value, ValueUpdateNotifier notifier) {
                this.injectDynamicUniformType(name, UniformType.FLOAT, offset -> ptr ->
                    MemoryUtil.memPutFloat(ptr + offset, value.getAsFloat()));
                return this;
            }

            @Override
            public DynamicLocationalUniformHolder uniform3f(UniformUpdateFrequency updateFrequency, String name, Supplier<Vector3f> value) {
                return this.uniform3f(name, value, null);
            }

            @Override
            public DynamicLocationalUniformHolder uniform3f(String name, Supplier<Vector3f> value, ValueUpdateNotifier notifier) {
                this.injectDynamicUniformType(name, UniformType.VEC3, offset -> ptr ->
                    value.get().getToAddress(ptr + offset));
                return this;
            }

            private void injectDynamicUniformType(String requestedName, UniformType type, Long2ObjectFunction<LongConsumer> supplier) {
                var names = patch.getUniformList();
                for (var candidate : names) {
                    if (candidate.equals(requestedName)) {
                        if (!seenUniforms.add(requestedName)) {
                            throw new IllegalArgumentException("Already added uniform: " + requestedName);
                        }
                        uniforms.add(new UniformWritingHolder(requestedName, type, supplier));
                        break;
                    }
                }
            }

            @Override
            public DynamicLocationalUniformHolder addDynamicUniform(Uniform uniform, ValueUpdateNotifier valueUpdateNotifier) {
                throw new IllegalStateException("Type not implemented for uniform: " + uniform);
            }

            @Override
            public LocationalUniformHolder addUniform(UniformUpdateFrequency uniformUpdateFrequency, Uniform uniform) {
                if (uniform instanceof BooleanUniform bu) {
                    int loc = bu.getLocation();
                    var ul = patch.getUniformList();
                    if (loc < ul.length) {
                        var uniformName = ul[loc];
                        if (!seenUniforms.add(uniformName)) {
                            throw new IllegalArgumentException("Already added uniform: " + uniformName);
                        }
                        uniforms.add(new UniformWritingHolder(uniformName, UniformType.INT, offset -> ptr ->
                            MemoryUtil.memPutInt(ptr + offset, 0)));
                    }
                }
                return this;
            }

            @Override
            public OptionalInt location(String uniformName, UniformType uniformType) {
                var names = patch.getUniformList();
                for (int i = 0; i < names.length; i++) {
                    if (names[i].equals(uniformName)) {
                        return OptionalInt.of(i);
                    }
                }
                return OptionalInt.empty();
            }

            @Override
            public UniformHolder externallyManagedUniform(String s, UniformType uniformType) {
                return null;
            }
        };
        CommonUniforms.addDynamicUniforms(uniformBuilder, FogMode.PER_FRAGMENT);
        cu.assignTo(uniformBuilder);
        cu.mapholderToPass(uniformBuilder, patch);

        FunctionReturn cachedReturn = new FunctionReturn();
        var locationMap = ((AllvrCustomUniformsAccessor) cu).getLocationMap().get(patch);
        if (locationMap != null) {
            locationMap.object2IntEntrySet().forEach(entry -> {
                if (!seenUniforms.add(entry.getKey().getName())) {
                    throw new IllegalArgumentException("Already added uniform: " + entry.getKey().getName());
                }
                uniforms.add(new UniformWritingHolder(entry.getKey().getName(),
                    Type.convert(entry.getKey().getType()),
                    offset -> createWriter(offset, cachedReturn, entry.getKey())));
            });
        }

        if (uniforms.size() != patch.getUniformList().length) {
            Set<String> missing = new HashSet<>(List.of(patch.getUniformList()));
            for (var uniform : uniforms) {
                missing.remove(uniform.name());
            }
            CreateManaIndustry.LOGGER.warn("[Allvr] voxy patch uniforms not found: [{}]",
                missing.stream().sorted(String::compareToIgnoreCase).collect(Collectors.joining(",")));
        }
        return uniforms;
    }

    // ------------------------------------------------------------------
    // samplers (pack colortexes/lightmap/shadowtex + our depth textures)
    // ------------------------------------------------------------------

    public record ImageSet(String layout, IntConsumer bindingFunction, int samplerCount) {}

    private record TextureWSampler(String name, IntSupplier texture, int sampler) {}

    private static ImageSet createImageSet(IrisRenderingPipeline ipipe, AllvrVoxyPatch patch) {
        var samplerDataSet = patch.getSamplerSet();
        if (samplerDataSet == null || samplerDataSet.isEmpty()) {
            return null;
        }
        Set<String> samplerNameSet = new LinkedHashSet<>(samplerDataSet.keySet());
        Set<TextureWSampler> samplerSet = new LinkedHashSet<>();

        Map<String, IntSupplier> externalTextures = new HashMap<>();
        externalTextures.put("lightmap", () -> getLightmapTextureId());

        SamplerHolder samplerBuilder = new SamplerHolder() {
            @Override
            public boolean hasSampler(String name) {
                return samplerNameSet.contains(name);
            }

            private boolean hasAny(String... names) {
                for (var name : names) {
                    if (samplerNameSet.contains(name)) {
                        return true;
                    }
                }
                return false;
            }

            private String name(String... names) {
                for (var name : names) {
                    if (samplerNameSet.contains(name)) {
                        return name;
                    }
                }
                return null;
            }

            @Override
            public boolean addDefaultSampler(TextureType type, IntSupplier texture, ValueUpdateNotifier notifier,
                                             GlSampler sampler, String... names) {
                CreateManaIndustry.LOGGER.warn("[Allvr] unsupported default sampler requested: {}", (Object) names);
                return false;
            }

            @Override
            public boolean addDynamicSampler(TextureType type, IntSupplier texture, GlSampler sampler, String... names) {
                if (!this.hasAny(names)) {
                    return false;
                }
                var resolvedName = this.name(names);
                samplerSet.add(new TextureWSampler(resolvedName, texture,
                    sampler != null ? sampler.getId() : -1));
                return true;
            }

            @Override
            public boolean addDynamicSampler(TextureType type, IntSupplier texture, ValueUpdateNotifier notifier,
                                             GlSampler sampler, String... names) {
                if (!this.hasAny(names)) {
                    return false;
                }
                var resolvedName = this.name(names);
                samplerSet.add(new TextureWSampler(resolvedName, texture,
                    sampler != null ? sampler.getId() : -1));
                return true;
            }

            @Override
            public void addExternalSampler(int texture, String... names) {
                if (!this.hasAny(names)) {
                    return;
                }
                var resolvedName = this.name(names);
                var ex = externalTextures.get(resolvedName);
                if (ex != null) {
                    samplerSet.add(new TextureWSampler(resolvedName, ex, -1));
                } else {
                    samplerSet.add(new TextureWSampler(resolvedName, () -> texture, -1));
                }
            }
        };

        ImageHolder imageBuilder = new ImageHolder() {
            @Override
            public boolean hasImage(String s) {
                return false;
            }

            @Override
            public void addTextureImage(IntSupplier intSupplier, InternalTextureFormat internalTextureFormat, String s) {
            }
        };

        ipipe.addGbufferOrShadowSamplers(samplerBuilder, imageBuilder, ipipe::getFlippedAfterPrepare,
            false, true, true, false);

        if (samplerSet.size() != samplerNameSet.size()) {
            Set<String> missing = new LinkedHashSet<>(samplerNameSet);
            for (var s : samplerSet) {
                missing.remove(s.name());
            }
            CreateManaIndustry.LOGGER.warn("[Allvr] voxy patch samplers not found: [{}]",
                String.join(", ", missing));
        }

        StringBuilder builder = new StringBuilder();
        TextureWSampler[] samplers = new TextureWSampler[samplerSet.size()];
        int i = 0;
        for (var entry : samplerSet) {
            samplers[i] = entry;
            builder.append("layout(binding = (SAMPLER_BINDING_BASE + ").append(i).append(")) uniform ")
                .append(samplerDataSet.get(entry.name())).append(' ').append(entry.name()).append(";\n");
            i++;
        }

        IntConsumer bindingFunction = base -> {
            for (int j = 0; j < samplers.length; j++) {
                int unit = j + base;
                var ts = samplers[j];
                glBindTextureUnit(unit, ts.texture().getAsInt());
                if (ts.sampler() != -1) {
                    glBindSampler(unit, ts.sampler());
                }
            }
        };
        return new ImageSet(builder.toString(), bindingFunction, samplers.length);
    }

    /**
     * The vanilla lightmap texture id — the vanilla field is private, so the
     * read goes through {@code AllvrLightTextureAccessor}. Render thread only.
     */
    public static int getLightmapTextureId() {
        return ((AllvrLightTextureAccessor) (Object) Minecraft.getInstance().gameRenderer.lightTexture())
            .allvr$getLightTexture().getId();
    }

    // ------------------------------------------------------------------
    // SSBOs (pack shader storage buffers, bound through iris's holder)
    // ------------------------------------------------------------------

    public record SSBOSet(String layout, IntConsumer bindingFunction, int bindingCount) {}

    private static SSBOSet createSSBOLayouts(Int2ObjectMap<String> ssbos, ShaderStorageBufferHolder ssboStore) {
        if (ssboStore == null || ssbos.isEmpty()) {
            return null;
        }
        String header = "";
        if (ssbos.containsKey(-1)) {
            header = ssbos.remove(-1);
        }
        StringBuilder builder = new StringBuilder(header).append('\n');
        record SSBOBinding(int irisIndex, int bindingOffset) {}
        var bindings = new ArrayList<SSBOBinding>(ssbos.size());
        int i = 0;
        for (var entry : ssbos.int2ObjectEntrySet()) {
            bindings.add(new SSBOBinding(entry.getIntKey(), i));
            builder.append("layout(binding = (SSBO_BINDING_BASE + ").append(i)
                .append(")) restrict buffer IrisBufferBinding").append(i).append(' ')
                .append(entry.getValue()).append(";\n");
            i++;
        }
        IntConsumer bindingFunction = base -> {
            for (var binding : bindings) {
                glBindBufferBase(GL_SHADER_STORAGE_BUFFER, base + binding.bindingOffset(),
                    ssboStore.getBufferIndex(binding.irisIndex()));
            }
        };
        return new SSBOSet(builder.toString(), bindingFunction, bindings.size());
    }
}
