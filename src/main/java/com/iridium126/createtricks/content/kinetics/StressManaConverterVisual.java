package com.iridium126.createtricks.content.kinetics;

import java.util.function.Consumer;

import com.iridium126.createtricks.CreateTricksPartialModels;
import com.simibubi.create.content.kinetics.base.KineticBlockEntityVisual;
import com.simibubi.create.content.kinetics.base.RotatingInstance;
import com.simibubi.create.foundation.render.AllInstanceTypes;

import dev.engine_room.flywheel.api.instance.Instance;
import dev.engine_room.flywheel.api.visualization.VisualizationContext;
import dev.engine_room.flywheel.lib.model.Models;
import dev.engine_room.flywheel.lib.visual.SimpleTickableVisual;
import net.minecraft.core.Direction;

public class StressManaConverterVisual extends KineticBlockEntityVisual<StressManaConverterBlockEntity>
		implements SimpleTickableVisual {

	protected final RotatingInstance inner;

	public StressManaConverterVisual(VisualizationContext context, StressManaConverterBlockEntity blockEntity,
			float partialTick) {
		super(context, blockEntity, partialTick);

		inner = instancerProvider().instancer(AllInstanceTypes.ROTATING, Models.partial(CreateTricksPartialModels.STRESS_MANA_CONVERTER_INNER))
				.createInstance()
				.rotateToFace(Direction.UP, rotationAxis())
				.setup(blockEntity)
				.setPosition(getVisualPosition());

		inner.setChanged();
	}

	@Override
	public void update(float pt) {
		inner.setup(blockEntity).setChanged();
	}

	@Override
	public void tick(Context context) {
		applyOverstressEffect(blockEntity, inner);
	}

	@Override
	public void updateLight(float partialTick) {
		relight(inner);
	}

	@Override
	protected void _delete() {
		inner.delete();
	}

	@Override
	public void collectCrumblingInstances(Consumer<Instance> consumer) {
		consumer.accept(inner);
	}
}
