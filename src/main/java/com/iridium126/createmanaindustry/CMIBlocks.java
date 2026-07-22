package com.iridium126.createmanaindustry;

import static com.iridium126.createmanaindustry.CreateManaIndustry.REGISTRATE;

import com.iridium126.createmanaindustry.content.fluids.condenser.CondenserBlock;
import com.iridium126.createmanaindustry.content.kinetics.kineticatomizer.KineticAtomizerBlock;
import com.iridium126.createmanaindustry.content.kinetics.kineticmanagenerator.KineticManaGeneratorBlock;
import com.simibubi.create.AllBlocks;
import com.simibubi.create.AllItems;
import com.simibubi.create.foundation.data.BlockStateGen;
import com.simibubi.create.foundation.data.ModelGen;
import com.simibubi.create.foundation.data.TagGen;
import com.iridium126.createmanaindustry.config.CMIStress;
import com.tterrag.registrate.providers.RegistrateRecipeProvider;
import com.tterrag.registrate.util.entry.BlockEntry;

import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.material.MapColor;
import net.minecraft.data.recipes.RecipeCategory;
import net.minecraft.data.recipes.ShapedRecipeBuilder;

public final class CMIBlocks {
    public static BlockEntry<KineticManaGeneratorBlock> KINETIC_MANA_GENERATOR;

    public static final BlockEntry<KineticAtomizerBlock> KINETIC_ATOMIZER = REGISTRATE
            .block("kinetic_atomizer", KineticAtomizerBlock::new)
            .initialProperties(() -> Blocks.IRON_BLOCK)
            .properties(p -> p.noOcclusion()
                .mapColor(MapColor.TERRACOTTA_YELLOW))
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
            .transform(TagGen.pickaxeOnly())
            .item()
            .transform(ModelGen.customItemModel())
            .recipe((c, p) -> {
                ShapedRecipeBuilder.shaped(RecipeCategory.MISC, c.get())
                        .define('I', Items.COPPER_INGOT)
                        .define('P', AllBlocks.FLUID_PIPE.asItem())
                        .define('C', Items.COPPER_BLOCK)
                        .pattern(" I ")
                        .pattern("PCP")
                        .pattern(" I ")
                        .unlockedBy("has_copper", RegistrateRecipeProvider.has(Items.COPPER_INGOT))
                        .save(p, CreateManaIndustry.modLoc(c.getName()));
            })
            .register();

    private CMIBlocks() {
    }

    public static void register() {
        if (CreateManaIndustry.TRICKSTER_ACTIVE) {
            KINETIC_MANA_GENERATOR = REGISTRATE
                    .block("kinetic_mana_generator", KineticManaGeneratorBlock::new)
                    .initialProperties(() -> Blocks.IRON_BLOCK)
                    .properties(p -> p.mapColor(MapColor.TERRACOTTA_YELLOW))
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
