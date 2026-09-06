package com.iridium126.createmanaindustry.client.particles.engine;

import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL12;
import org.lwjgl.opengl.GL15;
import org.lwjgl.opengl.GL21;
import org.lwjgl.opengl.GL30;
import org.lwjgl.opengl.GL33;

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
public final class ParticleGLUtil {

    private ParticleGLUtil() {
    }

    private static final int[] QUERY_SCRATCH = new int[1];

    /**
     * Id of the query object currently active on the {@code GL_TIME_ELAPSED}
     * target (0 = none). The query target is process-global — vanilla's
     * Alt+F3 profiler and any other timer-query user can own it at our
     * bracket points — so timer brackets must check this before
     * {@code glBeginQuery} and only {@code glEndQuery} when the active id is
     * their own: a begin against a busy target fails with GL_INVALID_OPERATION
     * ("Cannot begin query on an active query object"), and an end without our
     * bracket running would either end the FOREIGN query (silently ruining its
     * sample) or log GL_INVALID_OPERATION again ("does not have an active
     * query").
     */
    public static int activeTimeElapsedQuery() {
        GL15.glGetQueryiv(GL33.GL_TIME_ELAPSED, GL15.GL_CURRENT_QUERY, QUERY_SCRATCH);
        return QUERY_SCRATCH[0];
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
