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
 * Gating is purely package-based — the mixin class FQCN decides:
 * <ul>
 *   <li>{@code .bnb.}        → Bits 'n' Bobs AND Trickster required</li>
 *   <li>{@code .hextrick.}   → Hexcasting AND Trickster required</li>
 *   <li>{@code .trickster.}  → Trickster required</li>
 *   <li>{@code .hexcasting.} → Hexcasting required</li>
 *   <li>{@code .iris.}       → iris required</li>
 *   <li>{@code .irisveil.}   → iris-veil-compat required (implies iris)</li>
 *   <li>anything else        → always applied</li>
 * </ul>
 * Subpackages are part of the FQCN, so every mixin under
 * {@code com.iridium126.createmanaindustry.mixin} is covered; new mixins must
 * be placed in the subpackage matching their gate.
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
    private static final String IRIS_MOD_ID = "iris";
    private static final String IRISVEIL_MOD_ID = "irisveil";

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

        // Mixins bridging Hexcasting and Trickster — require both
        if (mixinClassName.contains(".hextrick."))
            return isLoaded(HEX_MOD_ID) && isLoaded(TRICKSTER_MOD_ID);

        // Mixins targeting Trickster classes — disable when Trickster is absent
        if (mixinClassName.contains(".trickster."))
            return isLoaded(TRICKSTER_MOD_ID);

        // Mixins handling Hexcasting items/recipes — disable when Hexcasting is absent
        if (mixinClassName.contains(".hexcasting."))
            return isLoaded(HEX_MOD_ID);

        // Mixins targeting iris internals — disable when iris is absent
        if (mixinClassName.contains(".iris."))
            return isLoaded(IRIS_MOD_ID);

        // Mixins reserving iris-veil-compat resources (the particle TBO texture
        // units) require iris-veil-compat itself; iris is its hard dependency,
        // so loading implies everything they target is present
        if (mixinClassName.contains(".irisveil."))
            return isLoaded(IRISVEIL_MOD_ID);

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
