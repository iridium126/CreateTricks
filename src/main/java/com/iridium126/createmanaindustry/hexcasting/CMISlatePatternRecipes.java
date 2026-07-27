package com.iridium126.createmanaindustry.hexcasting;

import java.util.ArrayList;
import java.util.List;

import com.iridium126.createmanaindustry.CreateManaIndustry;

import at.petrak.hexcasting.api.casting.ActionRegistryEntry;
import at.petrak.hexcasting.api.mod.HexTags;
import at.petrak.hexcasting.api.utils.HexUtils;
import at.petrak.hexcasting.common.lib.HexDataComponents;
import at.petrak.hexcasting.common.lib.HexItems;
import net.minecraft.core.Registry;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.Ingredient;
import net.minecraft.world.item.crafting.RecipeHolder;
import net.minecraft.world.item.crafting.RecipeManager;
import net.minecraft.world.item.crafting.StonecutterRecipe;
import net.neoforged.fml.ModList;
import net.neoforged.neoforge.event.server.ServerStartedEvent;

/**
 * Generates stonecutting recipes for Hexcasting slate patterns at runtime.
 * <p>
 * Each registered action (except great spells tagged with
 * {@link HexTags.Actions#PER_WORLD_PATTERN}) gets a stonecutter recipe:
 * 1 blank {@code hexcasting:slate} → 1 slate with the action's prototype
 * pattern written on it via {@link HexDataComponents#PATTERN}.
 * <p>
 * All Hexcasting API references are isolated here so the JVM never loads this
 * class when Hexcasting is absent.  Callers must guard with
 * {@link CreateManaIndustry#HEX_ACTIVE}.
 */
public final class CMISlatePatternRecipes {

    /** Recipe ID prefix for the generated stonecutting recipes. */
    public static final String RECIPE_PREFIX = "stonecutting/slate_pattern/";

    private CMISlatePatternRecipes() {}

    /**
     * Creates {@link RecipeHolder} entries for every non-great-spell action
     * in the Hexcasting action registry.
     *
     * @return a list of stonecutting recipe holders (empty if Hexcasting is absent
     *         or the registry is unavailable)
     */
    public static List<RecipeHolder<?>> createRecipes() {
        List<RecipeHolder<?>> result = new ArrayList<>();

        if (!CreateManaIndustry.HEX_ACTIVE)
            return result;

        if (!ModList.get().isLoaded("hexcasting"))
            return result;

        Registry<ActionRegistryEntry> registry;
        try {
            registry = at.petrak.hexcasting.xplat.IXplatAbstractions.INSTANCE.getActionRegistry();
        } catch (Exception e) {
            CreateManaIndustry.LOGGER.error("Failed to access Hexcasting action registry", e);
            return result;
        }

        if (registry == null)
            return result;

        for (ResourceKey<ActionRegistryEntry> key : registry.registryKeySet()) {
            ActionRegistryEntry entry = registry.get(key);
            if (entry == null)
                continue;

            if (HexUtils.isOfTag(registry, key, HexTags.Actions.PER_WORLD_PATTERN))
                continue;

            ResourceLocation actionId = key.location();
            ResourceLocation recipeId = ResourceLocation.fromNamespaceAndPath(
                    CreateManaIndustry.MODID,
                    RECIPE_PREFIX + actionId.getNamespace() + "/" + actionId.getPath());

            ItemStack resultStack = new ItemStack(HexItems.SLATE.get());
            resultStack.set(HexDataComponents.PATTERN, entry.prototype());

            StonecutterRecipe recipe = new StonecutterRecipe("",
                    Ingredient.of(HexItems.SLATE.get()), resultStack);
            result.add(new RecipeHolder<>(recipeId, recipe));
        }

        if (!result.isEmpty())
            CreateManaIndustry.LOGGER.info("Generated {} slate pattern stonecutting recipes", result.size());

        return result;
    }

    // ---- server lifecycle: re-inject after tags are loaded ------------------

    /**
     * Re-injects slate-pattern recipes during {@link ServerStartedEvent}.
     * <p>
     * The earlier injection from {@code RecipeManagerMixin} (during
     * {@code RecipeManager.apply()}) runs before action tags are populated.
     * By the time {@code ServerStartedEvent} fires, all tags are loaded and
     * {@link HexUtils#isOfTag} works correctly — addon-mod great spells are
     * also excluded.
     * <p>
     * Old mixin-injected recipes (by namespace + {@link #RECIPE_PREFIX}) are
     * removed first, then the correctly-filtered set is merged in via
     * {@link RecipeManager#replaceRecipes(Iterable)}.
     */
    public static void onServerStarted(ServerStartedEvent event) {
        if (!CreateManaIndustry.HEX_ACTIVE)
            return;

        RecipeManager rm = event.getServer().getRecipeManager();
        List<RecipeHolder<?>> merged = new ArrayList<>(rm.getRecipes());

        // Purge old mixin-injected recipes (which may have included addon-mod
        // great spells that the hardcoded set couldn't catch on initial load)
        merged.removeIf(h ->
                h.id().getNamespace().equals(CreateManaIndustry.MODID)
                        && h.id().getPath().startsWith(RECIPE_PREFIX));

        // Regenerate with correct tag filtering — tags are now loaded
        merged.addAll(createRecipes());

        rm.replaceRecipes(merged);
    }
}
