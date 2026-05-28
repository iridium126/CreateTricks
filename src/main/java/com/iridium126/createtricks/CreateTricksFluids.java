package com.iridium126.createtricks;

import static com.iridium126.createtricks.CreateTricks.REGISTRATE;

import java.util.function.Consumer;

import com.tterrag.registrate.util.entry.FluidEntry;

import net.minecraft.core.BlockPos;
import net.minecraft.core.dispenser.BlockSource;
import net.minecraft.core.dispenser.DefaultDispenseItemBehavior;
import net.minecraft.core.dispenser.DispenseItemBehavior;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.BucketItem;
import net.minecraft.world.item.DispensibleContainerItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.BlockAndTintGetter;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.DispenserBlock;
import net.minecraft.world.level.material.FluidState;
import net.minecraft.world.level.material.MapColor;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.fml.LogicalSide;
import net.neoforged.fml.loading.FMLEnvironment;
import net.neoforged.fml.util.thread.EffectiveSide;
import net.neoforged.neoforge.client.extensions.common.IClientFluidTypeExtensions;
import net.neoforged.neoforge.common.Tags;
import net.neoforged.neoforge.fluids.BaseFlowingFluid;
import net.neoforged.neoforge.fluids.FluidStack;
import net.neoforged.neoforge.fluids.FluidType;

public class CreateTricksFluids {
	public static final FluidEntry<BaseFlowingFluid.Flowing> LIQUID_MANA =
			REGISTRATE.standardFluid("liquid_mana", ClientFullBrightFluidType::new)
					.properties(b -> b.viscosity(1000).density(1000))
					.fluidProperties(p -> p.levelDecreasePerBlock(1)
							.tickRate(5)
							.slopeFindDistance(4)
							.explosionResistance(100f))
					.source(BaseFlowingFluid.Source::new)
					.block()
					.properties(p -> p.mapColor(MapColor.COLOR_PURPLE))
					// Let the rendered fluid stay full-bright without making the placed liquid emit world light.
					.properties(p -> p.lightLevel($ -> 0))
					.build()
					.bucket()
					.onRegister(CreateTricksFluids::registerFluidDispenseBehavior)
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
		public int getLightLevel(FluidState state, BlockAndTintGetter getter, BlockPos pos) {
			return getLightLevel();
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
