package com.iridium126.createmanaindustry;

import static com.iridium126.createmanaindustry.CreateManaIndustry.REGISTRATE;
import static com.simibubi.create.api.behaviour.movement.MovementBehaviour.movementBehaviour;

import com.iridium126.createmanaindustry.content.burner.AllayBurnerBlock;
import com.iridium126.createmanaindustry.content.burner.AllayBurnerBlockItem;
import com.iridium126.createmanaindustry.content.burner.AllayBurnerMovementBehaviour;
import com.iridium126.createmanaindustry.content.fluids.condenser.CondenserBlock;
import com.iridium126.createmanaindustry.content.kinetics.kineticatomizer.KineticAtomizerBlock;
import com.iridium126.createmanaindustry.content.kinetics.kineticmanagenerator.KineticManaGeneratorBlock;
import com.iridium126.createmanaindustry.content.kinetics.manacogwheel.EncasedManaCogwheelBlock;
import com.iridium126.createmanaindustry.content.kinetics.manacogwheel.ManaCogwheelBlock;
import com.iridium126.createmanaindustry.content.kinetics.manacogwheel.ManaCogwheelBlockItem;
import com.simibubi.create.AllBlocks;
import com.simibubi.create.AllItems;
import com.simibubi.create.content.decoration.encasing.EncasingRegistry;
import com.simibubi.create.content.kinetics.simpleRelays.BracketedKineticBlockModel;
import com.simibubi.create.foundation.data.BlockStateGen;
import com.simibubi.create.foundation.data.CreateRegistrate;
import com.simibubi.create.foundation.data.ModelGen;
import com.simibubi.create.foundation.data.TagGen;
import com.iridium126.createmanaindustry.config.CMIStress;
import com.tterrag.registrate.providers.RegistrateRecipeProvider;
import com.tterrag.registrate.util.entry.BlockEntry;
import com.tterrag.registrate.util.entry.ItemEntry;
import com.tterrag.registrate.util.nullness.NonNullBiConsumer;

import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.material.MapColor;
import net.minecraft.data.recipes.RecipeCategory;
import net.minecraft.data.recipes.ShapedRecipeBuilder;
import net.minecraft.data.recipes.ShapelessRecipeBuilder;

public final class CMIBlocks {
    public static BlockEntry<KineticManaGeneratorBlock> KINETIC_MANA_GENERATOR;

    public static final BlockEntry<AllayBurnerBlock> ALLAY_BURNER = REGISTRATE
            .block("allay_burner", AllayBurnerBlock::new)
            .initialProperties(() -> Blocks.IRON_BLOCK)
            .properties(p -> p.noOcclusion()
                    .mapColor(MapColor.COLOR_LIGHT_BLUE)
                    .lightLevel(AllayBurnerBlock::getLight))
            .transform(TagGen.pickaxeOnly())
            .blockstate((c, p) -> {})
            .loot((lt, block) -> lt.add(block, AllayBurnerBlock.buildLootTable()))
            .onRegister(movementBehaviour(new AllayBurnerMovementBehaviour()))
            .item(AllayBurnerBlockItem::withAllay)
            .model(NonNullBiConsumer.noop())
            .build()
            .register();

    public static final ItemEntry<AllayBurnerBlockItem> EMPTY_ALLAY_BURNER = REGISTRATE
            .item("empty_allay_burner", p -> AllayBurnerBlockItem.empty(CMIBlocks.ALLAY_BURNER.get(), p))
            .model(NonNullBiConsumer.noop())
            .recipe((c, p) -> {
                ShapedRecipeBuilder.shaped(RecipeCategory.MISC, c.get())
                        .define('S', AllItems.IRON_SHEET)
                        .define('A', Blocks.AMETHYST_BLOCK.asItem())
                        .pattern(" S ")
                        .pattern("SAS")
                        .pattern(" S ")
                        .unlockedBy("has_amethyst_block", RegistrateRecipeProvider.has(Blocks.AMETHYST_BLOCK.asItem()))
                        .save(p, CreateManaIndustry.modLoc(c.getName()));
            })
            .register();

    public static final BlockEntry<KineticAtomizerBlock> KINETIC_ATOMIZER = REGISTRATE
            .block("kinetic_atomizer", KineticAtomizerBlock::new)
            .initialProperties(() -> Blocks.IRON_BLOCK)
            .properties(p -> p.noOcclusion()
                .mapColor(MapColor.COLOR_YELLOW))
            .blockstate(BlockStateGen.directionalBlockProvider(true))
            .transform(TagGen.pickaxeOnly())
            .transform(CMIStress.setImpact(4.0))
            .item()
            .transform(ModelGen.customItemModel())
            .recipe((c, p) -> {
                ShapedRecipeBuilder.shaped(RecipeCategory.MISC, c.get())
                        .define('I', Items.COPPER_INGOT)
                        .define('L', CMIFluids.LIQUID_MANA.getBucket().get())
                        .define('C', AllBlocks.COGWHEEL.asItem())
                        .define('B', AllItems.BRASS_INGOT)
                        .define('P', AllBlocks.FLUID_PIPE.asItem())
                        .pattern(" I ")
                        .pattern("LCL")
                        .pattern("BPB")
                        .unlockedBy("has_liquid_mana", RegistrateRecipeProvider.has(CMIFluids.LIQUID_MANA.getBucket().get()))
                        .save(p, CreateManaIndustry.modLoc(c.getName()));
            })
            .register();

