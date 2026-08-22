package com.iridium126.createmanaindustry.content.fluids.fueltank;

import java.util.HashMap;
import java.util.Map;

import org.jetbrains.annotations.Nullable;

import com.iridium126.createmanaindustry.CMIBlockEntityTypes;
import com.simibubi.create.AllBlocks;
import com.simibubi.create.content.decoration.copycat.CopycatBlock;
import com.simibubi.create.content.decoration.copycat.CopycatBlockEntity;
import com.simibubi.create.content.equipment.wrench.IWrenchable;
import com.simibubi.create.content.fluids.transfer.GenericItemEmptying;
import com.simibubi.create.content.fluids.transfer.GenericItemFilling;
import com.simibubi.create.content.redstone.RoseQuartzLampBlock;
import com.simibubi.create.foundation.blockEntity.ComparatorUtil;
import com.simibubi.create.foundation.blockEntity.SmartBlockEntity;
import com.simibubi.create.foundation.blockEntity.SmartBlockEntityTicker;
import com.simibubi.create.foundation.fluid.FluidHelper;
import com.simibubi.create.foundation.fluid.FluidHelper.FluidExchange;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.particles.BlockParticleOption;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.util.Mth;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.ItemInteractionResult;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.context.UseOnContext;
import net.minecraft.world.level.BlockAndTintGetter;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.LevelEvent;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.BlockEntityTicker;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.StateDefinition.Builder;
import net.minecraft.world.level.block.state.properties.BooleanProperty;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.Shapes;
import net.minecraft.world.phys.shapes.VoxelShape;

import net.neoforged.neoforge.capabilities.Capabilities;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.level.BlockEvent;
import net.neoforged.neoforge.fluids.FluidStack;
import net.neoforged.neoforge.fluids.capability.IFluidHandler;

/**
 * Molten Salt Fuel Tank — a multi-block fluid storage that connects in any
 * shape, and a copycat block mirroring Create's {@code CopycatBlock} (see the
 * interaction contract below).
 * <p>
 * <b>Wrench</b> (deviates from Create's copycat on purpose, keeps the tank's
 * window mechanic): a plain wrench toggles the window panes of the <i>clicked
 * cell only</i> — right-clicking the top face toggles {@link #TOP_OPEN},
 * right-clicking any other face toggles {@link #SIDE_OPEN}. A sneak-wrench
 * strips the custom copycat material (returning the consumed item) when one is
 * applied, and otherwise dismantles the cell (Create's default
 * {@code IWrenchable} behavior).
 * <p>
 * <b>Copycat material</b>: the inherited {@code CopycatBlock} interactions
 * apply — right-click with an accepted block to shell the cell with that
 * material (blockstate rules: full-cube shape only, no block entities, no
 * stairs, {@code copycat_allow}/{@code copycat_deny} tags, direction properties
 * aligned to the hit face), holding a material in the off-hand while placing
 * applies it to the placed cell, and the usual creative mode handling. The
 * material only skins the cell's shell — the windows stay glass and the fluid
 * keeps its own rendering. Material light combines with the fluid light
 * ({@link #getLightEmission}).
 * <p>
 * <b>Fluid</b>: the inherited {@code useItemOn} also keeps Create's tank
 * behavior — in creative, buckets can fill/drain the group through the clicked
 * cell (survival buckets are intentionally a no-op, exactly as in Create's
 * regular tank).
 * <p>
 * The model is a four-variant family ({@code top_open} x {@code side_open}):
 * full-height frame + 8x8 side windows, a 1-unit top ring with a window pane
 * and a 1-unit solid bottom plate. Faces shared with a same-group tank are
 * culled in all six directions by {@link FuelTankModel} (top/bottom included).
 */
public class FuelTankBlock extends CopycatBlock {

	public static final BooleanProperty TOP_OPEN = BooleanProperty.create("top_open");
	public static final BooleanProperty SIDE_OPEN = BooleanProperty.create("side_open");
	/**
	 * The per-cell brightness rule ({@link #refreshLitStates}) materialized as a
	 * blockstate, and the single source of truth for everything that depends
	 * on it: {@link #getLightEmission} (fluid part), the rose quartz lamp
	 * shell's POWERING skin ({@code FuelTankModel#displayMaterial}) and
	 * shader-side colored lights (Photon seeds its LPV from block IDs alone
	 * and never consults the vanilla light level, so an unstored flag would
	 * let a drained cell keep glowing pink).
	 * <p>
	 * Refreshed on fluid changes and after connectivity regrouping, self-healed
	 * by controller lazy ticks; eventually consistent within one lazy-tick
	 * period for paths that bypass both.
	 */
	public static final BooleanProperty LIT = BooleanProperty.create("lit");

