package com.iridium126.createtricks.content.kinetics;

import java.util.function.Consumer;

import com.simibubi.create.content.kinetics.base.KineticBlockEntityVisual;
import com.simibubi.create.content.kinetics.base.RotatingInstance;
import com.simibubi.create.content.kinetics.simpleRelays.BracketedKineticBlockEntity;
import com.simibubi.create.content.kinetics.simpleRelays.BracketedKineticBlockEntityRenderer;
import com.simibubi.create.foundation.render.AllInstanceTypes;

import dev.engine_room.flywheel.api.instance.Instance;
import dev.engine_room.flywheel.api.visualization.VisualizationContext;
import dev.engine_room.flywheel.lib.model.Models;
import dev.engine_room.flywheel.lib.visual.SimpleTickableVisual;
import net.minecraft.core.Direction;

public class TemporaryStressBracketedLargeCogVisual extends KineticBlockEntityVisual<BracketedKineticBlockEntity>
		implements SimpleTickableVisual {
	private RotatingInstance rotatingModel;
	private RotatingInstance additionalShaft;
	private boolean stressed;

	public TemporaryStressBracketedLargeCogVisual(VisualizationContext context, BracketedKineticBlockEntity blockEntity,
			float partialTick) {
		super(context, blockEntity, partialTick);
		setupModels();
	}

	@Override
	public void update(float pt) {
		boolean active = TemporaryStress.isActive(blockEntity);
		if (active != stressed) {
			rotatingModel.delete();
			additionalShaft.delete();
			setupModels();
			return;
		}

		rotatingModel.setup(blockEntity)
			.setChanged();
		additionalShaft.setup(blockEntity)
			.setRotationOffset(BracketedKineticBlockEntityRenderer.getShaftAngleOffset(rotationAxis(), pos))
			.setChanged();
	}

	@Override
	public void tick(Context context) {
		applyOverstressEffect(blockEntity, rotatingModel);
	}

	@Override
	public void updateLight(float partialTick) {
		relight(rotatingModel, additionalShaft);
	}

	@Override
	protected void _delete() {
		rotatingModel.delete();
		additionalShaft.delete();
	}

	@Override
	public void collectCrumblingInstances(Consumer<Instance> consumer) {
		consumer.accept(rotatingModel);
		consumer.accept(additionalShaft);
	}

	private void setupModels() {
		stressed = TemporaryStress.isActive(blockEntity);
		Direction.Axis axis = rotationAxis();
		rotatingModel = instancerProvider().instancer(AllInstanceTypes.ROTATING,
				Models.partial(TemporaryStressModel.shaftlessLargeCogwheel(blockEntity)))
			.createInstance()
			.rotateToFace(Direction.UP, axis)
			.setup(blockEntity)
			.setPosition(getVisualPosition());
		rotatingModel.setChanged();

		additionalShaft = instancerProvider().instancer(AllInstanceTypes.ROTATING,
				Models.partial(TemporaryStressModel.cogwheelShaft(blockEntity)))
			.createInstance()
			.rotateToFace(axis)
			.setup(blockEntity)
			.setRotationOffset(BracketedKineticBlockEntityRenderer.getShaftAngleOffset(axis, pos))
			.setPosition(getVisualPosition());
		additionalShaft.setChanged();
	}
}
