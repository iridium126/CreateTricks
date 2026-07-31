package com.iridium126.createmanaindustry.content.burner;

import net.minecraft.util.Mth;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.animal.allay.Allay;
import net.minecraft.world.level.Level;

/**
 * A cached {@link Allay} used only for client-side rendering inside the Allay
 * Burner. Vanilla's {@link Allay#setDancing} is a no-op on the client
 * (guarded by {@code !level().isClientSide && isEffectiveAi()}), so this
 * subclass drives the dance state directly and replicates the exact
 * client-side dance counter math from {@code Allay.tick()} — the same numbers
 * {@link net.minecraft.client.model.AllayModel#setupAnim} reads via
 * {@code isDancing() / isSpinning() / getSpinningProgress()}, reproducing the
 * vanilla dance animation faithfully.
 * <p>
 * Never add this entity to a level and never call {@code tick()} on it.
 */
public class RenderedAllay extends Allay {

    private boolean dancing;
    private float dancingAnimationTicks;
    private float spinningAnimationTicks;
    private float spinningAnimationTicks0;
    private int lastClientTick = -1;

    public RenderedAllay(Level level) {
        super(EntityType.ALLAY, level);
    }

    /**
     * Called once per rendered frame. Keeps the dancing state in sync with the
     * burner's heat level immediately (fixes stale state when multiple burners
     * share this renderer), while advancing the vanilla dance counters at most
     * once per client tick. tickCount is synced to the client tick so that
     * {@code ageInTicks - tickCount} stays at {@code partialTicks} — the
     * interpolation coefficient AllayModel.setupAnim feeds into
     * {@link #getSpinningProgress}.
     */
    public void updateDance(boolean dancing, int clientTick) {
        this.dancing = dancing;
        tickCount = clientTick;
        if (clientTick == lastClientTick)
            return;
        lastClientTick = clientTick;
        tickDance();
    }

    @Override
    public boolean isDancing() {
        return dancing;
    }

    @Override
    public boolean isSpinning() {
        return dancingAnimationTicks % 55.0F < 15.0F;
    }

    @Override
    public float getSpinningProgress(float partialTick) {
        return Mth.lerp(partialTick, spinningAnimationTicks0, spinningAnimationTicks) / 15.0F;
    }

    @Override
    public float getHoldingItemAnimationProgress(float partialTick) {
        return 0.0F;
    }

    /**
     * Exact replica of the client-side dance counter branch of
     * {@code Allay.tick()} (called once per client tick by the renderer,
     * AFTER {@link #setDancingState} so the counters advance under the new
     * state — the same ordering vanilla uses).
     */
    public void tickDance() {
        if (dancing) {
            dancingAnimationTicks++;
            spinningAnimationTicks0 = spinningAnimationTicks;
            if (isSpinning()) {
                spinningAnimationTicks++;
            } else {
                spinningAnimationTicks--;
            }
            spinningAnimationTicks = Mth.clamp(spinningAnimationTicks, 0.0F, 15.0F);
        } else {
            dancingAnimationTicks = 0.0F;
            spinningAnimationTicks = 0.0F;
            spinningAnimationTicks0 = 0.0F;
        }
    }
}
