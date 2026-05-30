package com.iridium126.createtricks.content.kinetics;

import java.util.Collections;
import java.util.Map;
import java.util.WeakHashMap;
import java.util.function.Function;

import dev.engine_room.flywheel.api.model.Model;
import dev.engine_room.flywheel.lib.model.Models;
import dev.engine_room.flywheel.lib.model.baked.PartialModel;
import net.minecraft.world.level.block.entity.BlockEntity;

public final class TemporaryStressVisualModels {
	private static final Map<Model, Resolver> MODELS = Collections.synchronizedMap(new WeakHashMap<>());

	private TemporaryStressVisualModels() {}

	public static void track(PartialModel partial, Model model) {
		track(partial, model, Models::partial);
	}

	public static void track(PartialModel partial, Model model, Function<PartialModel, Model> modelFactory) {
		if (TemporaryStressModel.hasReplacement(partial))
			MODELS.put(model, new Resolver(partial, modelFactory));
	}

	public static Model replace(BlockEntity be, Model model) {
		Resolver resolver = MODELS.get(model);
		if (resolver == null)
			return model;

		PartialModel replacement = TemporaryStressModel.replacement(be, resolver.partial);
		if (replacement == null || replacement == resolver.partial)
			return model;
		return resolver.modelFactory.apply(replacement);
	}

	private record Resolver(PartialModel partial, Function<PartialModel, Model> modelFactory) {}
}
