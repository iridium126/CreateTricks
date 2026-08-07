package com.iridium126.createmanaindustry.mixin.basin;

import java.util.Comparator;
import java.util.List;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import com.iridium126.createmanaindustry.CMIRecipeTypes;
import com.iridium126.createmanaindustry.content.fluids.mist.MistFieldStore;
import com.iridium126.createmanaindustry.content.recipes.MistRecipe;
import com.iridium126.createmanaindustry.content.recipes.MistOutput;
import com.iridium126.createmanaindustry.network.ClientboundMistSyncPacket;
import com.simibubi.create.content.processing.basin.BasinOperatingBlockEntity;

import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.item.crafting.Recipe;
import net.neoforged.neoforge.fluids.FluidStack;

/**
 * Basin operating machines (press, mixer, deployer...):
 * <ul>
 *   <li>Sorts matching recipes so heated_compacting always wins.</li>
 *   <li>Activates/deactivates persistent mist at the basin position when a
 *       {@link MistRecipe} is being processed.</li>
 * </ul>
 */
@Mixin(value = BasinOperatingBlockEntity.class, remap = false)
public class BasinOperatingBlockEntityMixin {

    @Shadow
    protected Recipe<?> currentRecipe;

    @Shadow
    protected boolean isRunning() { throw new AssertionError(); }

    @Unique
    private BlockPos createmanaindustry$activeMistPos;

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

    /** After recipe completes, emit/extend timed mist if the recipe has mist output. */
    @Inject(method = "applyBasinRecipe", at = @At("RETURN"))
    private void createmanaindustry$activateMistOnRecipe(CallbackInfo ci) {
        BasinOperatingBlockEntity self = (BasinOperatingBlockEntity) (Object) this;
        if (self.getLevel() == null || self.getLevel().isClientSide)
            return;

        if (!(currentRecipe instanceof MistRecipe mistRecipe))
            return;

        MistOutput mist = mistRecipe.getMistOutput();
        if (mist == null)
            return;

        // Basin is 2 blocks below the operating machine
        BlockPos basinPos = self.getBlockPos().below(2);
        FluidStack fluid = new FluidStack(BuiltInRegistries.FLUID.get(mist.fluidId()), 1);

        // Timed emission: each recipe completion resets the timer and adds capacity
        MistFieldStore.emitOrExtendTimed(self.getLevel(), basinPos, fluid,
                mist.radius(), mist.duration(), mist.amount());
        ClientboundMistSyncPacket.sendToTracking(self.getLevel(), basinPos, fluid, mist.radius());
        createmanaindustry$activeMistPos = basinPos;
    }

    /**
     * When basin is removed, remove the timed mist entry.
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
                && mistRecipe.getMistOutput() != null)
            return;

        // Machine stopped or recipe changed — let the timed entry expire naturally
        createmanaindustry$activeMistPos = null;
    }
}
