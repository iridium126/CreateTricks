package com.iridium126.createmanaindustry.content.fluids.fueltank;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import com.simibubi.create.foundation.model.BakedModelWrapperWithData;

import net.createmod.catnip.data.Iterate;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.block.model.BakedQuad;
import net.minecraft.client.resources.model.BakedModel;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.BlockAndTintGetter;
import net.minecraft.world.level.block.state.BlockState;
import net.neoforged.neoforge.client.model.data.ModelData;
import net.neoforged.neoforge.client.model.data.ModelData.Builder;
import net.neoforged.neoforge.client.model.data.ModelProperty;

/**
 * Delegate baked model that culls the faces shared with an adjacent fuel tank of
 * the same group, so the interior walls between connected tanks are not rendered.
 * All six faces are culled by the same rule — including top/bottom, which the
 * unified six-face model relies on instead of the old top/bottom blockstate
 * variants (mirrors Create's {@code FluidTankModel} without the connected-texture
 * sprite shifting). The cull set is computed per-block through {@link ModelData}.
 */
public class FuelTankModel extends BakedModelWrapperWithData {

	protected static final ModelProperty<CullData> CULL_PROPERTY = new ModelProperty<>();

	public FuelTankModel(BakedModel originalModel) {
		super(originalModel);
	}

	@Override
	protected ModelData.Builder gatherModelData(Builder builder, BlockAndTintGetter world, BlockPos pos,
		BlockState state, ModelData blockEntityData) {
		CullData cullData = new CullData();
		for (Direction d : Iterate.directions)
			cullData.setCulled(d, FuelTankConnectivity.isSameGroup(world, pos, pos.relative(d)));
		return builder.with(CULL_PROPERTY, cullData);
	}

	@Override
	public List<BakedQuad> getQuads(BlockState state, Direction side, RandomSource rand, ModelData extraData,
		RenderType renderType) {
		if (side != null)
			return Collections.emptyList();

		List<BakedQuad> quads = new ArrayList<>();
		for (Direction d : Iterate.directions) {
			if (extraData.has(CULL_PROPERTY) && extraData.get(CULL_PROPERTY)
				.isCulled(d))
				continue;
			quads.addAll(super.getQuads(state, d, rand, extraData, renderType));
		}
		quads.addAll(super.getQuads(state, null, rand, extraData, renderType));
		return quads;
	}

	private static class CullData {
		final boolean[] culledFaces = new boolean[6];

		void setCulled(Direction face, boolean cull) {
			culledFaces[face.get3DDataValue()] = cull;
		}

		boolean isCulled(Direction face) {
			return culledFaces[face.get3DDataValue()];
		}
	}
}