    public static final BlockEntry<CondenserBlock> CONDENSER = REGISTRATE
            .block("condenser", CondenserBlock::new)
            .initialProperties(() -> Blocks.IRON_BLOCK)
            .properties(p -> p.mapColor(MapColor.COLOR_ORANGE))
            .blockstate(BlockStateGen.axisBlockProvider(true))
            .transform(TagGen.pickaxeOnly())
            .item()
            .transform(ModelGen.customItemModel())
            .recipe((c, p) -> {
                ShapedRecipeBuilder.shaped(RecipeCategory.MISC, c.get())
                        .define('S', AllItems.COPPER_SHEET)
                        .define('P', AllBlocks.FLUID_PIPE.asItem())
                        .pattern("SSS")
                        .pattern("SPS")
                        .pattern("SSS")
                        .unlockedBy("has_pipe", RegistrateRecipeProvider.has(AllBlocks.FLUID_PIPE.asItem()))
                        .save(p, CreateManaIndustry.modLoc(c.getName()));
            })
            .register();

    public static final BlockEntry<ManaCogwheelBlock> MANA_COGWHEEL = REGISTRATE
            .block("mana_cogwheel", ManaCogwheelBlock::new)
            .initialProperties(() -> Blocks.IRON_BLOCK)
            .properties(p -> p.mapColor(MapColor.COLOR_LIGHT_BLUE))
            .transform(CMIStress.setCapacity(8.0))
            .transform(TagGen.axeOrPickaxe())
            .blockstate(BlockStateGen.axisBlockProvider(false))
            .onRegister(CreateRegistrate.blockModel(() -> BracketedKineticBlockModel::new))
            .item(ManaCogwheelBlockItem::new)
            .recipe((c, p) -> {
				ShapelessRecipeBuilder.shapeless(RecipeCategory.MISC, c.get())
					.requires(AllBlocks.COGWHEEL.asItem())
					.requires(CMIFluids.LIQUID_MANA.getBucket().get())
					.unlockedBy("has_liquid_mana", RegistrateRecipeProvider.has(CMIFluids.LIQUID_MANA.getBucket().get()))
					.save(p, CreateManaIndustry.modLoc(c.getName()));
			})
            .build()
            .register();

    public static final BlockEntry<EncasedManaCogwheelBlock> ANDESITE_ENCASED_MANA_COGWHEEL = REGISTRATE
            .block("andesite_encased_mana_cogwheel",
                    p -> new EncasedManaCogwheelBlock(p, AllBlocks.ANDESITE_CASING::get))
            .initialProperties(() -> Blocks.IRON_BLOCK)
            .properties(p -> p.noOcclusion().mapColor(MapColor.PODZOL))
            .blockstate((c, p) -> {})
            .transform(CMIStress.setNoImpact())
            .transform(EncasingRegistry.addVariantTo(CMIBlocks.MANA_COGWHEEL))
            .loot((p, lb) -> p.dropOther(lb, CMIBlocks.MANA_COGWHEEL.get()))
            .transform(TagGen.axeOrPickaxe())
            .register();

    public static final BlockEntry<EncasedManaCogwheelBlock> BRASS_ENCASED_MANA_COGWHEEL = REGISTRATE
            .block("brass_encased_mana_cogwheel",
                    p -> new EncasedManaCogwheelBlock(p, AllBlocks.BRASS_CASING::get))
            .initialProperties(() -> Blocks.IRON_BLOCK)
            .properties(p -> p.noOcclusion().mapColor(MapColor.TERRACOTTA_BROWN))
            .blockstate((c, p) -> {})
            .transform(CMIStress.setNoImpact())
            .transform(EncasingRegistry.addVariantTo(CMIBlocks.MANA_COGWHEEL))
            .loot((p, lb) -> p.dropOther(lb, CMIBlocks.MANA_COGWHEEL.get()))
            .transform(TagGen.axeOrPickaxe())
            .register();

    private CMIBlocks() {
    }

    public static void register() {
        if (CreateManaIndustry.TRICKSTER_ACTIVE) {
            KINETIC_MANA_GENERATOR = REGISTRATE
                    .block("kinetic_mana_generator", KineticManaGeneratorBlock::new)
                    .initialProperties(() -> Blocks.IRON_BLOCK)
                    .properties(p -> p.mapColor(MapColor.COLOR_YELLOW))
                    .blockstate(BlockStateGen.directionalBlockProvider(true))
                    .transform(TagGen.pickaxeOnly())
                    .item()
                    .transform(ModelGen.customItemModel())
                    .recipe((c, p) -> {
                        ShapedRecipeBuilder.shaped(RecipeCategory.MISC, c.get())
                                .define('A', Items.AMETHYST_BLOCK)
                                .define('L', CMIFluids.LIQUID_MANA.getBucket().get())
                                .define('C', AllBlocks.COGWHEEL.asItem())
                                .define('B', AllBlocks.BRASS_BLOCK.asItem())
                                .pattern("AAA")
                                .pattern("LCL")
                                .pattern("BBB")
                                .unlockedBy("has_liquid_mana", RegistrateRecipeProvider.has(CMIFluids.LIQUID_MANA.getBucket().get()))
                                .save(p, CreateManaIndustry.modLoc(c.getName()));
                    })
                    .register();
        }
    }
}