	public FuelTankBlock(Properties properties) {
		super(properties);
		registerDefaultState(defaultBlockState().setValue(TOP_OPEN, true).setValue(SIDE_OPEN, true)
			.setValue(LIT, false));
	}

	public static boolean isFuelTank(BlockState state) {
		return state.getBlock() instanceof FuelTankBlock;
	}

	@Override
	protected void createBlockStateDefinition(Builder<Block, BlockState> builder) {
		builder.add(TOP_OPEN, SIDE_OPEN, LIT);
	}

	@Override
	public void onPlace(BlockState state, Level world, BlockPos pos, BlockState oldState, boolean moved) {
		if (oldState.getBlock() == state.getBlock())
			return;
		if (moved)
			return;
		withBlockEntityDo(world, pos, be -> {
			if (be instanceof FuelTankBlockEntity tankBE)
				tankBE.updateConnectivity();
		});

		// updateConnectivity may have changed the in-world block state, which prevents
		// markAndNotifyBlock in CommonHooks#onPlaceItemIntoWorld from doing anything.
		BlockState newState = world.getBlockState(pos);
		if (state != newState && newState.getBlock() == this)
			world.markAndNotifyBlock(pos, world.getChunkAt(pos), oldState, newState, Block.UPDATE_ALL_IMMEDIATE, 512);
	}

	@Override
	public void onRemove(BlockState state, Level world, BlockPos pos, BlockState newState, boolean isMoving) {
		// Copycat drop contract (pop the consumed material item) + the tank's
		// connectivity split and fuel rod re-validation.
		if (state.hasBlockEntity() && (state.getBlock() != newState.getBlock() || !newState.hasBlockEntity())) {
			BlockEntity be = world.getBlockEntity(pos);
			if (!(be instanceof FuelTankBlockEntity tankBE))
				return;
			if (!isMoving)
				Block.popResource(world, pos, tankBE.getConsumedItem());
			world.removeBlockEntity(pos);
			FuelTankConnectivity.split(tankBE);
			// A removed fuel tank can break or re-root a fuel rod; re-validate the
			// structures around the hole from any surviving neighbours.
			if (!world.isClientSide)
				FuelRodStructure.validateFor(world, pos);
		}
	}

	/**
	 * Wrench (non-sneak): toggle the window panes of the clicked cell — top face
	 * toggles the top pane, any other face toggles the side panes. Mirrors
	 * Create's tank wrench conceptually (per-cell instead of whole-group).
	 */
	@Override
	public InteractionResult onWrenched(BlockState state, UseOnContext context) {
		Level level = context.getLevel();
		BlockPos pos = context.getClickedPos();
		BooleanProperty property = context.getClickedFace() == Direction.UP ? TOP_OPEN : SIDE_OPEN;
		level.setBlock(pos, state.cycle(property), UPDATE_CLIENTS | UPDATE_INVISIBLE | UPDATE_KNOWN_SHAPE);
		level.getChunkSource().getLightEngine().checkBlock(pos);
		return InteractionResult.SUCCESS;
	}

