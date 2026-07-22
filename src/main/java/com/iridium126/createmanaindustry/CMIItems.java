package com.iridium126.createmanaindustry;

import static com.iridium126.createmanaindustry.CreateManaIndustry.REGISTRATE;

import com.iridium126.createmanaindustry.config.Config;
import com.iridium126.createmanaindustry.content.items.IncompleteHexItem;
import com.iridium126.createmanaindustry.content.items.IncompleteKnotItem;
import com.iridium126.createmanaindustry.content.items.IncompleteMediaBatteryItem;
import com.iridium126.createmanaindustry.content.items.KineticsSpellCoreItem;
import com.simibubi.create.AllBlocks;
import com.tterrag.registrate.providers.RegistrateRecipeProvider;
import com.tterrag.registrate.util.entry.ItemEntry;
import com.tterrag.registrate.util.nullness.NonNullBiConsumer;

import net.minecraft.data.recipes.RecipeCategory;
import net.minecraft.data.recipes.ShapedRecipeBuilder;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.Items;

public final class CMIItems {

    public static ItemEntry<Item> KINETICS_SPELL_CORE;
    public static ItemEntry<IncompleteKnotItem> INCOMPLETE_EMERALD_KNOT;
    public static ItemEntry<IncompleteKnotItem> INCOMPLETE_PRISMATIC_KNOT;
    public static ItemEntry<IncompleteKnotItem> INCOMPLETE_DIAMOND_KNOT;
    public static ItemEntry<IncompleteKnotItem> INCOMPLETE_ECHO_KNOT;
    public static ItemEntry<IncompleteKnotItem> INCOMPLETE_ASTRAL_KNOT;

    public static ItemEntry<IncompleteHexItem> INCOMPLETE_CYPHER;
    public static ItemEntry<IncompleteHexItem> INCOMPLETE_TRINKET;
    public static ItemEntry<IncompleteHexItem> INCOMPLETE_ARTIFACT;
    public static ItemEntry<IncompleteMediaBatteryItem> INCOMPLETE_MEDIA_BATTERY;

    private CMIItems() {
    }

    public static void register() {
        if (CreateManaIndustry.TRICKSTER_ACTIVE) {
            KINETICS_SPELL_CORE = REGISTRATE.item("kinetics_spell_core", KineticsSpellCoreItem::create)
                    .recipe((c, p) -> {
                        ShapedRecipeBuilder.shaped(RecipeCategory.MISC, c.get())
                                .define('L', Items.LEATHER)
                                .define('G', Items.GOLD_INGOT)
                                .define('T', Items.TUFF)
                                .define('C', AllBlocks.COGWHEEL.asItem())
                                .define('M', CMIFluids.LIQUID_MANA.getBucket().get())
                                .pattern("LGL")
                                .pattern("TCT")
                                .pattern("TMT")
                                .unlockedBy("has_liquid_mana",
                                        RegistrateRecipeProvider.has(CMIFluids.LIQUID_MANA.getBucket().get()))
                                .save(p, CreateManaIndustry.modLoc(c.getName()));
                    })
                    .register();

            INCOMPLETE_EMERALD_KNOT = incompleteKnot("incomplete_emerald_knot");
            INCOMPLETE_PRISMATIC_KNOT = incompleteKnot("incomplete_prismatic_knot");
            INCOMPLETE_DIAMOND_KNOT = incompleteKnot("incomplete_diamond_knot");
            INCOMPLETE_ECHO_KNOT = incompleteKnot("incomplete_echo_knot");
            INCOMPLETE_ASTRAL_KNOT = incompleteKnot("incomplete_astral_knot");
        }
        if (CreateManaIndustry.HEX_ACTIVE) {
            INCOMPLETE_CYPHER = REGISTRATE.item("incomplete_cypher",
                    p -> new IncompleteHexItem(p, () -> Config.cypherMaxMedia, hexLoc("cypher")))
                    .model(NonNullBiConsumer.noop())
                    .register();
            INCOMPLETE_TRINKET = REGISTRATE.item("incomplete_trinket",
                    p -> new IncompleteHexItem(p, () -> Config.trinketMaxMedia, hexLoc("trinket")))
                    .model(NonNullBiConsumer.noop())
                    .register();
            INCOMPLETE_ARTIFACT = REGISTRATE.item("incomplete_artifact",
                    p -> new IncompleteHexItem(p, () -> Config.artifactMaxMedia, hexLoc("artifact")))
                    .model(NonNullBiConsumer.noop())
                    .register();
            INCOMPLETE_MEDIA_BATTERY = REGISTRATE.item("incomplete_media_battery",
                    p -> new IncompleteMediaBatteryItem(p, () -> Config.batteryMaxMedia, hexLoc("battery")))
                    .model(NonNullBiConsumer.noop())
                    .register();
        }
    }

    private static ItemEntry<IncompleteKnotItem> incompleteKnot(String name) {
        return REGISTRATE.item(name, IncompleteKnotItem::new)
                .model(NonNullBiConsumer.noop())
                .register();
    }

    private static ResourceLocation hexLoc(String path) {
        return ResourceLocation.fromNamespaceAndPath("hexcasting", path);
    }
}
