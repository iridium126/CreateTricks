package com.iridium126.createtricks.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;

import com.iridium126.createtricks.content.kinetics.TemporaryStressInstancerProvider;
import com.simibubi.create.content.kinetics.base.KineticBlockEntity;
import com.simibubi.create.content.kinetics.base.KineticBlockEntityVisual;

import dev.engine_room.flywheel.api.instance.InstancerProvider;
import dev.engine_room.flywheel.api.visualization.VisualizationContext;
import dev.engine_room.flywheel.lib.visual.AbstractBlockEntityVisual;

@Mixin(value = KineticBlockEntityVisual.class, remap = false)
public abstract class KineticBlockEntityVisualMixin<T extends KineticBlockEntity> extends AbstractBlockEntityVisual<T> {
	@Unique
	private InstancerProvider createtricks$instancerProvider;

	protected KineticBlockEntityVisualMixin(VisualizationContext context, T blockEntity, float partialTick) {
		super(context, blockEntity, partialTick);
	}

	@Override
	protected InstancerProvider instancerProvider() {
		if (createtricks$instancerProvider == null)
			createtricks$instancerProvider = new TemporaryStressInstancerProvider(visualizationContext.instancerProvider(),
					blockEntity);
		return createtricks$instancerProvider;
	}
}
