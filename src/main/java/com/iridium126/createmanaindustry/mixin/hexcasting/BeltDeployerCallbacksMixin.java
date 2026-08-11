package com.iridium126.createmanaindustry.mixin.hexcasting;

import java.util.List;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import com.iridium126.createmanaindustry.content.recipes.HexItemDataTransfer;
import com.simibubi.create.content.kinetics.belt.behaviour.BeltProcessingBehaviour.ProcessingResult;
import com.simibubi.create.content.kinetics.belt.behaviour.TransportedItemStackHandlerBehaviour;
import com.simibubi.create.content.kinetics.belt.transport.TransportedItemStack;
import com.simibubi.create.content.kinetics.deployer.BeltDeployerCallbacks;
import com.simibubi.create.content.kinetics.deployer.DeployerBlockEntity;
import com.simibubi.create.content.kinetics.deployer.DeployerFakePlayer;
import com.simibubi.create.foundation.recipe.RecipeApplier;

import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.Recipe;
import net.minecraft.world.level.Level;

/**
 * Handles Iota transfer from the deployer's held item to the incomplete hex
 * item during belt-based deployer processing.
 * <p>
 * The deployer's punch animation is driven by {@link DeployerBlockEntity#start()},
 * called from {@code onItemReceived} / {@code whenItemHeld} before the recipe is
 * applied. Cancelling only {@code activate} (the recipe application at the end of
 * the cycle) would leave an invalid-scroll item sitting on the belt while the
 * deployer punches it forever, so the scroll {@code op_id} is validated at the
 * {@code start()} call sites too — an invalid scroll never starts the animation.
 * <p>
 * The same {@code start()} call sites reject <b>finished</b> hexcasting spell
 * items (cypher/trinket/artifact carrying stored hex data): their {@code <x>_or_incomplete}
 * deployer recipe would otherwise match and downgrade them back to an incomplete
 * pipeline intermediate. Letting them pass untouched (PASS) keeps them finished.
 */
@Mixin(value = BeltDeployerCallbacks.class, remap = false)
public class BeltDeployerCallbacksMixin {

    private static final ThreadLocal<ItemStack> HELD_ITEM = new ThreadLocal<>();

    @Inject(method = "onItemReceived", at = @At(value = "INVOKE",
            target = "Lcom/simibubi/create/content/kinetics/deployer/DeployerBlockEntity;start()V"),
            cancellable = true)
    private static void createmanaindustry$rejectUnprocessableBeforeStart(TransportedItemStack transported,
            TransportedItemStackHandlerBehaviour handler, DeployerBlockEntity blockEntity,
            CallbackInfoReturnable<ProcessingResult> cir) {
        // Invalid scroll, or a finished hexcasting spell item that must not be
        // downgraded back into a pipeline intermediate — let it pass untouched.
        if (!isValidHeldScroll(blockEntity) || HexItemDataTransfer.isFinishedHexItem(transported.stack))
            cir.setReturnValue(ProcessingResult.PASS);
    }

    @Inject(method = "whenItemHeld", at = @At(value = "INVOKE",
            target = "Lcom/simibubi/create/content/kinetics/deployer/DeployerBlockEntity;start()V"),
            cancellable = true)
    private static void createmanaindustry$rejectUnprocessableWhenHeld(TransportedItemStack transported,
            TransportedItemStackHandlerBehaviour handler, DeployerBlockEntity blockEntity,
            CallbackInfoReturnable<ProcessingResult> cir) {
        // Same rejection while already held: a finished item that became held
        // (or a scroll check that changed) must not start the punch either.
        if (!isValidHeldScroll(blockEntity) || HexItemDataTransfer.isFinishedHexItem(transported.stack))
            cir.setReturnValue(ProcessingResult.PASS);
    }

    @Inject(method = "activate", at = @At("HEAD"))
    private static void createmanaindustry$captureHeldItem(TransportedItemStack transported,
            TransportedItemStackHandlerBehaviour handler,
            DeployerBlockEntity blockEntity, Recipe<?> recipe, CallbackInfo ci) {
        DeployerFakePlayer player = blockEntity.getPlayer();
        ItemStack heldItem = player != null ? player.getMainHandItem().copy() : ItemStack.EMPTY;
        HELD_ITEM.set(heldItem);
    }

    @Redirect(method = "activate",
            at = @At(value = "INVOKE",
                    target = "Lcom/simibubi/create/foundation/recipe/RecipeApplier;applyRecipeOn(Lnet/minecraft/world/level/Level;Lnet/minecraft/world/item/ItemStack;Lnet/minecraft/world/item/crafting/Recipe;Z)Ljava/util/List;"))
    private static List<ItemStack> createmanaindustry$appendIotaOnDeployer(Level level, ItemStack stack, Recipe<?> recipe,
            boolean respectChances) {
        ItemStack heldItem = HELD_ITEM.get();
        HELD_ITEM.remove();

        List<ItemStack> results = RecipeApplier.applyRecipeOn(level, stack, recipe, respectChances);

        if (heldItem == null || heldItem.isEmpty())
            return results;

        // Post-process each result: copy data from input, append Iota from held item
        for (int i = 0; i < results.size(); i++) {
            ItemStack processed = HexItemDataTransfer.applyDeployerIotaAppend(
                    results.get(i), stack, heldItem);
            if (processed != results.get(i)) {
                results.set(i, processed);
            }
        }

        return results;
    }

    private static boolean isValidHeldScroll(DeployerBlockEntity blockEntity) {
        DeployerFakePlayer player = blockEntity.getPlayer();
        ItemStack held = player != null ? player.getMainHandItem() : ItemStack.EMPTY;
        return HexItemDataTransfer.validateScrollOpId(held);
    }
}
