package com.iridium126.createmanaindustry.client.dimension.iris;

/**
 * Shared state for the allay-dimension pipeline capture. Lives outside the
 * mixin package: mixin-registered packages are protected, so a non-mixin class
 * there cannot be loaded when the merged injector code references it. Both the
 * {@code Iris.createPipeline} handlers ({@code AllvrIrisCreatePipelineMixin})
 * and the pack-side consumer ({@code AllvrShaderPackMixin}) share this single
 * threadlocal through one ordinary class.
 */
public final class AllvrIrisPipelineCapture {

    /** iris-side id of the allay dimension — kept as a string so this class loads without iris. */
    private static final String ALLAY_DIM_ID = "createmanaindustry:allay_dimension";

    private static final ThreadLocal<String> CREATING_DIMENSION = new ThreadLocal<>();

    private AllvrIrisPipelineCapture() {
    }

    /** Called from {@code Iris.createPipeline} HEAD with the target dimension id. */
    public static void begin(String dimensionId) {
        CREATING_DIMENSION.set(dimensionId);
    }

    /** Called from {@code Iris.createPipeline} RETURN. */
    public static void end() {
        CREATING_DIMENSION.remove();
    }

    /** True while an iris pipeline is being built FOR the allay dimension. */
    public static boolean isBuildingAllayPipeline() {
        return ALLAY_DIM_ID.equals(CREATING_DIMENSION.get());
    }
}
