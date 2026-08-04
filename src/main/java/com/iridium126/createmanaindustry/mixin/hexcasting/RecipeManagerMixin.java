package com.iridium126.createmanaindustry.mixin.hexcasting;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import com.iridium126.createmanaindustry.CreateManaIndustry;
import com.iridium126.createmanaindustry.compat.hexcasting.CMISlatePatternRecipes;

import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.crafting.RecipeHolder;
import net.minecraft.world.item.crafting.RecipeManager;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Injects slate-pattern stonecutting recipes into {@link RecipeManager}
 * after all data-pack recipes have been loaded.
 * <p>
 * Only enabled when Hexcasting is present — gated by {@code CMIMixinPlugin}.
 */
@Mixin(RecipeManager.class)
public class RecipeManagerMixin {

    @Shadow
    private Map<ResourceLocation, RecipeHolder<?>> byName;

    /**
     * Fires after every recipe map rebuild (initial load, {@code /reload}, etc.).
     * Merges the dynamically-generated slate pattern recipes into the freshly
     * built recipe collection via {@link RecipeManager#replaceRecipes(Iterable)}.
     * <p>
     * Previously-injected dynamic recipes are filtered out before merging to
     * avoid duplicate-ID errors on {@code /reload} (the freshly-loaded recipes
     * from {@code byName} already contain the previous injection's entries).
     */
    @Inject(method = "apply(Ljava/util/Map;Lnet/minecraft/server/packs/resources/ResourceManager;Lnet/minecraft/util/profiling/ProfilerFiller;)V",
            at = @At("TAIL"))
    private void createmanaindustry$injectSlatePatternRecipes(CallbackInfo ci) {
        List<RecipeHolder<?>> dynamic = CMISlatePatternRecipes.createRecipes();
        if (dynamic.isEmpty())
            return;

        // Filter out old dynamic recipes (from a previous /reload) so we don't
        // pass duplicate IDs to replaceRecipes(), which would throw
        List<RecipeHolder<?>> merged = new ArrayList<>();
        String prefix = CMISlatePatternRecipes.RECIPE_PREFIX;
        for (RecipeHolder<?> existing : byName.values()) {
            if (!existing.id().getNamespace().equals(CreateManaIndustry.MODID)
                    || !existing.id().getPath().startsWith(prefix)) {
                merged.add(existing);
            }
        }
        merged.addAll(dynamic);

        ((RecipeManager) (Object) this).replaceRecipes(merged);
    }
}
