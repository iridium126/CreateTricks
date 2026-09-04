package com.iridium126.createmanaindustry.dimension;

import java.util.function.Function;

import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;

/**
 * Common-side bridge for client block reads. Common mixins (the collision
 * iterator) must not reference client classes in their bytecode — a dedicated
 * server applies them but never loads {@code client.*} classes. The client
 * registers its cube-cache resolver during mod construction; the hook is only
 * ever consulted from client-side levels, so on a dedicated server the
 * resolver is unreachable and the null fallback never fires.
 */
public final class AllvrClientBlockHook {

    private static volatile Function<BlockPos, BlockState> resolver;

    /** Registers the client-side resolver (called once from client mod init). */
    public static void setResolver(Function<BlockPos, BlockState> blockResolver) {
        resolver = blockResolver;
    }

    public static BlockState resolve(BlockPos pos) {
        Function<BlockPos, BlockState> current = resolver;
        return current == null ? Blocks.VOID_AIR.defaultBlockState() : current.apply(pos);
    }

    private AllvrClientBlockHook() {}
}
