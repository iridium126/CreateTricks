package com.iridium126.createmanaindustry.mixin.basin;

import java.util.Comparator;
import java.util.List;
import java.util.Optional;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import com.iridium126.createmanaindustry.CMIRecipeTypes;
import com.iridium126.createmanaindustry.content.fluids.mist.MistFieldStore;
import com.iridium126.createmanaindustry.content.kinetics.depositionlid.DepositionLidBlockEntity;
import com.iridium126.createmanaindustry.content.recipes.MistOutput;
import com.iridium126.createmanaindustry.content.recipes.MistRecipe;
import com.iridium126.createmanaindustry.content.recipes.MistRequirement;
import com.iridium126.createmanaindustry.network.ClientboundMistSyncPacket;
import com.simibubi.create.content.processing.basin.BasinBlockEntity;
import com.simibubi.create.content.processing.basin.BasinOperatingBlockEntity;

import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.item.crafting.Recipe;
import net.neoforged.neoforge.fluids.FluidStack;

/**
 * Basin operating machines (press, mixer, deployer, deposition lid):
 * <ul>
 *   <li>Sorts matching recipes so heated_compacting always wins.</li>
 *   <li>Consumes a recipe's {@code mist_requirement.amount} at completion and
 *       emits the {@code mist_result} byproduct into the mist field.</li>
 *   <li>Maintains the mist capacity reservation: self-polls while a waiting
 *       reservation exists (so a press/lid starts once capacity suffices), and
 *       refreshes the reservation while processing a mist-consuming recipe so
 *       the condenser yields.</li>
 *   <li>Releases the reservation and timed mist when the basin is removed.</li>
 * </ul>
 */
@Mixin(value = BasinOperatingBlockEntity.class, remap = false)
public class BasinOperatingBlockEntityMixin {

    @Shadow
    protected Recipe<?> currentRecipe;

    @Shadow
    protected boolean isRunning() { throw new AssertionError(); }

    @Shadow
    protected boolean matchBasinRecipe(Recipe<?> recipe) { throw new AssertionError(); }

    @Shadow
    protected Optional<BasinBlockEntity> getBasin() { throw new AssertionError(); }

    @Unique
    private BlockPos createmanaindustry$activeMistPos;

    @Unique
    private BlockPos createmanaindustry$reservedBasinPos;

    @Inject(method = "getMatchingRecipes", at = @At("RETURN"))
    private void createmanaindustry$prioritizeHeatedCompacting(CallbackInfoReturnable<List<Recipe<?>>> cir) {
        List<Recipe<?>> list = cir.getReturnValue();
        if (list.size() <= 1)
            return;
        // heated_compacting always takes priority over all other recipe types,
        // regardless of ingredient count. Among the rest, more ingredients first.
        var heatedType = CMIRecipeTypes.HEATED_COMPACTING.getType();
        list.sort(
                Comparator.<Recipe<?>, Boolean>comparing(r -> r.getType() != heatedType)
                        .thenComparing(Comparator.<Recipe<?>, Integer>comparing(
                                r -> r.getIngredients().size()).reversed()));
    }

    /**
     * After recipe completion: consume the requirement's mist amount, release the
     * reservation that protected it, and emit/extend timed mist if the recipe has
     * a {@code mist_result} byproduct.
     */
    @Inject(method = "applyBasinRecipe", at = @At("RETURN"))
    private void createmanaindustry$activateMistOnRecipe(CallbackInfo ci) {
        BasinOperatingBlockEntity self = (BasinOperatingBlockEntity) (Object) this;
        if (self.getLevel() == null || self.getLevel().isClientSide)
            return;

        if (!(currentRecipe instanceof MistRecipe mistRecipe))
            return;

        BlockPos basinPos = this.getBasin().map(BasinBlockEntity::getBlockPos).orElse(null);
        if (basinPos == null)
            return;

        // Consume the mist requirement at completion, then release the reservation.
        // Raw drain (respectReservations=false): the reservation already held the
        // amount through processing, so the physical field has it available.
        MistRequirement req = mistRecipe.getMistRequirement();
        if (req != null && req.amount() > 0) {
            MistFieldStore.consumeCapacity(self.getLevel(), basinPos, req.fluidId(), req.amount(), false);
            MistFieldStore.releaseReservation(self.getLevel(), basinPos);
            createmanaindustry$reservedBasinPos = null;
        }

        MistOutput mist = mistRecipe.getMistResult();
        if (mist == null)
            return;

        FluidStack fluid = new FluidStack(BuiltInRegistries.FLUID.get(mist.fluidId()), 1);

        // Timed emission: each recipe completion resets the timer and adds capacity.
        // expiryTick is an absolute game tick (MistFieldStore.tick compares it
        // against level.getGameTime()), so the mist_result duration must be
        // offset onto the current time — passing the bare duration would expire
        // the entry on the very next tick.
        MistFieldStore.emitOrExtendTimed(self.getLevel(), basinPos, fluid,
                mist.radius(), self.getLevel().getGameTime() + mist.duration(), mist.amount());
        ClientboundMistSyncPacket.sendToTracking(self.getLevel(), basinPos, fluid, mist.radius());
        createmanaindustry$activeMistPos = basinPos;
    }

