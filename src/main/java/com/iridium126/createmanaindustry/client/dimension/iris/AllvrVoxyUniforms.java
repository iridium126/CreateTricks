package com.iridium126.createmanaindustry.client.dimension.iris;

import java.util.function.Supplier;

import net.irisshaders.iris.gl.uniform.UniformHolder;
import net.irisshaders.iris.gl.uniform.UniformUpdateFrequency;
import org.joml.Matrix4f;
import org.joml.Matrix4fc;

/**
 * The vx* uniform surface packs expect when {@code VOXY} is defined (their
 * uniforms.glsl declares {@code vxRenderDistance}, the vx matrices and friends).
 * Values are supplied from the draw-mounting slice via {@link #update}; before
 * the first terrain draw they are sane defaults (identity matrices), which is
 * exactly the "no LOD content yet" state the packs already handle.
 * <p>
 * These are registered only while the voxy mod is absent (apply-time gate) and
 * only ever consumed inside the allay dimension (the VOXY define is per-
 * dimension in ALLVR's integration), so no vanilla-dimension neutralization is
 * needed here.
 */
public final class AllvrVoxyUniforms {

    private static volatile Matrix4f modelView = new Matrix4f();
    private static volatile Matrix4f projection = new Matrix4f();
    private static volatile int renderDistanceBlocks = 2048;

    private static final Matrix4f PREVIOUS_MODEL_VIEW = new Matrix4f();
    private static final Matrix4f PREVIOUS_PROJECTION = new Matrix4f();

    private AllvrVoxyUniforms() {}

    /** Per-frame state push from the draw slice (render thread). */
    public static void update(Matrix4f newModelView, Matrix4f newProjection, int newRenderDistanceBlocks) {
        synchronized (AllvrVoxyUniforms.class) {
            PREVIOUS_MODEL_VIEW.set(currentModelView());
            PREVIOUS_PROJECTION.set(currentProjection());
            modelView = new Matrix4f(newModelView);
            projection = new Matrix4f(newProjection);
            renderDistanceBlocks = newRenderDistanceBlocks;
        }
    }

    private static Matrix4fc currentModelView() {
        return modelView;
    }

    private static Matrix4fc currentProjection() {
        return projection;
    }

    private static Supplier<Matrix4fc> inverted(Supplier<Matrix4fc> parent) {
        return () -> new Matrix4f(parent.get()).invert();
    }

    public static void addUniforms(UniformHolder uniforms) {
        uniforms
            .uniform1i(UniformUpdateFrequency.PER_FRAME, "vxRenderDistance", () -> renderDistanceBlocks)
            .uniformMatrix(UniformUpdateFrequency.PER_FRAME, "vxViewProj",
                () -> new Matrix4f(currentModelView()).mul(currentProjection()))
            .uniformMatrix(UniformUpdateFrequency.PER_FRAME, "vxViewProjInv",
                inverted(() -> new Matrix4f(currentModelView()).mul(currentProjection())))
            .uniformMatrix(UniformUpdateFrequency.PER_FRAME, "vxViewProjPrev",
                () -> new Matrix4f(PREVIOUS_MODEL_VIEW).mul(PREVIOUS_PROJECTION))
            .uniformMatrix(UniformUpdateFrequency.PER_FRAME, "vxModelView", AllvrVoxyUniforms::currentModelView)
            .uniformMatrix(UniformUpdateFrequency.PER_FRAME, "vxModelViewInv", inverted(AllvrVoxyUniforms::currentModelView))
            .uniformMatrix(UniformUpdateFrequency.PER_FRAME, "vxModelViewPrev", () -> PREVIOUS_MODEL_VIEW)
            .uniformMatrix(UniformUpdateFrequency.PER_FRAME, "vxProj", AllvrVoxyUniforms::currentProjection)
            .uniformMatrix(UniformUpdateFrequency.PER_FRAME, "vxProjInv", inverted(AllvrVoxyUniforms::currentProjection))
            .uniformMatrix(UniformUpdateFrequency.PER_FRAME, "vxProjPrev", () -> PREVIOUS_PROJECTION);
    }
}
