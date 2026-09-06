package com.iridium126.createmanaindustry.dimension.mesh;

import net.minecraft.world.level.block.state.BlockState;

/**
 * BlockState → packed quad stateId mapping (doc §13 4c). One method: the id
 * written into the quad word's bits 28..43, or 0 for "not renderable" (the
 * mask cell stays empty and no quad is emitted).
 * <p>
 * Two implementations:
 * <ul>
 *   <li><b>Client</b> (full-res cubes): the {@code AllvrRenderStateMap}
 *       16-bit render id, gated on its per-state {@code renderable} flag
 *       (full-cube model assumption).</li>
 *   <li><b>Server</b> (LOD nodes): the vanilla global state id
 *       ({@code Block.getId}), gated on canOcclude + full-block collision —
 *       the 4c grilling gating decision. The client remaps vanilla ids to
 *       render ids once per quad on mesh-packet receive.</li>
 * </ul>
 */
@FunctionalInterface
public interface AllvrMeshCodec {

    /** Packed id for the quad word (bits 28..43), 0 = not renderable. */
    int packId(BlockState state);
}