	/**
	 * Wrench (sneak): strip the custom material of the clicked cell (returning the
	 * consumed item, mirroring Create's copycat material removal) when present;
	 * otherwise dismantle the cell like Create's default {@code IWrenchable}.
	 */
	@Override
	public InteractionResult onSneakWrenched(BlockState state, UseOnContext context) {
		InteractionResult stripped = onBlockEntityUse(context.getLevel(), context.getClickedPos(), ufte -> {
			ItemStack consumedItem = ufte.getConsumedItem();
			if (!ufte.hasCustomMaterial())
				return InteractionResult.PASS;
			Player player = context.getPlayer();
			if (!player.isCreative())
				player.getInventory().placeItemBackInInventory(consumedItem);
			context.getLevel().levelEvent(LevelEvent.PARTICLES_DESTROY_BLOCK, context.getClickedPos(),
				Block.getId(ufte.getBlockState()));
			ufte.setMaterial(AllBlocks.COPYCAT_BASE.getDefaultState());
			ufte.setConsumedItem(ItemStack.EMPTY);
			return InteractionResult.SUCCESS;
		});
		if (stripped == InteractionResult.SUCCESS)
			return stripped;

		// Dismantle the cell — inlined from IWrenchable's default onSneakWrenched
		// (the interface super-qualifier is unreachable through the CopycatBlock
		// intermediate, whose own onSneakWrenched would re-trigger our window
		// toggle via dynamic dispatch).
		Level world = context.getLevel();
		BlockPos pos = context.getClickedPos();
		Player player = context.getPlayer();

		if (!(world instanceof ServerLevel serverLevel))
			return InteractionResult.SUCCESS;

		BlockEvent.BreakEvent event = new BlockEvent.BreakEvent(world, pos, world.getBlockState(pos), player);
		NeoForge.EVENT_BUS.post(event);
		if (event.isCanceled())
			return InteractionResult.SUCCESS;

		if (player != null && !player.isCreative()) {
			Block.getDrops(state, serverLevel, pos, world.getBlockEntity(pos), player, context.getItemInHand())
				.forEach(itemStack -> player.getInventory()
					.placeItemBackInInventory(itemStack));
		}

		state.spawnAfterBreak(serverLevel, pos, ItemStack.EMPTY, true);
		world.destroyBlock(pos, false);
		IWrenchable.playRemoveSound(world, pos);
		return InteractionResult.SUCCESS;
	}

	@Override
	protected ItemInteractionResult useItemOn(ItemStack stack, BlockState state, Level level, BlockPos pos,
		Player player, InteractionHand hand, BlockHitResult hitResult) {
		if (player == null)
			return ItemInteractionResult.PASS_TO_DEFAULT_BLOCK_INTERACTION;
		if (stack.isEmpty())
			return ItemInteractionResult.PASS_TO_DEFAULT_BLOCK_INTERACTION;

		// ---- copycat material application (mirrors CopycatBlock) ----
		Direction face = hitResult.getDirection();
		BlockState materialIn = getAcceptedBlockState(level, pos, stack, face);
		if (materialIn != null)
			materialIn = prepareMaterial(level, pos, state, player, hand, hitResult, materialIn);
		if (materialIn != null) {
			BlockState material = materialIn;
			return onBlockEntityUseItemOn(level, pos, ufte -> {
				if (ufte.getMaterial().is(material.getBlock())) {
					if (!ufte.cycleMaterial())
						return ItemInteractionResult.PASS_TO_DEFAULT_BLOCK_INTERACTION;
					ufte.getLevel().playSound(null, ufte.getBlockPos(), SoundEvents.ITEM_FRAME_ADD_ITEM,
						SoundSource.BLOCKS, .75f, .95f);
					return ItemInteractionResult.SUCCESS;
				}
				if (ufte.hasCustomMaterial())
					return ItemInteractionResult.PASS_TO_DEFAULT_BLOCK_INTERACTION;
				if (level.isClientSide())
					return ItemInteractionResult.SUCCESS;

				ufte.setMaterial(material);
				ufte.setConsumedItem(stack);
				ufte.getLevel().playSound(null, ufte.getBlockPos(),
					material.getSoundType(ufte.getLevel(), ufte.getBlockPos(), null).getPlaceSound(), SoundSource.BLOCKS, 1,
					.75f);

				if (player.isCreative())
					return ItemInteractionResult.SUCCESS;

				stack.shrink(1);
				if (stack.isEmpty())
					player.setItemInHand(hand, ItemStack.EMPTY);
				return ItemInteractionResult.SUCCESS;
			});
		}

		// ---- tank bucket interaction (mirrors FluidTankBlock; creative-only) ----
		if (!player.isCreative())
			return ItemInteractionResult.PASS_TO_DEFAULT_BLOCK_INTERACTION;

		boolean onClient = level.isClientSide;
		FluidExchange exchange = null;
		FuelTankBlockEntity be = FuelTankConnectivity.partAt(getBlockEntityType(), level, pos);
		if (be == null)
			return ItemInteractionResult.FAIL;

		IFluidHandler tankCapability = level.getCapability(Capabilities.FluidHandler.BLOCK, be.getBlockPos(), null);
		if (tankCapability == null)
			return ItemInteractionResult.PASS_TO_DEFAULT_BLOCK_INTERACTION;
		FluidStack prevFluidInTank = tankCapability.getFluidInTank(0).copy();

		if (FluidHelper.tryEmptyItemIntoBE(level, player, hand, stack, be))
			exchange = FluidExchange.ITEM_TO_TANK;
		else if (FluidHelper.tryFillItemFromBE(level, player, hand, stack, be))
			exchange = FluidExchange.TANK_TO_ITEM;

		if (exchange == null) {
			if (GenericItemEmptying.canItemBeEmptied(level, stack)
				|| GenericItemFilling.canItemBeFilled(level, stack))
				return ItemInteractionResult.SUCCESS;
			return ItemInteractionResult.PASS_TO_DEFAULT_BLOCK_INTERACTION;
		}

		SoundEvent soundevent = null;
		BlockState fluidState = null;
		FluidStack fluidInTank = tankCapability.getFluidInTank(0);

		if (exchange == FluidExchange.ITEM_TO_TANK) {
			net.minecraft.world.level.material.Fluid fluid = fluidInTank.getFluid();
			fluidState = fluid.defaultFluidState().createLegacyBlock();
			soundevent = FluidHelper.getEmptySound(fluidInTank);
		}

		if (exchange == FluidExchange.TANK_TO_ITEM) {
			net.minecraft.world.level.material.Fluid fluid = prevFluidInTank.getFluid();
			fluidState = fluid.defaultFluidState().createLegacyBlock();
			soundevent = FluidHelper.getFillSound(prevFluidInTank);
		}

		if (soundevent != null && !onClient) {
			float pitch = Mth.clamp(1 - (1f * fluidInTank.getAmount() / (FuelTankBlockEntity.getCapacityPerBlock() * 16)), 0,
				1);
			pitch /= 1.5f;
			pitch += .5f;
			pitch += (level.random.nextFloat() - .5f) / 4f;
			level.playSound(null, pos, soundevent, SoundSource.BLOCKS, .5f, pitch);
		}

		if (!FluidStack.isSameFluidSameComponents(fluidInTank, prevFluidInTank)) {
			FuelTankBlockEntity controllerBE = be.getControllerBE();
			if (controllerBE != null) {
				if (fluidState != null && onClient) {
					BlockParticleOption blockParticleData = new BlockParticleOption(ParticleTypes.BLOCK, fluidState);
					float fluidLevel = (float) fluidInTank.getAmount() / tankCapability.getTankCapacity(0);

					boolean reversed = fluidInTank.getFluid().getFluidType().isLighterThanAir();
					if (reversed)
						fluidLevel = 1 - fluidLevel;

					Vec3 vec = hitResult.getLocation();
					vec = new Vec3(vec.x, controllerBE.getBlockPos().getY() + fluidLevel
						* (Math.max(1, controllerBE.getCount()) - .5f) + .25f, vec.z);
					Vec3 motion = player.position().subtract(vec).scale(1 / 20f);
					vec = vec.add(motion);
					level.addParticle(blockParticleData, vec.x, vec.y, vec.z, motion.x, motion.y, motion.z);
					return ItemInteractionResult.SUCCESS;
				}

				controllerBE.sendDataImmediately();
				controllerBE.setChanged();
			}
		}

		return ItemInteractionResult.SUCCESS;
	}

