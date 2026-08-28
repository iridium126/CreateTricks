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
 * Self-hosted sprite atlas for textured (ALPHA/OPAQUE) particles, assembled on
 * the render thread from the mod's own asset PNGs. The SPRITE atlas packs the
 * vanilla {@code cherry_0..11} frames (copied into mod assets) for the ALPHA
 * path plus the combat frames — vanilla {@code critical_hit}, {@code
 * enchanted_hit}, {@code damage} and {@code generic_0..7} (poof order 7→0) as
 * {@code combat_*} — for the OPAQUE combat emitters ({@code CombatSpecs});
 * 23 frames in an 8×3 grid. The atlas id is owned here; the render shaders
 * sample it by frame index.
 */
public final class ParticleAtlas {

    private static final String[] SPRITE_FILES = {
            "cherry_0", "cherry_1", "cherry_2", "cherry_3", "cherry_4", "cherry_5",
            "cherry_6", "cherry_7", "cherry_8", "cherry_9", "cherry_10", "cherry_11",
            // combat frames: frameBase 12..22 (see CombatSpecs); poof keeps the
            // vanilla poof.json order generic_7 → generic_0
            "combat_crit", "combat_magic", "combat_heart",
            "combat_poof_0", "combat_poof_1", "combat_poof_2", "combat_poof_3",
            "combat_poof_4", "combat_poof_5", "combat_poof_6", "combat_poof_7"
    };

    /** Shared ALPHA-sprite + OPAQUE-combat atlas (see class doc). */
    public static final ParticleAtlas SPRITE = new ParticleAtlas(SPRITE_FILES, 8, 3, false);
    /**
     * Single 32x32 frame: the vanilla allay entity texture (for MODEL
     * particles). Mipmapped like the vanilla entity atlas — safe here because
     * a single-frame atlas cannot blend neighbouring sprites at high mip
     * levels — removing distant shimmer.
     */
    public static final ParticleAtlas ALLAY = new ParticleAtlas(new String[] {"allay_0"}, 1, 1, true);

    private final String name;
    private final String[] files;
    private final int cols;
    private final int rows;
    private final int frames;
    /** Whether to generate mipmaps and minify through them on this atlas. */
    private final boolean mipmap;
    private int textureId = -1;

    private ParticleAtlas(String[] files, int cols, int rows, boolean mipmap) {
        this.files = files.clone();
        this.name = files[0].split("_")[0];
        this.cols = cols;
        this.rows = rows;
        this.frames = files.length;
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
                ResourceLocation id = CreateManaIndustry.modLoc("textures/particle/" + files[i] + ".png");
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
                // cell-sized frames copy 1:1; smaller frames (the 8x8 combat
                // sprites in the 16x16 cherry cells) are nearest-upscaled to
                // FILL the cell first — the render shaders map a full cell per
                // frame, so a partially-filled cell would draw the sprite at
                // the wrong world size
                if (fw[i] == maxW && fh[i] == maxH) {
                    // this=img (read), arg=canvas (write) — see NativeImage.copyRect overload
                    imgs[i].copyRect(canvas, 0, 0, col * maxW, row * maxH,
                            fw[i], fh[i], false, false);
                } else {
                    for (int y = 0; y < maxH; y++) {
                        int sy = Math.min(fh[i] - 1, y * fh[i] / maxH);
                        for (int x = 0; x < maxW; x++) {
                            int sx = Math.min(fw[i] - 1, x * fw[i] / maxW);
                            canvas.setPixelRGBA(col * maxW + x, row * maxH + y,
                                    imgs[i].getPixelRGBA(sx, sy));
                        }
                    }
                }
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
                    name, texW, texH);
        } catch (RuntimeException | LinkageError e) {
            CreateManaIndustry.LOGGER.error("[CMI particles] failed to build sprite atlas " + name, e);
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
