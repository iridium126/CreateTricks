package com.iridium126.createmanaindustry.mixin;

import com.iridium126.createmanaindustry.CMIFluids;
import com.iridium126.createmanaindustry.CreateManaIndustry;
import com.iridium126.createmanaindustry.compat.hexcasting.HexCompat;
import com.iridium126.createmanaindustry.content.fluids.CMIFluidConversions;
import com.simibubi.create.content.processing.recipe.ProcessingRecipe;
import net.minecraft.core.NonNullList;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.crafting.Ingredient;
import net.minecraft.world.level.material.Fluids;
import net.neoforged.neoforge.fluids.FluidStack;
import net.neoforged.neoforge.fluids.crafting.SizedFluidIngredient;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Dynamically recalculates fluid amounts for processing recipes whose JSON
 * amounts were written for default config values:
 * <ul>
 *   <li>Liquid Media outputs (Hexcasting media items) scale with the
 *       Hexcasting media config and {@code mediaPerBucket}.</li>
 *   <li>Liquid Source recipes (sweet berries / sourceberry bush + water) scale
 *       both the water input and the liquid source output with
 *       {@code sourcePerBucket}, keeping the produced source amount constant.</li>
 * </ul>
 * <p>
 * Items are identified via {@link BuiltInRegistries#ITEM} lookups rather than
 * direct Hexcasting / Ars Nouveau class references, so the mixin loads safely
 * even when those mods are absent. Config values come from {@link HexCompat}
 * (safe fallback defaults) and the project's own {@code Config}.
 */
@Mixin(value = ProcessingRecipe.class, remap = false)
public class ProcessingRecipeFluidMixin {

    private static final ResourceLocation AMETHYST_DUST_ID =
            ResourceLocation.fromNamespaceAndPath("hexcasting", "amethyst_dust");
    private static final ResourceLocation CHARGED_AMETHYST_ID =
            ResourceLocation.fromNamespaceAndPath("hexcasting", "charged_amethyst");
    private static final ResourceLocation SOURCEBERRY_BUSH_ID =
            ResourceLocation.fromNamespaceAndPath("ars_nouveau", "sourceberry_bush");

    // Fallback media amounts when Hexcasting is absent
    private static final long FALLBACK_DUST_MEDIA = 10000L;
    private static final long FALLBACK_SHARD_MEDIA = 50000L;
    private static final long FALLBACK_CHARGED_MEDIA = 100000L;

    // Source units per berry item — independent of SOURCE_PER_BUCKET. The mB
    // amounts written in the recipe JSON scale with 1 / sourcePerBucket, so the
    // produced source total stays constant.
    private static final int SWEET_BERRIES_SOURCE_UNITS = 25;
    private static final int SOURCEBERRY_BUSH_SOURCE_UNITS = 100;

    // Lazily-resolved item references. Initialised on first call to
    // getFluidResults() — by which point the item registry is frozen.
    // volatile ensures visibility across threads; duplicated init is harmless
    // since the registry always returns the same instance.
    private static volatile Item cachedAmethystDust;
    private static volatile Item cachedChargedAmethyst;
    private static volatile Item cachedSourceberryBush;
    private static volatile boolean itemsResolved;

    @Inject(method = "getFluidResults", at = @At("RETURN"), cancellable = true)
    private void createmanaindustry$modifyMediaAndSourceFluidResults(
            CallbackInfoReturnable<NonNullList<FluidStack>> cir) {
        NonNullList<FluidStack> original = cir.getReturnValue();
        var liquidMedia = CMIFluids.LIQUID_MEDIA.get();
        var liquidSource = CMIFluids.LIQUID_SOURCE.get();

        // Only intercept recipes producing liquid_media and/or liquid_source
        boolean hasLiquidMedia = false;
        boolean hasLiquidSource = false;
        for (FluidStack fs : original) {
            if (fs.isEmpty())
                continue;
            if (fs.getFluid().isSame(liquidMedia))
                hasLiquidMedia = true;
            else if (fs.getFluid().isSame(liquidSource))
                hasLiquidSource = true;
        }
        if (!hasLiquidMedia && !hasLiquidSource)
            return;

        ProcessingRecipe<?, ?> recipe = (ProcessingRecipe<?, ?>) (Object) this;

        long mediaAmount = hasLiquidMedia
                ? createmanaindustry$getMediaAmountFromIngredients(recipe) : 0;
        int newMediaAmount = mediaAmount > 0
                ? CMIFluidConversions.mediaToFluidAmount(mediaAmount) : -1;
        int sourceUnits = hasLiquidSource
                ? createmanaindustry$getSourceUnitsFromIngredients(recipe) : 0;
        int newSourceAmount = sourceUnits > 0
                ? CMIFluidConversions.sourceToFluidAmount(sourceUnits) : -1;
        if (newMediaAmount <= 0 && newSourceAmount <= 0)
            return;

        NonNullList<FluidStack> modified = NonNullList.create();
        for (FluidStack fs : original) {
            if (!fs.isEmpty() && fs.getFluid().isSame(liquidMedia) && newMediaAmount > 0) {
                // Reuse the original fluid instance so that FluidStack.isSameFluidSameComponents()
                // (which uses reference equality internally) can still match. Using
                // CMIFluids.LIQUID_MEDIA.get() would produce a different Fluid instance
                // (flowing vs. source) which breaks the basin filter comparison.
                modified.add(new FluidStack(fs.getFluid(), newMediaAmount));
            } else if (!fs.isEmpty() && fs.getFluid().isSame(liquidSource) && newSourceAmount > 0) {
                // Same reasoning applies to liquid source: keep the original fluid
                // instance so the basin filter comparison still matches.
                modified.add(new FluidStack(fs.getFluid(), newSourceAmount));
            } else {
                modified.add(fs);
            }
        }
        cir.setReturnValue(modified);
    }

    /**
     * Inspects the recipe's item ingredients to determine which media item is
     * being processed. Uses lazy-cached registry-name lookups (not Hexcasting
     * class references) so the mixin loads safely when Hexcasting is absent.
     *
     * @return the media amount for the recognised item, or 0 if none matches
     */
    private static long createmanaindustry$getMediaAmountFromIngredients(
            ProcessingRecipe<?, ?> recipe) {
        if (!itemsResolved) {
            cachedAmethystDust = BuiltInRegistries.ITEM.get(AMETHYST_DUST_ID);
            cachedChargedAmethyst = BuiltInRegistries.ITEM.get(CHARGED_AMETHYST_ID);
            cachedSourceberryBush = BuiltInRegistries.ITEM.get(SOURCEBERRY_BUSH_ID);
            itemsResolved = true;
        }

        for (Ingredient ingredient : recipe.getIngredients()) {
            for (ItemStack stack : ingredient.getItems()) {
                Item item = stack.getItem();
                if (cachedAmethystDust != Items.AIR && item == cachedAmethystDust)
                    return CreateManaIndustry.HEX_ACTIVE
                            ? HexCompat.getDustMediaAmount()
                            : FALLBACK_DUST_MEDIA;
                if (item == Items.AMETHYST_SHARD)
                    return CreateManaIndustry.HEX_ACTIVE
                            ? HexCompat.getShardMediaAmount()
                            : FALLBACK_SHARD_MEDIA;
                if (cachedChargedAmethyst != Items.AIR && item == cachedChargedAmethyst)
                    return CreateManaIndustry.HEX_ACTIVE
                            ? HexCompat.getChargedCrystalMediaAmount()
                            : FALLBACK_CHARGED_MEDIA;
            }
        }
        return 0;
    }

    /**
     * Inspects the recipe's item ingredients to determine which berry item is
     * being processed. Uses lazy-cached registry-name lookups (not Ars Nouveau
     * class references) so the mixin loads safely when Ars Nouveau is absent.
     *
     * @return the source units for the recognised item, or 0 if none matches
     */
    private static int createmanaindustry$getSourceUnitsFromIngredients(
            ProcessingRecipe<?, ?> recipe) {
        for (Ingredient ingredient : recipe.getIngredients()) {
            for (ItemStack stack : ingredient.getItems()) {
                Item item = stack.getItem();
                if (item == Items.SWEET_BERRIES)
                    return SWEET_BERRIES_SOURCE_UNITS;
                if (cachedSourceberryBush != Items.AIR && item == cachedSourceberryBush)
                    return SOURCEBERRY_BUSH_SOURCE_UNITS;
            }
        }
        return 0;
    }

    /**
     * Scales the water input of the berry mixing recipes with
     * {@code sourcePerBucket}, mirroring the output scaling in
     * {@link #createmanaindustry$modifyMediaAndSourceFluidResults}. The recipe
     * JSON's water amount is written for the default config value; at runtime
     * it is replaced so input and output stay equal (1:1) and the produced
     * source total stays constant.
     * <p>
     * The list is modified in place — this is safe because the source units are
     * re-derived from the item ingredients on every call, so re-entry and
     * config reloads both converge on the current scaled amount.
     */
    @Inject(method = "getFluidIngredients", at = @At("RETURN"), cancellable = true)
    private void createmanaindustry$modifyBerryFluidInputAmounts(
            CallbackInfoReturnable<NonNullList<SizedFluidIngredient>> cir) {
        ProcessingRecipe<?, ?> recipe = (ProcessingRecipe<?, ?>) (Object) this;
        int sourceUnits = createmanaindustry$getSourceUnitsFromIngredients(recipe);
        if (sourceUnits <= 0)
            return;
        int newAmount = CMIFluidConversions.sourceToFluidAmount(sourceUnits);
        if (newAmount <= 0)
            return;

        NonNullList<SizedFluidIngredient> fluidIngredients = cir.getReturnValue();
        FluidStack waterProbe = new FluidStack(Fluids.WATER, 1);
        boolean modified = false;
        for (int i = 0; i < fluidIngredients.size(); i++) {
            SizedFluidIngredient sfi = fluidIngredients.get(i);
            // Test the inner ingredient — SizedFluidIngredient.test() also checks
            // stack.getAmount() >= amount, so a 1 mB probe would never pass there.
            if (!sfi.ingredient().test(waterProbe))
                continue;
            fluidIngredients.set(i, new SizedFluidIngredient(sfi.ingredient(), newAmount));
            modified = true;
        }
        if (modified)
            cir.setReturnValue(fluidIngredients);
    }
}
