package com.iridium126.createmanaindustry.dimension.net;

import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.neoforged.neoforge.network.handling.IPayloadContext;

import com.iridium126.createmanaindustry.CreateManaIndustry;
import com.iridium126.createmanaindustry.client.dimension.AllvrLodClientState;

/**
 * Tells the client to drop one LOD node's mesh and pending request state (doc
 * §13 4c): sent on player-edit invalidation (the node will be re-requested if
 * still in band), on stale build results, and as a rejection for
 * out-of-range/malformed requests so the client's pending set cannot leak.
 */
public record ClientboundAllvrLodForgetPacket(int level, long cellLong) implements CustomPacketPayload {

    public static final CustomPacketPayload.Type<ClientboundAllvrLodForgetPacket> TYPE =
        new CustomPacketPayload.Type<>(CreateManaIndustry.modLoc("allvr_lod_forget"));

    public static final StreamCodec<RegistryFriendlyByteBuf, ClientboundAllvrLodForgetPacket> STREAM_CODEC =
        StreamCodec.of(ClientboundAllvrLodForgetPacket::encode, ClientboundAllvrLodForgetPacket::decode);

    private static void encode(RegistryFriendlyByteBuf buf, ClientboundAllvrLodForgetPacket p) {
        buf.writeVarInt(p.level);
        buf.writeLong(p.cellLong);
    }

    private static ClientboundAllvrLodForgetPacket decode(RegistryFriendlyByteBuf buf) {
        return new ClientboundAllvrLodForgetPacket(buf.readVarInt(), buf.readLong());
    }

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }

    public static void handle(ClientboundAllvrLodForgetPacket packet, IPayloadContext ctx) {
        ctx.enqueueWork(() -> AllvrLodClientState.applyForget(packet));
    }
}
