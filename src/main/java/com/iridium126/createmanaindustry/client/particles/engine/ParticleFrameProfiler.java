package com.iridium126.createmanaindustry.client.particles.engine;

/**
 * Rolling frame-cost average and a simple hysteretic emission-throttle
 * controller: when the EMA of the GPU engine's measured frame cost climbs
 * above the budget, the emission scale decays; when it drops well under
 * budget it ramps back up toward 1. The recorded cost is the GPU-side
 * elapsed time of a frame (GL_TIME_ELAPSED query ring, a few frames lagged)
 * — falling back to CPU submit time only before the first query completes.
 */
public final class ParticleFrameProfiler {

    private static final float COOL_DOWN = 0.85f;
    private static final float RAMP_UP = 1.05f;
    private static final float MIN_SCALE = 0.05f;

    // budget()/setBudget() cross threads (command vs render); emaMs() is read by
    // /cmip stats on the client thread while record() runs on the render thread.
    private volatile double emaMs;
    private volatile float userBudgetMs = 5.0f;
    private float emissionScale = 1.0f;
    private boolean initialized;

    public void setBudget(float ms) {
        this.userBudgetMs = Math.max(1.0f, ms);
    }

    public float budget() {
        return this.userBudgetMs;
    }

    /** Records one frame's engine cost and updates the emission scale. */
    public void record(double frameMs, boolean autoThrottle) {
        this.emaMs = this.initialized ? this.emaMs * 0.9 + frameMs * 0.1 : frameMs;
        this.initialized = true;
        if (!autoThrottle) {
            this.emissionScale = 1.0f;
            return;
        }
        if (this.emaMs > this.userBudgetMs) {
            this.emissionScale *= COOL_DOWN;
        } else if (this.emaMs < this.userBudgetMs * 0.5) {
            this.emissionScale = Math.min(1.0f, this.emissionScale * RAMP_UP);
        }
        this.emissionScale = Math.max(MIN_SCALE, Math.min(1.0f, this.emissionScale));
    }

    public float emissionScale() {
        return this.emissionScale;
    }

    public double emaMs() {
        return this.emaMs;
    }

    public void reset() {
        this.emaMs = 0;
        this.initialized = false;
    }
}
