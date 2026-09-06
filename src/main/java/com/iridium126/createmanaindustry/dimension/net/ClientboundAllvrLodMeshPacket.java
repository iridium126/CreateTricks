package com.iridium126.createmanaindustry.dimension.net;

import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.neoforged.neoforge.network.handling.IPayloadContext;

import com.iridium126.createmanaindustry.CreateManaIndustry;
import com.iridium126.createmanaindustry.client.dimension.AllvrLodClientState;

/**
 * One LOD node's meshed quad stream (doc §13 4c). The quads use the shared
 * 8-byte format with ONE wire difference from the client-meshed streams: the
 * stateId field carries the VANILLA global state id (server-side codec — the
 * client's render-state table does not exist there). The client remaps every
 * quad to its render id once on receive.
 * <p>
 * An empty array is meaningful: "meshed, no faces" — it settles the client's
 * pending state so the node is not re-requested forever.
 */
public record ClientboundAllvrLodMeshPacket(int level, long cellLong, long[] quads) implements CustomPacketPayload {

    public static final CustomPacketPayload.Type<ClientboundAllvrLodMeshPacket> TYPE =
        new CustomPacketPayload.Type<>(CreateManaIndustry.modLoc("allvr_lod_mesh"));

    public static final StreamCodec<RegistryFriendlyByteBuf, ClientboundAllvrLodMeshPacket> STREAM_CODEC =
        StreamCodec.of(ClientboundAllvrLodMeshPacket::encode, ClientboundAllvrLodMeshPacket::decode);

    private static void encode(RegistryFriendlyByteBuf buf, ClientboundAllvrLodMeshPacket p) {
        buf.writeVarInt(p.level);
        buf.writeLong(p.cellLong);
        buf.writeLongArray(p.quads);
    }

    private static ClientboundAllvrLodMeshPacket decode(RegistryFriendlyByteBuf buf) {
        return new ClientboundAllvrLodMeshPacket(buf.readVarInt(), buf.readLong(), buf.readLongArray());
    }

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }

    public static void handle(ClientboundAllvrLodMeshPacket packet, IPayloadContext ctx) {
        ctx.enqueueWork(() -> AllvrLodClientState.applyMesh(packet));
    }
}
