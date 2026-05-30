package com.iridium126.createtricks.content.kinetics;

import java.util.function.Consumer;

import com.simibubi.create.content.kinetics.base.KineticBlockEntityVisual;
import com.simibubi.create.content.kinetics.base.RotatingInstance;
import com.simibubi.create.foundation.render.AllInstanceTypes;

import dev.engine_room.flywheel.api.instance.Instance;
import dev.engine_room.flywheel.api.visual.BlockEntityVisual;
import dev.engine_room.flywheel.api.visualization.VisualizationContext;
import dev.engine_room.flywheel.lib.model.Models;
import net.minecraft.core.Direction;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;

public class StressManaConverterVisual extends KineticBlockEntityVisual<StressManaConverterBlockEntity> {
	private RotatingInstance rotatingModel;
	private boolean stressed;

	private StressManaConverterVisual(VisualizationContext context, StressManaConverterBlockEntity blockEntity,
			float partialTick) {
		super(context, blockEntity, partialTick);
		setupModel();
	}

	public static BlockEntityVisual<? super StressManaConverterBlockEntity> create(VisualizationContext context,
			StressManaConverterBlockEntity blockEntity, float partialTick) {
		return new StressManaConverterVisual(context, blockEntity, partialTick);
	}

	@Override
	public void update(float pt) {
		boolean active = TemporaryStress.isActive(blockEntity);
		if (active != stressed) {
			rotatingModel.delete();
			setupModel();
			return;
		}

		rotatingModel.setup(blockEntity)
			.setChanged();
	}

	@Override
	public void updateLight(float partialTick) {
		relight(rotatingModel);
	}

	@Override
	protected void _delete() {
		rotatingModel.delete();
	}

	@Override
	public void collectCrumblingInstances(Consumer<Instance> consumer) {
		consumer.accept(rotatingModel);
	}

	private void setupModel() {
		stressed = TemporaryStress.isActive(blockEntity);
		Direction facing = blockEntity.getBlockState()
			.getValue(BlockStateProperties.FACING);
		rotatingModel = instancerProvider().instancer(AllInstanceTypes.ROTATING,
				Models.partial(TemporaryStressModel.innerStressManaConverter(blockEntity)))
			.createInstance()
			.rotateToFace(Direction.SOUTH, facing)
			.setup(blockEntity)
			.setPosition(getVisualPosition());
		rotatingModel.setChanged();
	}
}
