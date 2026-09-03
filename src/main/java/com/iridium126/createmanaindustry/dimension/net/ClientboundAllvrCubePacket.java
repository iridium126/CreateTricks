package com.iridium126.createmanaindustry.dimension.net;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import net.minecraft.core.BlockPos;
import net.minecraft.core.RegistryAccess;
import net.minecraft.core.registries.Registries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.world.level.chunk.LevelChunkSection;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.neoforged.neoforge.network.handling.IPayloadContext;

import com.iridium126.createmanaindustry.CreateManaIndustry;
import com.iridium126.createmanaindustry.client.dimension.AllvrClientCubeCache;
import com.iridium126.createmanaindustry.dimension.cube.AllvrCube;
import com.iridium126.createmanaindustry.dimension.cube.AllvrCubePos;

import it.unimi.dsi.fastutil.ints.Int2IntMap;
import it.unimi.dsi.fastutil.ints.Int2ObjectMap;

/**
 * One cube's block data, server → client. Wire format (payload bytes, after
 * the cube position long):
 * <pre>
 *   8 × LevelChunkSection#write  — nonEmpty count + states palette/bits + biomes palette
 *                                  (vanilla chunk-packet encoding; a uniform stone
 *                                   cube costs ~100 bytes, an air cube ~50)
 *   varint BE count
 *     BE × { short cellIndex, update-tag NBT }   (cell = y&lt;&lt;10 | z&lt;&lt;5 | x, 15 bit)
 *   varint emitter count
 *     emitter × { short cellIndex, varint emission }
 * </pre>
 * The position is {@link AllvrCubePos#asLong()} written directly (21 bit per
 * axis) — the vanilla section/position narrow types are never used.
 * <p>
 * Light is carried as emitter events only; there is deliberately no
 * light-engine data on the wire (the client builds its own synthetic light,
 * roadmap phase 3+).
 */
public record ClientboundAllvrCubePacket(long cubePos, byte[] payload) implements CustomPacketPayload {

    public static final CustomPacketPayload.Type<ClientboundAllvrCubePacket> TYPE =
        new CustomPacketPayload.Type<>(CreateManaIndustry.modLoc("allvr_cube"));

    public static final StreamCodec<RegistryFriendlyByteBuf, ClientboundAllvrCubePacket> STREAM_CODEC =
        StreamCodec.of(ClientboundAllvrCubePacket::encode, ClientboundAllvrCubePacket::decode);

    private static void encode(RegistryFriendlyByteBuf buffer, ClientboundAllvrCubePacket p) {
        buffer.writeLong(p.cubePos);
        buffer.writeByteArray(p.payload);
    }

    private static ClientboundAllvrCubePacket decode(RegistryFriendlyByteBuf buffer) {
        return new ClientboundAllvrCubePacket(buffer.readLong(), buffer.readByteArray());
    }

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }

    /** Server-side encoder (server thread). NBT uses the level's registry access. */
    public static ClientboundAllvrCubePacket of(AllvrCube cube, RegistryAccess registryAccess) {
        ByteBuf byteBuf = Unpooled.buffer();
        FriendlyByteBuf buf = new FriendlyByteBuf(byteBuf);
        for (LevelChunkSection section : cube.getSections()) {
            section.write(buf);
        }
        Int2ObjectMap<BlockEntity> bes = cube.getBlockEntities();
        buf.writeVarInt(bes.size());
        for (Int2ObjectMap.Entry<BlockEntity> entry : bes.int2ObjectEntrySet()) {
            buf.writeShort(entry.getIntKey());
            CompoundTag tag = entry.getValue().getUpdateTag(registryAccess);
            buf.writeNbt(tag.isEmpty() ? null : tag);
        }
        Int2IntMap emitters = cube.getEmitters();
        buf.writeVarInt(emitters.size());
        for (Int2IntMap.Entry entry : emitters.int2IntEntrySet()) {
            buf.writeShort(entry.getIntKey());
            buf.writeVarInt(entry.getIntValue());
        }
        byte[] payload = new byte[byteBuf.readableBytes()];
        byteBuf.readBytes(payload);
        byteBuf.release();
        return new ClientboundAllvrCubePacket(cube.getPos().asLong(), payload);
    }

    /** Called on the client; decoded and applied on the main thread. */
    public static void handle(ClientboundAllvrCubePacket packet, IPayloadContext ctx) {
        ctx.enqueueWork(() -> AllvrClientCubeCache.applyCube(packet));
    }

    /** Client-side decode into a fresh {@link AllvrCube} (main thread). */
    public AllvrCube decodeCube(RegistryAccess registryAccess) {
        AllvrCubePos pos = AllvrCubePos.fromLong(cubePos);
        AllvrCube cube = new AllvrCube(pos, registryAccess.registryOrThrow(Registries.BIOME));
        FriendlyByteBuf buf = new FriendlyByteBuf(Unpooled.wrappedBuffer(payload));
        LevelChunkSection[] sections = cube.getSections();
        for (int i = 0; i < sections.length; i++) {
            sections[i].read(buf);
        }
        int baseX = pos.minBlockX();
        int baseY = pos.minBlockY();
        int baseZ = pos.minBlockZ();
        int beCount = buf.readVarInt();
        for (int i = 0; i < beCount; i++) {
            int cell = buf.readShort() & 0xFFFF;
            CompoundTag tag = buf.readNbt();
            BlockPos worldPos = new BlockPos(baseX + (cell & 31), baseY + (cell >> 10), baseZ + ((cell >> 5) & 31));
            BlockState state = cube.getBlockState(worldPos);
            if (state.getBlock() instanceof net.minecraft.world.level.block.EntityBlock entityBlock) {
                BlockEntity be = entityBlock.newBlockEntity(worldPos, state);
                if (be != null) {
                    if (tag != null) {
                        be.loadWithComponents(tag, registryAccess);
                    }
                    cube.putBlockEntity(worldPos, be);
                }
            }
        }
        int emitterCount = buf.readVarInt();
        for (int i = 0; i < emitterCount; i++) {
            int cell = buf.readShort() & 0xFFFF;
            int emission = buf.readVarInt();
            cube.putEmitter(new BlockPos(baseX + (cell & 31), baseY + (cell >> 10), baseZ + ((cell >> 5) & 31)), emission);
        }
        return cube;
    }
}
