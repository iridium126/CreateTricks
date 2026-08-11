package com.iridium126.createmanaindustry;

import static com.iridium126.createmanaindustry.CreateManaIndustry.REGISTRATE;

import java.util.function.Consumer;

import com.iridium126.createmanaindustry.content.fluids.MoltenRoseQuartzFluid;
import com.iridium126.createmanaindustry.content.fluids.MoltenRoseQuartzFluidType;
import com.tterrag.registrate.util.entry.FluidEntry;
import com.tterrag.registrate.util.nullness.NonNullBiConsumer;

import net.minecraft.core.BlockPos;
import net.minecraft.core.dispenser.BlockSource;
import net.minecraft.core.dispenser.DefaultDispenseItemBehavior;
import net.minecraft.core.dispenser.DispenseItemBehavior;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.world.item.BucketItem;
import net.minecraft.world.item.DispensibleContainerItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.DispenserBlock;
import net.minecraft.world.level.material.MapColor;
import net.minecraft.world.level.pathfinder.PathType;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.fml.LogicalSide;
import net.neoforged.fml.loading.FMLEnvironment;
import net.neoforged.fml.util.thread.EffectiveSide;
import net.neoforged.neoforge.client.extensions.common.IClientFluidTypeExtensions;
import net.neoforged.neoforge.common.SoundActions;
import net.neoforged.neoforge.common.Tags;
import net.neoforged.neoforge.fluids.BaseFlowingFluid;
import net.neoforged.neoforge.fluids.FluidStack;
import net.neoforged.neoforge.fluids.FluidType;

public class CMIFluids {
    public static final FluidEntry<BaseFlowingFluid.Flowing> LIQUID_MANA =
            REGISTRATE.standardFluid("liquid_mana", ClientFullBrightFluidType::new)
                    .properties(b -> b.viscosity(1000).density(1000))
                    .fluidProperties(p -> p.levelDecreasePerBlock(1)
                            .tickRate(5)
                            .slopeFindDistance(4)
                            .explosionResistance(100f))
                    .source(BaseFlowingFluid.Source::new)
                    .block()
                    .properties(p -> p.mapColor(MapColor.COLOR_LIGHT_BLUE))
                    // Let the rendered fluid stay full-bright without making the placed liquid emit world light.
                    .properties(p -> p.lightLevel($ -> 0))
                    // The emissiveRendering flag makes Sodium and the
                    // shader lightmap treat the fluid as full-bright, while
                    // remaining purely a render flag (no world light emission).
                    .properties(p -> p.emissiveRendering((state, level, pos) -> true))
                    .build()
                    .bucket()
                    .model(NonNullBiConsumer.noop())
                    .onRegister(CMIFluids::registerFluidDispenseBehavior)
                    .tag(Tags.Items.BUCKETS)
                    .build()
                    .register();

    public static final FluidEntry<BaseFlowingFluid.Flowing> LIQUID_MEDIA =
            REGISTRATE.standardFluid("liquid_media")
                    .properties(b -> b.viscosity(1000).density(1000).lightLevel(10))
                    .fluidProperties(p -> p.levelDecreasePerBlock(1)
                            .tickRate(5)
                            .slopeFindDistance(4)
                            .explosionResistance(100f))
                    .source(BaseFlowingFluid.Source::new)
                    .block()
                    .properties(p -> p.mapColor(MapColor.COLOR_PINK))
                    .build()
                    .bucket()
                    .model(NonNullBiConsumer.noop())
                    .onRegister(CMIFluids::registerFluidDispenseBehavior)
                    .tag(Tags.Items.BUCKETS)
                    .build()
                    .register();

    public static final FluidEntry<BaseFlowingFluid.Flowing> LIQUID_SOURCE =
            REGISTRATE.standardFluid("liquid_source")
                    .properties(b -> b.viscosity(1000).density(1000))
                    .fluidProperties(p -> p.levelDecreasePerBlock(1)
                            .tickRate(5)
                            .slopeFindDistance(4)
                            .explosionResistance(100f))
                    .source(BaseFlowingFluid.Source::new)
                    .block()
                    .properties(p -> p.mapColor(MapColor.COLOR_PURPLE))
                    .build()
                    .bucket()
                    .onRegister(CMIFluids::registerFluidDispenseBehavior)
                    .tag(Tags.Items.BUCKETS)
                    .build()
                    .register();

    public static final FluidEntry<BaseFlowingFluid.Flowing> LIQUID_SOUL =
            REGISTRATE.standardFluid("liquid_soul")
                    .properties(b -> b.viscosity(1000).density(1000).lightLevel(15))
                    .fluidProperties(p -> p.levelDecreasePerBlock(1)
                            .tickRate(5)
                            .slopeFindDistance(4)
                            .explosionResistance(100f))
                    .source(BaseFlowingFluid.Source::new)
                    .block()
                    .properties(p -> p.mapColor(MapColor.COLOR_LIGHT_BLUE))
                    .build()
                    .bucket()
                    .model(NonNullBiConsumer.noop())
                    .onRegister(CMIFluids::registerFluidDispenseBehavior)
                    .tag(Tags.Items.BUCKETS)
                    .build()
                    .register();

