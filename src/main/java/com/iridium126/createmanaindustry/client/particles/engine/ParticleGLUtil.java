package com.iridium126.createmanaindustry.client.particles.engine;

import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL12;
import org.lwjgl.opengl.GL21;
import org.lwjgl.opengl.GL30;

/**
 * Tiny GL state guard for the particle engine's self-hosted client-memory
 * texture uploads.
 * <p>
 * Minecraft's texture/atlas/mipmap pipelines leave GL pixel-transfer state
 * dirty between calls. Two specific leftovers turn a client-memory
 * {@code glTexSubImage3D} into an NVIDIA driver crash:
 * <ul>
 *   <li>a bound {@code GL_PIXEL_UNPACK_BUFFER} — GL then reads the ByteBuffer
 *       pointer as an <em>offset into that PBO</em>;</li>
 *   <li>a nonzero {@code GL_UNPACK_ROW_LENGTH} / {@code GL_UNPACK_IMAGE_HEIGHT}
 *       — GL then reads rows/images wider/taller than our buffer.</li>
 * </ul>
 * Call {@link #prepareClientUpload()} immediately before every upload.
 */
final class ParticleGLUtil {

    private ParticleGLUtil() {
    }

    static void prepareClientUpload() {
        GL30.glBindBuffer(GL21.GL_PIXEL_UNPACK_BUFFER, 0);
        GL11.glPixelStorei(GL11.GL_UNPACK_ALIGNMENT, 1);
        GL11.glPixelStorei(GL11.GL_UNPACK_ROW_LENGTH, 0);
        GL11.glPixelStorei(GL11.GL_UNPACK_SKIP_ROWS, 0);
        GL11.glPixelStorei(GL11.GL_UNPACK_SKIP_PIXELS, 0);
        GL12.glPixelStorei(GL12.GL_UNPACK_IMAGE_HEIGHT, 0);
        GL12.glPixelStorei(GL12.GL_UNPACK_SKIP_IMAGES, 0);
    }
}
