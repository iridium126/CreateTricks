package com.iridium126.createmanaindustry.mixin.hexcasting;

import java.util.List;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import com.iridium126.createmanaindustry.content.recipes.HexItemDataTransfer;
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
 * Uses a ThreadLocal to capture the deployer's held item at method entry,
 * then consumes it during the recipe application redirect to append the
 * Iota from the held item to the recipe output.
 */
@Mixin(value = BeltDeployerCallbacks.class, remap = false)
public class BeltDeployerCallbacksMixin {

    private static final ThreadLocal<ItemStack> HELD_ITEM = new ThreadLocal<>();

    @Inject(method = "activate", at = @At("HEAD"), cancellable = true)
    private static void createmanaindustry$captureHeldItem(TransportedItemStack transported,
            TransportedItemStackHandlerBehaviour handler,
            DeployerBlockEntity blockEntity, Recipe<?> recipe, CallbackInfo ci) {
        DeployerFakePlayer player = blockEntity.getPlayer();
        ItemStack heldItem = player != null ? player.getMainHandItem().copy() : ItemStack.EMPTY;

        // Validate scroll op_id for the battery-scroll deploying recipe.
        // The recipe JSON cannot filter by data components reliably through
        // Create's Ingredient codec, so we enforce the check here.
        if (!HexItemDataTransfer.validateScrollOpId(heldItem)) {
            ci.cancel();
            return;
        }

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
}
