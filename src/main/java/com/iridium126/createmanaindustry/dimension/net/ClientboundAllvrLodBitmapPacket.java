package com.iridium126.createmanaindustry.dimension.net;

import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.neoforged.neoforge.network.handling.IPayloadContext;

import com.iridium126.createmanaindustry.CreateManaIndustry;
import com.iridium126.createmanaindustry.client.dimension.AllvrLodClientState;

/**
 * One level's surface-node bitmap around a player (doc §13 4c): the box
 * origin in level cells, its uniform cell dimension, and the packed bitset.
 * Bit index = {@code (cy·dim + cz)·dim + cx} (Y-major, matching
 * {@code AllvrLodField#compute}). ~4 KB per level at L0–L2, 0.5–4 KB at L3.
 */
public record ClientboundAllvrLodBitmapPacket(int level, int originCellX, int originCellY, int originCellZ,
                                              int dimCells, long[] words) implements CustomPacketPayload {

    public static final CustomPacketPayload.Type<ClientboundAllvrLodBitmapPacket> TYPE =
        new CustomPacketPayload.Type<>(CreateManaIndustry.modLoc("allvr_lod_bitmap"));

    public static final StreamCodec<RegistryFriendlyByteBuf, ClientboundAllvrLodBitmapPacket> STREAM_CODEC =
        StreamCodec.of(ClientboundAllvrLodBitmapPacket::encode, ClientboundAllvrLodBitmapPacket::decode);

    private static void encode(RegistryFriendlyByteBuf buf, ClientboundAllvrLodBitmapPacket p) {
        buf.writeVarInt(p.level);
        buf.writeVarInt(p.originCellX);
        buf.writeVarInt(p.originCellY);
        buf.writeVarInt(p.originCellZ);
        buf.writeVarInt(p.dimCells);
        buf.writeLongArray(p.words);
    }

    private static ClientboundAllvrLodBitmapPacket decode(RegistryFriendlyByteBuf buf) {
        return new ClientboundAllvrLodBitmapPacket(buf.readVarInt(), buf.readVarInt(), buf.readVarInt(),
            buf.readVarInt(), buf.readVarInt(), buf.readLongArray());
    }

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }

    public static void handle(ClientboundAllvrLodBitmapPacket packet, IPayloadContext ctx) {
        ctx.enqueueWork(() -> AllvrLodClientState.applyBitmap(packet));
    }
}
