package com.iridium126.createmanaindustry.mixin.allvr;

import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.iridium126.createmanaindustry.dimension.AllvrClientBlockHook;
import com.iridium126.createmanaindustry.dimension.AllvrDimensions;
import com.iridium126.createmanaindustry.dimension.cube.AllvrCubeMap;
import com.iridium126.createmanaindustry.dimension.cube.AllvrServerLevelDuck;

import net.minecraft.core.BlockPos;
import net.minecraft.world.level.BlockCollisions;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.CollisionGetter;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;

/**
 * The vanilla collision iterator ({@code Entity#collide}, suffocation checks
 * and {@code findSupportingBlock} all share it) does NOT go through
 * {@code Level#getBlockState}: per XZ column it fetches the chunk via
 * {@code getChunkForCollisions} and reads {@code LevelChunk#getBlockState}
 * directly (vanilla fast path). Inside the allay dimension those column
 * chunks are empty air shells and positions sit outside their section range
 * (reads as air), so entities fall through every island — on both the client
 * and the server.
 * <p>
 * Wrapping the iterator's single {@code BlockGetter#getBlockState} call site
 * routes allay reads to the cube data (client → streamed cube cache via the
 * common-side hook, server → the per-level cube map) and leaves every other
 * dimension untouched. Unloaded cubes resolve to air, mirroring vanilla's
 * "no collision in unloaded chunks" behavior without triggering generation.
 */
@Mixin(BlockCollisions.class)
public abstract class AllvrBlockCollisionsMixin {

    @Shadow
    @Final
    private CollisionGetter collisionGetter;

    @WrapOperation(method = "computeNext", at = @At(value = "INVOKE",
        target = "Lnet/minecraft/world/level/BlockGetter;getBlockState(Lnet/minecraft/core/BlockPos;)Lnet/minecraft/world/level/block/state/BlockState;"))
    private BlockState allvr$collideGetBlockState(BlockGetter getter, BlockPos pos, Operation<BlockState> original) {
        if (this.collisionGetter instanceof Level level && level.dimension() == AllvrDimensions.ALLAY_LEVEL) {
            if (level.isClientSide) {
                return AllvrClientBlockHook.resolve(pos);
            }
            if (level instanceof AllvrServerLevelDuck duck) {
                AllvrCubeMap map = duck.allvr$getCubeMap();
                return map == null ? Blocks.VOID_AIR.defaultBlockState() : map.getBlockState(pos);
            }
        }
        return original.call(getter, pos);
    }
}
