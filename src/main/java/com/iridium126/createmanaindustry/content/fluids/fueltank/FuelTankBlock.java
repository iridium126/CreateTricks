package com.iridium126.createmanaindustry.content.fluids.fueltank;

import com.iridium126.createmanaindustry.CMIBlockEntityTypes;
import com.simibubi.create.content.equipment.wrench.IWrenchable;
import com.simibubi.create.foundation.block.IBE;
import com.simibubi.create.foundation.blockEntity.ComparatorUtil;

import net.minecraft.core.BlockPos;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.shapes.Shapes;
import net.minecraft.world.phys.shapes.VoxelShape;

/**
 * Molten Salt Fuel Tank — a multi-block fluid storage that connects in any
 * shape. Mirror of Create's {@code FluidTankBlock} minus the window shapes, the
 * wrench toggle and the boiler integration; the window is always open.
 * <p>
 * The model is a single six-face-unified variant: full-height frame + 8x8 side
 * windows, a 1-unit top ring with a window pane and a 1-unit solid bottom
 * plate. Faces shared with a same-group tank are culled in all six directions
 * by {@link FuelTankModel} (top/bottom included), so no top/bottom blockstate
 * properties are needed.
 * <p>
 * Wrench: plain wrenching is a no-op (the default {@code IWrenchable} rotation
 * has no effect on this axis-independent block); sneak-wrenching dismantles the
 * block via the default {@code onSneakWrenched} (BreakEvent + drops to the
 * player's inventory + WRENCH_REMOVE sound), mirroring Create's fluid tank.
 */
public class FuelTankBlock extends Block implements IWrenchable, IBE<FuelTankBlockEntity> {

	public FuelTankBlock(Properties properties) {
		super(properties);
	}

	public static boolean isFuelTank(BlockState state) {
		return state.getBlock() instanceof FuelTankBlock;
	}

	@Override
	public void onPlace(BlockState state, Level world, BlockPos pos, BlockState oldState, boolean moved) {
		if (oldState.getBlock() == state.getBlock())
			return;
		if (moved)
			return;
		withBlockEntityDo(world, pos, FuelTankBlockEntity::updateConnectivity);

		// updateConnectivity may have changed the in-world block state, which prevents
		// markAndNotifyBlock in CommonHooks#onPlaceItemIntoWorld from doing anything.
		BlockState newState = world.getBlockState(pos);
		if (state != newState && newState.getBlock() == this)
			world.markAndNotifyBlock(pos, world.getChunkAt(pos), oldState, newState, Block.UPDATE_ALL_IMMEDIATE, 512);
	}

	@Override
	public void onRemove(BlockState state, Level world, BlockPos pos, BlockState newState, boolean isMoving) {
		if (state.hasBlockEntity() && (state.getBlock() != newState.getBlock() || !newState.hasBlockEntity())) {
			BlockEntity be = world.getBlockEntity(pos);
			if (!(be instanceof FuelTankBlockEntity tankBE))
				return;
			world.removeBlockEntity(pos);
			FuelTankConnectivity.split(tankBE);
			// A removed fuel tank can break or re-root a fuel rod; re-validate the
			// structures around the hole from any surviving neighbours.
			if (!world.isClientSide)
				FuelRodStructure.validateFor(world, pos);
		}
	}

	@Override
	public int getLightEmission(BlockState state, BlockGetter world, BlockPos pos) {
		FuelTankBlockEntity tankAt = FuelTankConnectivity.partAt(getBlockEntityType(), world, pos);
		if (tankAt == null || !tankAt.hasLevel())
			return 0;
		FuelTankBlockEntity controllerBE = tankAt.getControllerBE();
		if (controllerBE == null)
			return 0;
		// Only the controller tracks the fluid's luminosity; every part reports it.
		return controllerBE.luminosity;
	}

	@Override
	public boolean hasAnalogOutputSignal(BlockState state) {
		return true;
	}

	@Override
	public int getAnalogOutputSignal(BlockState blockState, Level worldIn, BlockPos pos) {
		return getBlockEntityOptional(worldIn, pos).map(FuelTankBlockEntity::getControllerBE)
			.map(be -> ComparatorUtil.fractionToRedstoneLevel(be.getFillState()))
			.orElse(0);
	}

	@Override
	public VoxelShape getBlockSupportShape(BlockState pState, BlockGetter pReader, BlockPos pPos) {
		return Shapes.block();
	}

	@Override
	public Class<FuelTankBlockEntity> getBlockEntityClass() {
		return FuelTankBlockEntity.class;
	}

	@Override
	public BlockEntityType<? extends FuelTankBlockEntity> getBlockEntityType() {
		return CMIBlockEntityTypes.MOLTEN_SALT_FUEL_TANK.get();
	}
}
