package com.iridium126.createmanaindustry;

import static com.iridium126.createmanaindustry.CreateManaIndustry.REGISTRATE;

import com.iridium126.createmanaindustry.config.ServerConfig;
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
import net.minecraft.data.recipes.ShapelessRecipeBuilder;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.Items;
import net.neoforged.neoforge.common.Tags;

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

    // Prismarine Quartz — this mod's parallel to Create's Rose Quartz: a plain
    // gem item obtained from quartz + prismarine shards, smelted (superheated
    // compacting) into Molten Prismarine Quartz. Core item, always registered.
    public static final ItemEntry<Item> PRISMARINE_QUARTZ = REGISTRATE.item("prismarine_quartz", Item::new)
                .recipe((c, p) -> {
                    // Mirrors Create's rose_quartz crafting (1 c:gems/quartz + 8 redstone),
                    // substituting prismarine shards for the mod's own prismarine identity.
                    // Saved under crafting/materials like Create's own rose_quartz.json.
                    ShapelessRecipeBuilder.shapeless(RecipeCategory.MISC, c.get())
                            .requires(Tags.Items.GEMS_QUARTZ)
                            .requires(Items.PRISMARINE_SHARD)
                            .requires(Items.PRISMARINE_SHARD)
                            .requires(Items.PRISMARINE_SHARD)
                            .requires(Items.PRISMARINE_SHARD)
                            .requires(Items.PRISMARINE_SHARD)
                            .requires(Items.PRISMARINE_SHARD)
                            .requires(Items.PRISMARINE_SHARD)
                            .requires(Items.PRISMARINE_SHARD)
                            .unlockedBy("has_prismarine_shard",
                                    RegistrateRecipeProvider.has(Items.PRISMARINE_SHARD))
                            .save(p, CreateManaIndustry.modLoc("crafting/materials/prismarine_quartz"));
                })
                .register();

    // Deposited metal sheets — produced by vapor deposition: a mist deposits
    // amethyst or rose quartz onto a Create metal sheet. Core items, always
    // registered.
    public static final ItemEntry<Item> AMETHYST_DEPOSITED_BRASS_SHEET = depositedSheet("amethyst_deposited_brass_sheet");
    public static final ItemEntry<Item> AMETHYST_DEPOSITED_COPPER_SHEET = depositedSheet("amethyst_deposited_copper_sheet");
    public static final ItemEntry<Item> AMETHYST_DEPOSITED_GOLDEN_SHEET = depositedSheet("amethyst_deposited_golden_sheet");
    public static final ItemEntry<Item> AMETHYST_DEPOSITED_IRON_SHEET = depositedSheet("amethyst_deposited_iron_sheet");
    public static final ItemEntry<Item> ROSE_QUARTZ_DEPOSITED_BRASS_SHEET = depositedSheet("rose_quartz_deposited_brass_sheet");
    public static final ItemEntry<Item> ROSE_QUARTZ_DEPOSITED_COPPER_SHEET = depositedSheet("rose_quartz_deposited_copper_sheet");
    public static final ItemEntry<Item> ROSE_QUARTZ_DEPOSITED_GOLDEN_SHEET = depositedSheet("rose_quartz_deposited_golden_sheet");
    public static final ItemEntry<Item> ROSE_QUARTZ_DEPOSITED_IRON_SHEET = depositedSheet("rose_quartz_deposited_iron_sheet");

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
                    p -> new IncompleteHexItem(p, () -> ServerConfig.cypherMaxMedia, hexLoc("cypher")))
                    .properties(p -> p.stacksTo(1))
                    .model(NonNullBiConsumer.noop())
                    .register();
            INCOMPLETE_TRINKET = REGISTRATE.item("incomplete_trinket",
                    p -> new IncompleteHexItem(p, () -> ServerConfig.trinketMaxMedia, hexLoc("trinket")))
                    .properties(p -> p.stacksTo(1))
                    .model(NonNullBiConsumer.noop())
                    .register();
            INCOMPLETE_ARTIFACT = REGISTRATE.item("incomplete_artifact",
                    p -> new IncompleteHexItem(p, () -> ServerConfig.artifactMaxMedia, hexLoc("artifact")))
                    .properties(p -> p.stacksTo(1))
                    .model(NonNullBiConsumer.noop())
                    .register();
            INCOMPLETE_MEDIA_BATTERY = REGISTRATE.item("incomplete_media_battery",
                    p -> new IncompleteMediaBatteryItem(p, () -> ServerConfig.batteryMaxMedia, hexLoc("battery")))
                    .properties(p -> p.stacksTo(1))
                    .model(NonNullBiConsumer.noop())
                    .register();
        }
    }

    private static ItemEntry<IncompleteKnotItem> incompleteKnot(String name) {
        return REGISTRATE.item(name, IncompleteKnotItem::new)
                .properties(p -> p.stacksTo(1))
                .model(NonNullBiConsumer.noop())
                .register();
    }

    /** Plain deposited-sheet item — default generated model references the ready texture. */
    private static ItemEntry<Item> depositedSheet(String name) {
        return REGISTRATE.item(name, Item::new).register();
    }

    private static ResourceLocation hexLoc(String path) {
        return ResourceLocation.fromNamespaceAndPath("hexcasting", path);
    }
}