    public static final FluidEntry<BaseFlowingFluid.Flowing> COOLANT =
            REGISTRATE.standardFluid("coolant")
                    .properties(b -> b.viscosity(1000).density(1000))
                    .fluidProperties(p -> p.levelDecreasePerBlock(1)
                            .tickRate(5)
                            .slopeFindDistance(4)
                            .explosionResistance(100f))
                    .source(BaseFlowingFluid.Source::new)
                    .block()
                    .properties(p -> p.mapColor(MapColor.ICE))
                    .build()
                    .bucket()
                    .onRegister(CMIFluids::registerFluidDispenseBehavior)
                    .tag(Tags.Items.BUCKETS)
                    .build()
                    .register();

    /**
     * Molten Rose Quartz — strictly mirrors lava: emissive block (light 15),
     * lava particle/sound ambience, dense viscous movement, player damage via
     * {@code EntityMoltenRoseQuartzMixin}. Carved-out exceptions (no fire spread, no
     * water→stone, no infinite source) live in {@link MoltenRoseQuartzFluid}.
     */
    public static final FluidEntry<MoltenRoseQuartzFluid.Flowing> MOLTEN_ROSE_QUARTZ =
            REGISTRATE.fluid("molten_rose_quartz",
                    CreateManaIndustry.modLoc("fluid/molten_rose_quartz_still"),
                    CreateManaIndustry.modLoc("fluid/molten_rose_quartz_flow"),
                    MoltenRoseQuartzFluidType::new,
                    MoltenRoseQuartzFluid.Flowing::new)
                    .properties(b -> b.lightLevel(15).density(3000).viscosity(6000).temperature(1300)
                            .canSwim(false).canDrown(false)
                            .pathType(PathType.LAVA)
                            .sound(SoundActions.BUCKET_FILL, SoundEvents.BUCKET_FILL_LAVA)
                            .sound(SoundActions.BUCKET_EMPTY, SoundEvents.BUCKET_EMPTY_LAVA))
                    .fluidProperties(p -> p.levelDecreasePerBlock(2).explosionResistance(100f))
                    .source(MoltenRoseQuartzFluid.Source::new)
                    .block()
                    .properties(p -> p.mapColor(MapColor.COLOR_PINK))
                    .build()
                    .bucket()
                    .model(NonNullBiConsumer.noop())
                    .onRegister(CMIFluids::registerFluidDispenseBehavior)
                    .tag(Tags.Items.BUCKETS)
                    .build()
                    .register();

    public static void register() {}

    private static final DispenseItemBehavior DISPENSE_FLUID = new DefaultDispenseItemBehavior() {
        @Override
        protected ItemStack execute(BlockSource pSource, ItemStack pStack) {
            DispensibleContainerItem dispensibleContainerItem = (DispensibleContainerItem) pStack.getItem();
            BlockPos pos = pSource.pos().relative(pSource.state().getValue(DispenserBlock.FACING));
            Level level = pSource.level();
            if (dispensibleContainerItem.emptyContents(null, level, pos, null, pStack)) {
                return new ItemStack(Items.BUCKET);
            }
            return super.execute(pSource, pStack);
        }
    };

    private static void registerFluidDispenseBehavior(BucketItem bucket) {
        DispenserBlock.registerBehavior(bucket, DISPENSE_FLUID);
    }

    private static class ClientFullBrightFluidType extends FluidType {
        private static final int FULL_BRIGHT_LIGHT_LEVEL = 15;
        private final ResourceLocation stillTexture;
        private final ResourceLocation flowingTexture;

        public ClientFullBrightFluidType(Properties properties, ResourceLocation stillTexture,
                ResourceLocation flowingTexture) {
            super(properties);
            this.stillTexture = stillTexture;
            this.flowingTexture = flowingTexture;
        }

        @Override
        public void initializeClient(Consumer<IClientFluidTypeExtensions> consumer) {
            consumer.accept(new IClientFluidTypeExtensions() {
                @Override
                public ResourceLocation getStillTexture() {
                    return stillTexture;
                }

                @Override
                public ResourceLocation getFlowingTexture() {
                    return flowingTexture;
                }
            });
        }

        @Override
        public int getLightLevel() {
            return shouldRenderFullBright() ? FULL_BRIGHT_LIGHT_LEVEL : 0;
        }

        @Override
        public int getLightLevel(FluidStack stack) {
            return getLightLevel();
        }

        private static boolean shouldRenderFullBright() {
            return FMLEnvironment.dist == Dist.CLIENT && EffectiveSide.get() == LogicalSide.CLIENT;
        }
    }
}
