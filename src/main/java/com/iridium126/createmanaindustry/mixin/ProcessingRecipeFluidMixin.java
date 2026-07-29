package com.iridium126.createmanaindustry.mixin;

import com.iridium126.createmanaindustry.CMIFluids;
import com.iridium126.createmanaindustry.CreateManaIndustry;
import com.iridium126.createmanaindustry.content.fluids.CMIFluidConversions;
import com.iridium126.createmanaindustry.hexcasting.HexCompat;
import com.simibubi.create.content.processing.recipe.ProcessingRecipe;
import net.minecraft.core.NonNullList;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.crafting.Ingredient;
import net.neoforged.neoforge.fluids.FluidStack;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Dynamically recalculates fluid output amounts for recipes that convert
 * Hexcasting media items into Liquid Media.
 * <p>
 * Recipe JSONs contain fluid amounts calculated with default config values.
 * This mixin replaces those amounts at runtime using the actual current values
 * of the Hexcasting media config and {@code mediaPerBucket}.
 * <p>
 * Items are identified via {@link BuiltInRegistries#ITEM} lookups rather than
 * direct Hexcasting class references, so the mixin loads safely even when
 * Hexcasting is absent. Config values come from {@link HexCompat}, which
 * provides safe fallback defaults.
 */
@Mixin(value = ProcessingRecipe.class, remap = false)
public class ProcessingRecipeFluidMixin {

    private static final ResourceLocation AMETHYST_DUST_ID =
            ResourceLocation.fromNamespaceAndPath("hexcasting", "amethyst_dust");
    private static final ResourceLocation CHARGED_AMETHYST_ID =
            ResourceLocation.fromNamespaceAndPath("hexcasting", "charged_amethyst");

    // Fallback media amounts when Hexcasting is absent
    private static final long FALLBACK_DUST_MEDIA = 10000L;
    private static final long FALLBACK_SHARD_MEDIA = 50000L;
    private static final long FALLBACK_CHARGED_MEDIA = 100000L;

    // Lazily-resolved item references. Initialised on first call to
    // getFluidResults() — by which point the item registry is frozen.
    // volatile ensures visibility across threads; duplicated init is harmless
    // since the registry always returns the same instance.
    private static volatile Item cachedAmethystDust;
    private static volatile Item cachedChargedAmethyst;
    private static volatile boolean itemsResolved;

    @Inject(method = "getFluidResults", at = @At("RETURN"), cancellable = true)
    private void createmanaindustry$modifyMediaFluidResults(
            CallbackInfoReturnable<NonNullList<FluidStack>> cir) {
        NonNullList<FluidStack> original = cir.getReturnValue();
        var liquidMedia = CMIFluids.LIQUID_MEDIA.get();

        // Only intercept recipes producing liquid_media
        boolean hasLiquidMedia = false;
        for (FluidStack fs : original) {
            if (!fs.isEmpty() && fs.getFluid().isSame(liquidMedia)) {
                hasLiquidMedia = true;
                break;
            }
        }
        if (!hasLiquidMedia)
            return;

        ProcessingRecipe<?, ?> recipe = (ProcessingRecipe<?, ?>) (Object) this;
        long mediaAmount = createmanaindustry$getMediaAmountFromIngredients(recipe);
        if (mediaAmount <= 0)
            return;

        int newAmount = CMIFluidConversions.mediaToFluidAmount(mediaAmount);
        if (newAmount <= 0)
            return;

        NonNullList<FluidStack> modified = NonNullList.create();
        for (FluidStack fs : original) {
            if (!fs.isEmpty() && fs.getFluid().isSame(liquidMedia)) {
                // Reuse the original fluid instance so that FluidStack.isSameFluidSameComponents()
                // (which uses reference equality internally) can still match. Using
                // CMIFluids.LIQUID_MEDIA.get() would produce a different Fluid instance
                // (flowing vs. source) which breaks the basin filter comparison.
                modified.add(new FluidStack(fs.getFluid(), newAmount));
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
}
