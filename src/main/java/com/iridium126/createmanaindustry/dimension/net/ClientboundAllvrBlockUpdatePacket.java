package com.iridium126.createmanaindustry.dimension.net;

import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.neoforged.neoforge.network.handling.IPayloadContext;

import com.iridium126.createmanaindustry.CreateManaIndustry;
import com.iridium126.createmanaindustry.client.dimension.AllvrClientCubeCache;

/**
 * One authoritative server-side block change inside the allay dimension —
 * the cube analogue of {@code ClientboundBlockUpdatePacket} (whose narrow
 * {@code BlockPos#asLong} position cannot address cube-only Y). Wire: cube
 * long ({@code AllvrCubePos.asLong()}, 21 bit/axis), 15-bit in-cube cell
 * index (y&lt;&lt;10 | z&lt;&lt;5 | x, same layout as the BE/emitter cells),
 * global block-state id ({@code Block.getId} — identical on both sides from
 * the shared block-state registry).
 * <p>
 * Sent when a server {@code Level#setBlock} write carries flag 2 (vanilla
 * "send to clients") to every client subscribed to the cube; the client
 * applies it through {@code AllvrClientCubeCache.setBlock} with vanilla
 * confirmation semantics (flags 19, recursion 512) — unloaded cubes drop the
 * write. Block-entity payload is deliberately not carried: BE data still
 * travels with full cube packets (known limitation).
 */
public record ClientboundAllvrBlockUpdatePacket(long cubePos, int cellIndex, int stateId)
        implements CustomPacketPayload {

    public static final CustomPacketPayload.Type<ClientboundAllvrBlockUpdatePacket> TYPE =
        new CustomPacketPayload.Type<>(CreateManaIndustry.modLoc("allvr_block_update"));

    public static final StreamCodec<RegistryFriendlyByteBuf, ClientboundAllvrBlockUpdatePacket> STREAM_CODEC =
        StreamCodec.of(ClientboundAllvrBlockUpdatePacket::encode, ClientboundAllvrBlockUpdatePacket::decode);

    private static void encode(RegistryFriendlyByteBuf buffer, ClientboundAllvrBlockUpdatePacket p) {
        buffer.writeLong(p.cubePos);
        buffer.writeShort(p.cellIndex);
        buffer.writeVarInt(p.stateId);
    }

    private static ClientboundAllvrBlockUpdatePacket decode(RegistryFriendlyByteBuf buffer) {
        return new ClientboundAllvrBlockUpdatePacket(buffer.readLong(), buffer.readShort() & 0xFFFF,
            buffer.readVarInt());
    }

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }

    public static void handle(ClientboundAllvrBlockUpdatePacket packet, IPayloadContext ctx) {
        ctx.enqueueWork(() -> AllvrClientCubeCache.applyBlockUpdate(packet));
    }
}