	@Override
	public int getLightEmission(BlockState state, BlockGetter world, BlockPos pos) {
		int light = 0;
		// The brightness verdict is shared through LIT: vanilla light, the lamp
		// shell skin and the shader colored-light flag all read one value rather
		// than re-deriving it per consumer.
		boolean lit = state.getValue(LIT);
		// Fluid luminosity, propagated from the controller (mirrors the original tank).
		FuelTankBlockEntity tankAt = FuelTankConnectivity.partAt(getBlockEntityType(), world, pos);
		if (tankAt != null && tankAt.hasLevel()) {
			FuelTankBlockEntity controllerBE = tankAt.getControllerBE();
			if (controllerBE != null) {
				int lum = controllerBE.luminosity;
				if (lum > 0) {
					// Bright cells (at or below the liquid surface of their basin,
					// flipped for lighter-than-air fluids) glow at full luminosity,
					// cells above it glow faintly at 1, so a partially filled tank
					// lights only its filled region.
					light = lit ? lum : 1;
				}
			}
		}
		// Copycat material luminosity: the shell material adds its own light, so a
		// cell lit by an emissive material shines even while empty (max of both).
		// A rose quartz lamp shell mirrors the cell's own brightness: POWERING
		// follows the per-cell light rule, so a bright cell contributes the
		// powered lamp's full 15 while a dark cell contributes nothing (mirrors
		// Create's lamp, where POWERING drives both the texture and the light).
		BlockEntity be = world.getBlockEntity(pos);
		if (be instanceof FuelTankBlockEntity tankBE && tankBE.hasCustomMaterial()) {
			BlockState material = tankBE.getMaterial();
			if (material.is(AllBlocks.ROSE_QUARTZ_LAMP.get()))
				light = Math.max(light, material.setValue(RoseQuartzLampBlock.POWERING, lit)
					.getLightEmission(world, pos));
			else
				light = Math.max(light, material.getLightEmission(world, pos));
		}
		return light;
	}

