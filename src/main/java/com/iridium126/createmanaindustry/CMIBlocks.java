package com.iridium126.createmanaindustry;

import static com.iridium126.createmanaindustry.CreateManaIndustry.REGISTRATE;
import static com.simibubi.create.api.behaviour.movement.MovementBehaviour.movementBehaviour;
import static com.simibubi.create.api.contraption.storage.fluid.MountedFluidStorageType.mountedFluidStorage;

import com.iridium126.createmanaindustry.content.burner.AllayBurnerBlock;
import com.iridium126.createmanaindustry.content.burner.AllayBurnerBlockItem;
import com.iridium126.createmanaindustry.content.burner.AllayBurnerMovementBehaviour;
import com.iridium126.createmanaindustry.content.fluids.condenser.CondenserBlock;
import com.iridium126.createmanaindustry.content.fluids.condenser.WeatheringCondenserBlock;
import com.iridium126.createmanaindustry.content.fluids.fueltank.FuelTankBlock;
import com.iridium126.createmanaindustry.content.fluids.fueltank.FuelTankModel;
import com.iridium126.createmanaindustry.content.fluids.fueltank.FuelTankMovementBehavior;
import com.iridium126.createmanaindustry.content.kinetics.depositionlid.DepositionLidBlock;
import com.iridium126.createmanaindustry.content.kinetics.depositionlid.DepositionLidCTBehaviour;
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
import com.simibubi.create.foundation.data.SharedProperties;
import com.simibubi.create.foundation.data.TagGen;
import com.iridium126.createmanaindustry.config.ServerConfig;
import com.tterrag.registrate.providers.RegistrateRecipeProvider;
import com.tterrag.registrate.util.DataIngredient;
import com.tterrag.registrate.util.entry.BlockEntry;
import com.tterrag.registrate.util.entry.ItemEntry;
import com.tterrag.registrate.util.nullness.NonNullBiConsumer;

