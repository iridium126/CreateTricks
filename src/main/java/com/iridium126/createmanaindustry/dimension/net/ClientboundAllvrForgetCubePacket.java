package com.iridium126.createmanaindustry.dimension.net;

import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.neoforged.neoforge.network.handling.IPayloadContext;

import com.iridium126.createmanaindustry.CreateManaIndustry;
import com.iridium126.createmanaindustry.client.dimension.AllvrClientCubeCache;

/**
 * Tells the client to drop one previously streamed cube (player moved out of
 * subscription range, hysteresis margin included). Position is
 * {@code AllvrCubePos.asLong()}.
 */
public record ClientboundAllvrForgetCubePacket(long cubePos) implements CustomPacketPayload {

    public static final CustomPacketPayload.Type<ClientboundAllvrForgetCubePacket> TYPE =
        new CustomPacketPayload.Type<>(CreateManaIndustry.modLoc("allvr_forget_cube"));

    public static final StreamCodec<RegistryFriendlyByteBuf, ClientboundAllvrForgetCubePacket> STREAM_CODEC =
        StreamCodec.of(ClientboundAllvrForgetCubePacket::encode, ClientboundAllvrForgetCubePacket::decode);

    private static void encode(RegistryFriendlyByteBuf buffer, ClientboundAllvrForgetCubePacket p) {
        buffer.writeLong(p.cubePos);
    }

    private static ClientboundAllvrForgetCubePacket decode(RegistryFriendlyByteBuf buffer) {
        return new ClientboundAllvrForgetCubePacket(buffer.readLong());
    }

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }

    public static void handle(ClientboundAllvrForgetCubePacket packet, IPayloadContext ctx) {
        ctx.enqueueWork(() -> AllvrClientCubeCache.forgetCube(packet.cubePos));
    }
}
