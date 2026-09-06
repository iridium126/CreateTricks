package com.iridium126.createmanaindustry.dimension.mesh;

/**
 * Mesher-time light sampler shared by the client cube baker and the server
 * LOD baker (doc §13 4c): both bake the two 4-bit channels into the quad
 * word's reserved bits, so the greedy sweep consumes them through one
 * interface and neither side forks the mesher.
 * <p>
 * {@code lx}/{@code lz} are cube-local coords in −1..32 (the padded snapshot
 * space), {@code y} is the absolute block Y of the sample voxel.
 */
public interface AllvrMeshLight {

    /** Origin of the sampled volume on Y — converts local sample Y to absolute. */
    long originY();

    /** Sky exposure nibble (0 or 15) at the sample voxel. */
    int sky(int lx, int lz, long y);

    /** Block light nibble (0..15) at the sample voxel. */
    int block(int lx, int lz, long y);
}
