package com.iridium126.createmanaindustry.content.kinetics.temporarykinetics;

import dev.engine_room.flywheel.lib.model.baked.PartialModel;
import net.minecraft.world.level.block.entity.BlockEntity;

public final class TemporaryKineticsRenderContext {
    private static final ThreadLocal<BlockEntity> RENDERED_BLOCK_ENTITY = new ThreadLocal<>();

    private TemporaryKineticsRenderContext() {}

    public static void set(BlockEntity be) {
        RENDERED_BLOCK_ENTITY.set(be);
    }

    public static void clear(BlockEntity be) {
        if (RENDERED_BLOCK_ENTITY.get() == be)
            RENDERED_BLOCK_ENTITY.remove();
    }

    public static BlockEntity get() {
        return RENDERED_BLOCK_ENTITY.get();
    }

    public static PartialModel replace(PartialModel partial) {
        BlockEntity be = RENDERED_BLOCK_ENTITY.get();
        if (be == null)
            return partial;
        return TemporaryKineticsModel.replacementOrSelf(be, partial);
    }
}