	/**
	 * Brightness of a cell under the group that governs it (the drifted-cell
	 * path of {@link #refreshLitStates}): bright when the liquid surface of its
	 * basin sits at or above it, dark on an empty/dangling controller, and the
	 * full-brightness fallback while no basin data is available yet.
	 */
	private static boolean brightnessUnder(FuelTankBlockEntity controller, BlockPos pos) {
		if (controller == null || controller.luminosity <= 0)
			return false;
		FuelTankConnectivity.BasinData basins = controller.basins;
		Integer basinId = basins != null ? basins.basinByCell.get(pos) : null;
		if (basinId == null)
			return true;
		float surface = basins.surfaces[basinId];
		boolean reversed = controller.tankInventory.getFluid()
			.getFluidType()
			.isLighterThanAir();
		return reversed ? pos.getY() >= (int) Math.floor(surface) : pos.getY() <= (int) Math.floor(surface);
	}

	/** Writes {@code lit} into one cell's {@link #LIT} flag when it changed. */
	private static void setLit(net.minecraft.server.level.ServerLevel level, BlockPos pos, BlockState state,
		boolean lit) {
		if (state.getValue(LIT) != lit)
			level.setBlock(pos, state.setValue(LIT, lit), UPDATE_CLIENTS);
	}

	/**
	 * Server-side refresh of the {@link #LIT} flag over one group's cells.
	 * Per position the verdict is identical to resolving the cell's <i>own</i>
	 * controller and applying its brightness rule, but our group's inputs
	 * &mdash; luminosity, fluid orientation, basin surfaces &mdash; are resolved
	 * once instead of per cell, membership is confirmed from the cell's stored
	 * controller position instead of a second block-entity lookup, and cells
	 * that drifted to another group (possible while a deferred split leaves
	 * stale basin data behind) are judged from that group's data &mdash; never
	 * from ours &mdash; with each distinct foreign controller resolved only
	 * once per sweep. Compare-first throughout, so steady-state sweeps only
	 * read; {@code UPDATE_CLIENTS} only, since neighbours are irrelevant to
	 * this purely informational flag.
	 */
	static void refreshLitStates(net.minecraft.server.level.ServerLevel level, Iterable<BlockPos> cells,
		FuelTankBlockEntity controller) {
		var type = CMIBlockEntityTypes.MOLTEN_SALT_FUEL_TANK.get();
		BlockPos controllerPos = controller.getBlockPos();
		int luminosity = controller.luminosity;
		boolean reversed = luminosity > 0
			&& controller.tankInventory.getFluid()
				.getFluidType()
				.isLighterThanAir();
		FuelTankConnectivity.BasinData basins = controller.basins;
		// Drifted-cell bookkeeping: distinct foreign controllers resolved once.
		Map<BlockPos, FuelTankBlockEntity> driftControllers = null;
		for (BlockPos pos : cells) {
			BlockState state = level.getBlockState(pos);
			if (!isFuelTank(state))
				continue;
			BlockEntity be = level.getBlockEntity(pos);
			if (!(be instanceof FuelTankBlockEntity tank) || tank.isRemoved() || !tank.hasLevel()
				|| tank.getType() != type) {
				setLit(level, pos, state, false);
				continue;
			}
			BlockPos cellControllerPos = tank.getController();
			boolean lit;
			if (controllerPos.equals(cellControllerPos)) {
				// Ours — everything precomputed, no further lookups.
				if (luminosity <= 0)
					lit = false;
				else {
					Integer basinId = basins != null ? basins.basinByCell.get(pos) : null;
					if (basinId == null)
						lit = true;
					else {
						float surface = basins.surfaces[basinId];
						lit = reversed ? pos.getY() >= (int) Math.floor(surface)
							: pos.getY() <= (int) Math.floor(surface);
					}
				}
			} else {
				// Drifted to another group (or a dangling pointer): judge from THAT
				// group exactly as the old per-cell resolution did. getControllerBE
				// may legitimately return null; the map stores it so a repeated
				// dangling target is not re-resolved either.
				if (driftControllers == null)
					driftControllers = new HashMap<>();
				FuelTankBlockEntity driftController;
				if (driftControllers.containsKey(cellControllerPos))
					driftController = driftControllers.get(cellControllerPos);
				else {
					driftController = tank.getControllerBE();
					driftControllers.put(cellControllerPos, driftController);
				}
				lit = brightnessUnder(driftController, pos);
			}
			setLit(level, pos, state, lit);
		}
	}

