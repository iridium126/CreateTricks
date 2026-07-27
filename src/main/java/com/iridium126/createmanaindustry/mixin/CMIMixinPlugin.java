package com.iridium126.createmanaindustry.mixin;

import java.util.List;
import java.util.Set;

import org.objectweb.asm.tree.ClassNode;
import org.spongepowered.asm.mixin.extensibility.IMixinConfigPlugin;
import org.spongepowered.asm.mixin.extensibility.IMixinInfo;

import net.neoforged.fml.loading.FMLLoader;

/**
 * Conditionally enables mixins based on which optional dependencies are loaded.
 * <p>
 * Uses {@link FMLLoader#getLoadingModList()} rather than {@code ModList.get()}
 * because the mixin plugin runs during the bootstrap phase — before NeoForge's
 * {@code ModList} singleton is populated.  {@code FMLLoader.getLoadingModList()}
 * is the lower-level equivalent available at this stage.
 * <p>
 * The same information is exposed at runtime via the static flags in
 * {@code CreateManaIndustry} ({@code TRICKSTER_ACTIVE}, {@code BNB_ACTIVE}).
 */
public class CMIMixinPlugin implements IMixinConfigPlugin {

    private static final String BNB_MOD_ID = "bits_n_bobs";
    private static final String TRICKSTER_MOD_ID = "trickster";
    private static final String HEX_MOD_ID = "hexcasting";

    @Override
    public void onLoad(String mixinPackage) {}

    @Override
    public String getRefMapperConfig() {
        return null;
    }

    @Override
    public boolean shouldApplyMixin(String targetClassName, String mixinClassName) {
        // BnB cogwheel-chain mixins require Bits 'n' Bobs AND Trickster
        if (mixinClassName.contains(".bnb."))
            return isLoaded(BNB_MOD_ID) && isLoaded(TRICKSTER_MOD_ID);

        // Mixins that target Trickster classes — disable when Trickster is absent
        if (mixinClassName.contains("TricksterSpellConstructSync")
                || mixinClassName.contains("ModularSpellConstructBlockEntityRenderer"))
            return isLoaded(TRICKSTER_MOD_ID);

        // RecipeManagerMixin injects slate pattern stonecutting recipes from Hexcasting
        if (mixinClassName.contains("RecipeManagerMixin"))
            return isLoaded(HEX_MOD_ID);

        // Mixins that handle incomplete hexcasting items via Create machines
        if (mixinClassName.contains("HexItem")
                || mixinClassName.contains("IotaTransfer"))
            return isLoaded(HEX_MOD_ID);

        return true;
    }

    @Override
    public void acceptTargets(Set<String> myTargets, Set<String> otherTargets) {}

    @Override
    public List<String> getMixins() {
        return null;
    }

    @Override
    public void preApply(String targetClassName, ClassNode targetClass, String mixinClassName, IMixinInfo mixinInfo) {}

    @Override
    public void postApply(String targetClassName, ClassNode targetClass, String mixinClassName, IMixinInfo mixinInfo) {}

    // ---- helpers ----------------------------------------------------------

    /**
     * Checks whether a mod is present during the mixin bootstrap phase.
     * <p>
     * This must use {@code FMLLoader.getLoadingModList()} — the higher-level
     * {@code ModList.get()} is not yet populated when mixin plugins are queried.
     */
    private static boolean isLoaded(String modId) {
        return FMLLoader.getLoadingModList().getModFileById(modId) != null;
    }
}
