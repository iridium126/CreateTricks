package com.iridium126.createmanaindustry.client.particles.engine;

import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;

import com.iridium126.createmanaindustry.CreateManaIndustry;

import com.mojang.blaze3d.platform.NativeImage;
import net.minecraft.client.Minecraft;
import net.minecraft.resources.ResourceLocation;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL12;
import org.lwjgl.opengl.GL13;
import org.lwjgl.opengl.GL30;

/**
 * Self-hosted sprite atlas for textured (ALPHA) particles, assembled on the
 * render thread from the mod's own asset PNGs. The vanilla {@code cherry_0..11}
 * frames are copied into {@code assets/createmanaindustry/textures/particle/}
 * and packed into one {@code GL_TEXTURE_2D} RGBA8 atlas (4 columns x 3 rows).
 * The atlas id is owned here; the render shaders sample it by frame index.
 */
public final class ParticleAtlas {

    public static final ParticleAtlas CHERRY = new ParticleAtlas("cherry", 4, 3, 12, false);
    /**
     * Single 32x32 frame: the vanilla allay entity texture (for MODEL
     * particles). Mipmapped like the vanilla entity atlas — safe here because
     * a single-frame atlas cannot blend neighbouring sprites at high mip
     * levels — removing distant shimmer.
     */
    public static final ParticleAtlas ALLAY = new ParticleAtlas("allay", 1, 1, 1, true);

    private final String baseName;
    private final int cols;
    private final int rows;
    private final int frames;
    /** Whether to generate mipmaps and minify through them on this atlas. */
    private final boolean mipmap;
    private int textureId = -1;

    private ParticleAtlas(String baseName, int cols, int rows, int frames, boolean mipmap) {
        this.baseName = baseName;
        this.cols = cols;
        this.rows = rows;
        this.frames = frames;
        this.mipmap = mipmap;
    }

    public int frames() {
        return frames;
    }

    public int cols() {
        return cols;
    }

    public int rows() {
        return rows;
    }

    /** Whether the atlas is loaded (texture id valid). */
    public boolean ready() {
        return textureId >= 0;
    }

    public int textureId() {
        return textureId;
    }

    /**
     * Decodes {@code frames} PNGs from the mod's assets and uploads them as a
     * {@code cols x rows} atlas. Called once on the render thread when the first
     * ALPHA emitter becomes live. No-op if already loaded.
     */
    public void ensureLoaded() {
        if (textureId >= 0)
            return;
        try {
            Minecraft mc = Minecraft.getInstance();
            var rm = mc.getResourceManager();
            if (rm == null)
                return;

            int[] fw = new int[frames];
            int[] fh = new int[frames];
            NativeImage[] imgs = new NativeImage[frames];
            int maxW = 0;
            int maxH = 0;
            for (int i = 0; i < frames; i++) {
                ResourceLocation id = CreateManaIndustry.modLoc("textures/particle/" + baseName + "_" + i + ".png");
                try (InputStream in = rm.open(id)) {
                    imgs[i] = NativeImage.read(in);
                } catch (Exception e) {
                    CreateManaIndustry.LOGGER.warn("[CMI particles] cannot read sprite {}", id, e);
                    for (int j = 0; j <= i; j++)
                        if (imgs[j] != null)
                            imgs[j].close();
                    return;
                }
                fw[i] = imgs[i].getWidth();
                fh[i] = imgs[i].getHeight();
                maxW = Math.max(maxW, fw[i]);
                maxH = Math.max(maxH, fh[i]);
            }

            int texW = cols * maxW;
            int texH = rows * maxH;
            NativeImage canvas = new NativeImage(texW, texH, true);
            for (int i = 0; i < frames; i++) {
                int col = i % cols;
                int row = i / cols;
                // this=img (read), arg=canvas (write) — see NativeImage.copyRect overload
                imgs[i].copyRect(canvas, 0, 0, col * maxW, row * maxH,
                        fw[i], fh[i], false, false);
            }

            int[] rgba = canvas.getPixelsRGBA();
            int texelCount = rgba.length;
            ByteBuffer pixels = ByteBuffer.allocateDirect(texelCount * 4)
                    .order(ByteOrder.nativeOrder());
            for (int v : rgba)
                pixels.putInt(v);
            pixels.flip();

            textureId = GL11.glGenTextures();
            GL11.glBindTexture(GL11.GL_TEXTURE_2D, textureId);
            GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MAG_FILTER, GL11.GL_NEAREST);
            GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_WRAP_S, GL12.GL_CLAMP_TO_EDGE);
            GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_WRAP_T, GL12.GL_CLAMP_TO_EDGE);
            ParticleGLUtil.prepareClientUpload(); // guard PBO / unpack-state leftovers
            GL11.glTexImage2D(GL11.GL_TEXTURE_2D, 0, GL11.GL_RGBA8, texW, texH, 0,
                    GL11.GL_RGBA, GL11.GL_UNSIGNED_BYTE, pixels);
            if (this.mipmap) {
                // vanilla-entity-atlas-style distance behaviour; a single-frame
                // atlas cannot bleed neighbouring sprites at high mip levels
                GL30.glGenerateMipmap(GL11.GL_TEXTURE_2D);
                GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MIN_FILTER,
                        GL30.GL_NEAREST_MIPMAP_LINEAR);
            } else {
                GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MIN_FILTER, GL11.GL_NEAREST);
            }

            canvas.close();
            for (NativeImage img : imgs)
                img.close();
            CreateManaIndustry.LOGGER.info("[CMI particles] sprite atlas {} ready: {}x{}",
                    baseName, texW, texH);
        } catch (RuntimeException | LinkageError e) {
            CreateManaIndustry.LOGGER.error("[CMI particles] failed to build sprite atlas " + baseName, e);
        }
    }

    /** Binds the atlas on the given texture unit (shader samples it there). */
    public void bind(int unit) {
        if (textureId < 0)
            return;
        GL13.glActiveTexture(GL13.GL_TEXTURE0 + unit);
        GL11.glBindTexture(GL11.GL_TEXTURE_2D, textureId);
    }

    public void free() {
        if (textureId >= 0) {
            GL30.glDeleteTextures(textureId);
            textureId = -1;
        }
    }
}
