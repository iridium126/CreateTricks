package com.iridium126.createmanaindustry.network;

import com.iridium126.createmanaindustry.CreateManaIndustry;

import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.neoforged.neoforge.network.handling.IPayloadContext;

/**
 * Continuous storm-center snapshot, server → clients (~24 bytes), broadcast
 * every {@code AllayStormManager.CENTER_INTERVAL_TICKS} (20 ticks) and once
 * immediately on activation. The chased center integrates as doubles
 * server-side at {@code AllayStormManager.CHASE_SPEED}; clients lerp the last
 * TWO snapshots one interval behind (entity-style interpolation), so the
 * center every client feeds its servo targets is stream-derivable and
 * identical across clients — no per-client divergence, and the packet rate
 * stays at 1 Hz while the center moves continuously.
 * <p>
 * The chase velocity rides along so a late/missed packet can be reasoned
 * about (and future paths may extrapolate); the current client interpolation
 * clamps at the newest snapshot — a stalled center moves 0 blocks/s versus
 * the truth's 2 b/s for under a second, which is invisible.
 */
public record ClientboundStormCenterPacket(
        float x, float y, float z, float velX, float velZ, long gameTime) implements CustomPacketPayload {

    public static final CustomPacketPayload.Type<ClientboundStormCenterPacket> TYPE =
            new CustomPacketPayload.Type<>(CreateManaIndustry.modLoc("storm_center"));

    public static final StreamCodec<RegistryFriendlyByteBuf, ClientboundStormCenterPacket> STREAM_CODEC =
            StreamCodec.of(ClientboundStormCenterPacket::encode, ClientboundStormCenterPacket::decode);

    private static void encode(RegistryFriendlyByteBuf buffer, ClientboundStormCenterPacket p) {
        buffer.writeFloat(p.x);
        buffer.writeFloat(p.y);
        buffer.writeFloat(p.z);
        buffer.writeFloat(p.velX);
        buffer.writeFloat(p.velZ);
        buffer.writeLong(p.gameTime);
    }

    private static ClientboundStormCenterPacket decode(RegistryFriendlyByteBuf buffer) {
        float x = buffer.readFloat();
        float y = buffer.readFloat();
        float z = buffer.readFloat();
        float velX = buffer.readFloat();
        float velZ = buffer.readFloat();
        long gameTime = buffer.readLong();
        return new ClientboundStormCenterPacket(x, y, z, velX, velZ, gameTime);
    }

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }

    /** Called on the client; body only executes client-side (see MistSyncPacket). */
    public static void handle(ClientboundStormCenterPacket packet, IPayloadContext ctx) {
        ctx.enqueueWork(() -> com.iridium126.createmanaindustry.client.particles.allaystorm.AllayStormClientHandler.onCenter(packet));
    }
}