    /**
     * Per-tick mist reservation maintenance:
     * <ul>
     *   <li>While a waiting reservation exists at the basin, keep the machine
     *       self-polling so it starts as soon as capacity suffices (the press and
     *       deposition lid do not self-schedule otherwise).</li>
     *   <li>While processing a mist recipe that consumes mist, refresh the
     *       reservation so the condenser yields and the field keeps its amount
     *       through to completion (the matching gate does not run during
     *       processing). Spinning machines only refresh while actually rotating
     *       (a stalled press must not hold the reservation forever); the
     *       shaftless deposition lid always refreshes, since its {@code running}
     *       flag already means it is genuinely progressing.</li>
     * </ul>
     */
    @Inject(method = "tick", at = @At("RETURN"))
    private void createmanaindustry$reconcileMistReservation(CallbackInfo ci) {
        BasinOperatingBlockEntity self = (BasinOperatingBlockEntity) (Object) this;
        if (self.getLevel() == null || self.getLevel().isClientSide)
            return;

        BlockPos basinPos = this.getBasin().map(BasinBlockEntity::getBlockPos).orElse(null);
        if (basinPos == null)
            return;

        if (MistFieldStore.hasWaitingReservation(self.getLevel(), basinPos)) {
            self.basinChecker.scheduleUpdate();
        }

        // The deposition lid has no shaft and processes basin recipes purely by
        // timer, so `running` alone means it is actively progressing — the speed
        // gate (a leak backstop for stalled spinning machines) must not apply.
        boolean activelyProcessing = self instanceof DepositionLidBlockEntity
                || (self.isSpeedRequirementFulfilled() && self.getSpeed() != 0);

        MistRequirement req = currentRecipe instanceof MistRecipe mr ? mr.getMistRequirement() : null;
        if (req != null && req.amount() > 0
                && isRunning()
                && activelyProcessing
                && this.matchBasinRecipe(currentRecipe)) {
            MistFieldStore.reserve(self.getLevel(), basinPos, req.fluidId(), req.amount());
            createmanaindustry$reservedBasinPos = basinPos;
        }
    }

    /**
     * When basin is removed, release the reservation and remove the timed mist
     * entry.
     * <p>
     * Injected after {@code onBasinRemoved()} is called inside
     * {@link BasinOperatingBlockEntity#tick()}, since the method itself is
     * {@code abstract} and subclass overrides do not call {@code super}.
     */
    @Inject(method = "tick",
            at = @At(value = "INVOKE",
                    target = "Lcom/simibubi/create/content/processing/basin/BasinOperatingBlockEntity;onBasinRemoved()V",
                    shift = At.Shift.AFTER))
    private void createmanaindustry$removeTimedOnBasinRemoved(CallbackInfo ci) {
        BasinOperatingBlockEntity self = (BasinOperatingBlockEntity) (Object) this;
        if (createmanaindustry$reservedBasinPos != null) {
            MistFieldStore.releaseReservation(self.getLevel(), createmanaindustry$reservedBasinPos);
            createmanaindustry$reservedBasinPos = null;
        }
        if (createmanaindustry$activeMistPos != null) {
            MistFieldStore.removeTimed(self.getLevel(), createmanaindustry$activeMistPos);
            ClientboundMistSyncPacket.sendToTracking(
                    self.getLevel(), createmanaindustry$activeMistPos, FluidStack.EMPTY, 0);
            createmanaindustry$activeMistPos = null;
        }
    }

    /**
     * When updateBasin runs and the machine is no longer actively processing a
     * mist recipe, clear local tracking. The timed entry expires naturally after
     * {@code duration} ticks — no explicit deactivation needed.
     */
    @Inject(method = "updateBasin", at = @At("RETURN"))
    private void createmanaindustry$clearTrackingOnIdle(CallbackInfoReturnable<Boolean> cir) {
        BasinOperatingBlockEntity self = (BasinOperatingBlockEntity) (Object) this;
        if (createmanaindustry$activeMistPos == null
                || self.getLevel() == null || self.getLevel().isClientSide)
            return;

        // Keep tracking while the machine is actively running a mist recipe
        if (isRunning() && currentRecipe instanceof MistRecipe mistRecipe
                && mistRecipe.getMistResult() != null)
            return;

        // Machine stopped or recipe changed — let the timed entry expire naturally
        createmanaindustry$activeMistPos = null;
    }
}