	@Override
	public boolean hasAnalogOutputSignal(BlockState state) {
		return true;
	}

	@Override
	public int getAnalogOutputSignal(BlockState blockState, Level worldIn, BlockPos pos) {
		BlockEntity be = worldIn.getBlockEntity(pos);
		if (be instanceof FuelTankBlockEntity tankBE) {
			FuelTankBlockEntity controllerBE = tankBE.getControllerBE();
			if (controllerBE != null)
				return ComparatorUtil.fractionToRedstoneLevel(controllerBE.getFillState());
		}
		return 0;
	}

	@Override
	public void setPlacedBy(Level pLevel, BlockPos pPos, BlockState pState, @Nullable LivingEntity pPlacer,
		ItemStack pStack) {
		// Copycat material from off-hand (mirrors CopycatBlock.setPlacedBy)
		super.setPlacedBy(pLevel, pPos, pState, pPlacer, pStack);
	}

	@Override
	public VoxelShape getBlockSupportShape(BlockState pState, BlockGetter pReader, BlockPos pPos) {
		return Shapes.block();
	}

	// ---- copycat CT (mirrors CopycatBlock#getAppearance / canConnectTexturesToward) ----

	/**
	 * Faces on the boundary of a same-group tank are culled, so they carry no
	 * material appearance to hand over to neighbours (mirrors the "interior wall"
	 * semantics of Create's copycat).
	 */
	@Override
	public boolean isIgnoredConnectivitySide(BlockAndTintGetter reader, BlockState state, Direction face,
		@Nullable BlockPos fromPos, @Nullable BlockPos toPos) {
		return fromPos != null && FuelTankConnectivity.isSameGroup(reader, fromPos, fromPos.relative(face));
	}

	/**
	 * The shell's material CT connects toward a neighbour when it shares the
	 * material: another tank with the same material, or a real block of the
	 * material itself (mirrors Create's
	 * {@code ConnectedTextureBehaviour#connectsTo}, which decides by block
	 * equality — the same rule that makes the real material's own CT connect
	 * toward this tank through {@link #getAppearance}).
	 */
	@Override
	public boolean canConnectTexturesToward(BlockAndTintGetter reader, BlockPos fromPos, BlockPos toPos,
		BlockState state) {
		if (fromPos == null || toPos == null)
			return false;
		BlockState material = CopycatBlock.getMaterial(reader, fromPos);
		if (material.isAir())
			return false;
		BlockState toState = reader.getBlockState(toPos);
		if (toState.getBlock() instanceof FuelTankBlock)
			return CopycatBlock.getMaterial(reader, toPos).getBlock() == material.getBlock();
		return toState.getBlock() == material.getBlock();
	}

	// ---- ticking (CopycatBlock returns null; the tank must tick) ----

	@Override
	public <S extends BlockEntity> BlockEntityTicker<S> getTicker(Level level, BlockState state,
		BlockEntityType<S> type) {
		if (SmartBlockEntity.class.isAssignableFrom(FuelTankBlockEntity.class))
			return new SmartBlockEntityTicker<>();
		return null;
	}

	/**
	 * The BE type is the tank's own; the generic class binding of
	 * {@code IBE<CopycatBlockEntity>} cannot be re-bound, so the runtime class is
	 * returned through an unchecked cast (the tank BE extends
	 * {@code CopycatBlockEntity}).
	 */
	@SuppressWarnings({ "unchecked" })
	@Override
	public Class<CopycatBlockEntity> getBlockEntityClass() {
		return (Class<CopycatBlockEntity>) (Class<?>) FuelTankBlockEntity.class;
	}

	@Override
	public BlockEntityType<? extends CopycatBlockEntity> getBlockEntityType() {
		return CMIBlockEntityTypes.MOLTEN_SALT_FUEL_TANK.get();
	}
}