import net.minecraft.client.renderer.RenderType;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.RotatedPillarBlock;
import net.minecraft.world.level.block.SoundType;
import net.minecraft.world.level.block.WeatheringCopper;
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
                        .define('S', CMIItems.AMETHYST_DEPOSITED_IRON_SHEET)
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
            .transform(ServerConfig.setImpact(4.0))
            .item()
            .transform(ModelGen.customItemModel())
            .recipe((c, p) -> {
                ShapedRecipeBuilder.shaped(RecipeCategory.MISC, c.get())
                        .define('S', AllItems.COPPER_SHEET)
                        .define('L', CMIFluids.LIQUID_MANA.getBucket().get())
                        .define('C', AllBlocks.COGWHEEL.asItem())
                        .define('B', AllBlocks.BRASS_BLOCK.asItem())
                        .define('P', AllBlocks.FLUID_PIPE.asItem())
                        .pattern(" S ")
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

    // Condenser weathering variants — mirror Minecraft's copper blocks: three
    // oxidizing stages (WeatheringCopper + OXIDIZABLES data map) and four
    // waxed variants (plain blocks linked via the WAXABLES data map). No
    // recipes, like vanilla; models are empty shells parenting the base
    // condenser model until textures are replaced.

    public static final BlockEntry<WeatheringCondenserBlock> EXPOSED_CONDENSER =
            weatheringCondenser("exposed_condenser", WeatheringCopper.WeatherState.EXPOSED);

    public static final BlockEntry<WeatheringCondenserBlock> WEATHERED_CONDENSER =
            weatheringCondenser("weathered_condenser", WeatheringCopper.WeatherState.WEATHERED);

    public static final BlockEntry<WeatheringCondenserBlock> OXIDIZED_CONDENSER =
            weatheringCondenser("oxidized_condenser", WeatheringCopper.WeatherState.OXIDIZED);

    public static final BlockEntry<CondenserBlock> WAXED_CONDENSER =
            waxedCondenser("waxed_condenser");

    public static final BlockEntry<CondenserBlock> WAXED_EXPOSED_CONDENSER =
            waxedCondenser("waxed_exposed_condenser");

    public static final BlockEntry<CondenserBlock> WAXED_WEATHERED_CONDENSER =
            waxedCondenser("waxed_weathered_condenser");

    public static final BlockEntry<CondenserBlock> WAXED_OXIDIZED_CONDENSER =
            waxedCondenser("waxed_oxidized_condenser");

    /**
     * Molten Salt Fuel Tank — a multi-block fluid storage that connects in any
     * shape (no Create box constraint). Mirrors Create's fluid tank visually
     * (copied window models), with a custom connectivity/basin simulation and
     * merged liquid rendering. Window is always open; no wrench toggle.
     */
    public static final BlockEntry<FuelTankBlock> MOLTEN_SALT_FUEL_TANK = REGISTRATE
            .block("molten_salt_fuel_tank", FuelTankBlock::new)
            .initialProperties(SharedProperties::copperMetal)
            .properties(p -> p.noOcclusion()
                    .isRedstoneConductor((p1, p2, p3) -> true))
            .transform(TagGen.pickaxeOnly())
            // Unified six-face model is hand-written; datagen emits the
            // single-variant blockstate referencing it (mirrors Create's
            // `simpleBlock` + `getExistingFile` pattern).
            .blockstate((c, p) -> p.simpleBlock(c.get(), p.models()
                    .getExistingFile(p.modLoc("block/molten_salt_fuel_tank/block"))))
            .onRegister(CreateRegistrate.blockModel(() -> FuelTankModel::new))
            .transform(mountedFluidStorage(CMIMountedStorageTypes.MOLTEN_SALT_FUEL_TANK))
            .onRegister(movementBehaviour(new FuelTankMovementBehavior()))
            .item()
            // Datagen-generated item model parenting the unified block model.
            .model((c, p) -> p.withExistingParent(c.getName(),
                    p.modLoc("block/molten_salt_fuel_tank/block")))
            .build()
            .register();

    /**
     * Block of Prismarine Quartz — strictly mirrors Create's Rose Quartz Block
     * ({@link RotatedPillarBlock}, amethyst-baseline hardness, correct-tool
     * drops, deepslate sound, pickaxe-only, axis blockstate, stonecutting 2→1),
     * with only the map color changed to the prismarine cyan family.
     */
    public static final BlockEntry<RotatedPillarBlock> PRISMARINE_QUARTZ_BLOCK = REGISTRATE
            .block("prismarine_quartz_block", RotatedPillarBlock::new)
            .initialProperties(() -> Blocks.AMETHYST_BLOCK)
            .properties(p -> p.mapColor(MapColor.COLOR_CYAN)
                    .requiresCorrectToolForDrops()
                    .sound(SoundType.DEEPSLATE))
            .transform(TagGen.pickaxeOnly())
            .blockstate((c, p) -> p.axisBlock(c.get(),
                    p.modLoc("block/prismarine_quartz_side"),
                    p.modLoc("block/prismarine_quartz_top")))
            .recipe((c, p) -> p.stonecutting(DataIngredient.items(CMIItems.PRISMARINE_QUARTZ.get()),
                    RecipeCategory.BUILDING_BLOCKS, c::get, 2))
            .simpleItem()
            .lang("Block of Prismarine Quartz")
            .register();

    /**
     * Deposition lid — visually and behaviourally identical to
     * {@code create:framed_glass_trapdoor}, but carrying a
     * {@code DepositionLidBlockEntity} so a sealed basin can run
     * {@code vapor_deposition} recipes.
     * <p>
     * It has no item (a {@code TrainTrapdoorBlockMixin} swap creates it in the
     * world) and drops the framed glass trapdoor when mined, so the conversion
     * is invisible to the player. The blockstate JSON is hand-written in
     * {@code src/main/resources} and points at Create's own trapdoor models.
     */
    public static final BlockEntry<DepositionLidBlock> DEPOSITION_LID = depositionLid();

    private static BlockEntry<WeatheringCondenserBlock> weatheringCondenser(String name,
            WeatheringCopper.WeatherState state) {
        return REGISTRATE.block(name, p -> new WeatheringCondenserBlock(state, p))
                .initialProperties(() -> Blocks.IRON_BLOCK)
                .properties(p -> p.mapColor(MapColor.COLOR_ORANGE))
                .blockstate(BlockStateGen.axisBlockProvider(true))
                .transform(TagGen.pickaxeOnly())
                .loot((p, lb) -> p.dropSelf(lb))
                .item()
                .transform(ModelGen.customItemModel())
                .register();
    }

    private static BlockEntry<CondenserBlock> waxedCondenser(String name) {
        return REGISTRATE.block(name, CondenserBlock::new)
                .initialProperties(() -> Blocks.IRON_BLOCK)
                .properties(p -> p.mapColor(MapColor.COLOR_ORANGE))
                .blockstate(BlockStateGen.axisBlockProvider(true))
                .transform(TagGen.pickaxeOnly())
                .loot((p, lb) -> p.dropSelf(lb))
                .item()
                .transform(ModelGen.customItemModel())
                .register();
    }

    /**
     * Registers the deposition lid without an item. The blockstate is not
     * generated (hand-written in {@code src/main/resources}, mirroring the
     * framed glass trapdoor and referencing Create's models); the loot table is
     * generated to drop a framed glass trapdoor item directly, so the conversion
     * is invisible to the player.
     */
    @SuppressWarnings("removal") // addLayer is deprecated but still the render-layer hook
    private static BlockEntry<DepositionLidBlock> depositionLid() {
        return REGISTRATE.block("deposition_lid", DepositionLidBlock::new)
                .initialProperties(SharedProperties::softMetal)
                .properties(p -> p.mapColor(MapColor.NONE)
                        .noOcclusion())
                .addLayer(() -> RenderType::cutoutMipped)
                .onRegister(CreateRegistrate.connectedTextures(() -> new DepositionLidCTBehaviour()))
                .blockstate((c, p) -> {})
                .loot((p, lb) -> p.dropOther(lb, AllBlocks.FRAMED_GLASS_TRAPDOOR.get()))
                .transform(TagGen.pickaxeOnly())
                .tag(BlockTags.TRAPDOORS)
                .register();
    }

    public static final BlockEntry<ManaCogwheelBlock> MANA_COGWHEEL = REGISTRATE
            .block("mana_cogwheel", ManaCogwheelBlock::new)
            .initialProperties(() -> Blocks.IRON_BLOCK)
            .properties(p -> p.mapColor(MapColor.COLOR_LIGHT_BLUE))
            .transform(ServerConfig.setCapacity(8.0))
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
            .transform(ServerConfig.setNoImpact())
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
            .transform(ServerConfig.setNoImpact())
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
