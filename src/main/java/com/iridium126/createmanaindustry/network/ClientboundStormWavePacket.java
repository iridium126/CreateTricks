package com.iridium126.createmanaindustry.network;

import com.iridium126.createmanaindustry.CreateManaIndustry;
import com.iridium126.createmanaindustry.content.allaystorm.AllayStormWaves;

import io.netty.handler.codec.DecoderException;

import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.neoforged.neoforge.network.handling.IPayloadContext;

/**
 * One dive-wave event, server → every active client (event-driven — a wave is
 * ~100 bytes once per {@code stormWaveInterval} per player, zero per-tick
 * traffic). Broadcast to ALL active players: every client must render the
 * same attack, so the squad membership, corridor and schedule ride the wire
 * as pure data and each client's GPU derives identical steering.
 * <p>
 * Fields: {@code waveId} (server counter, correlates contact reports),
 * {@code abort} flag (target invalidated mid-wave — the only early-exit
 * event; normal expiry is deterministic from the schedule on every client),
 * {@code waveSeed} (24-bit squad-selection salt), {@code fraction} (the
 * membership threshold the GPU and the server both test
 * {@code hash(memberSeed, waveSeed) < fraction} against),
 * {@code targetEntityId} (each client resolves the live position from its own
 * entity sync), the smoothed corridor waypoints (rel-to-anchor, 1/16 block
 * shorts — the same reconstruction frame as the hit/contact reports) and the
 * schedule in shared-clock seconds ({@code (gameTime mod 2^21)/20} — the
 * domain every storm timestamp already shares).
 */
public record ClientboundStormWavePacket(
        int waveId, boolean abort, int waveSeed, float fraction, int targetEntityId,
        float[] path, float assembleSec, float diveUntilSec) implements CustomPacketPayload {

    public static final CustomPacketPayload.Type<ClientboundStormWavePacket> TYPE =
            new CustomPacketPayload.Type<>(CreateManaIndustry.modLoc("storm_wave"));

    public static final StreamCodec<RegistryFriendlyByteBuf, ClientboundStormWavePacket> STREAM_CODEC =
            StreamCodec.of(ClientboundStormWavePacket::encode, ClientboundStormWavePacket::decode);

    private static void encode(RegistryFriendlyByteBuf buffer, ClientboundStormWavePacket p) {
        ByteBufCodecs.VAR_INT.encode(buffer, p.waveId);
        buffer.writeByte(p.abort ? 1 : 0);
        if (p.abort)
            return;
        ByteBufCodecs.VAR_INT.encode(buffer, p.waveSeed);
        buffer.writeFloat(p.fraction);
        ByteBufCodecs.VAR_INT.encode(buffer, p.targetEntityId);
        int points = p.path == null ? 0 : p.path.length / 3;
        ByteBufCodecs.VAR_INT.encode(buffer, points);
        for (int i = 0; i < points; i++) {
            buffer.writeShort(quant(p.path[i * 3]));
            buffer.writeShort(quant(p.path[i * 3 + 1]));
            buffer.writeShort(quant(p.path[i * 3 + 2]));
        }
        buffer.writeFloat(p.assembleSec);
        buffer.writeFloat(p.diveUntilSec);
    }

    private static short quant(double worldRel) {
        double rel = worldRel * 16.0;
        return (short) Math.max(Short.MIN_VALUE, Math.min(Short.MAX_VALUE, Math.round(rel)));
    }

    private static ClientboundStormWavePacket decode(RegistryFriendlyByteBuf buffer) {
        int waveId = ByteBufCodecs.VAR_INT.decode(buffer);
        boolean abort = (buffer.readByte() & 1) != 0;
        if (abort)
            return new ClientboundStormWavePacket(waveId, true, 0, 0, 0, null, 0, 0);
        int seed = ByteBufCodecs.VAR_INT.decode(buffer);
        float fraction = buffer.readFloat();
        int targetId = ByteBufCodecs.VAR_INT.decode(buffer);
        int points = ByteBufCodecs.VAR_INT.decode(buffer);
        if (points < 2 || points > AllayStormWaves.MAX_WAYPOINTS)
            throw new DecoderException("storm wave path point count out of bounds: " + points);
        float[] path = new float[points * 3];
        for (int i = 0; i < path.length; i++)
            path[i] = buffer.readShort() / 16.0f;
        float assemble = buffer.readFloat();
        float diveUntil = buffer.readFloat();
        return new ClientboundStormWavePacket(waveId, false, seed, fraction, targetId,
                path, assemble, diveUntil);
    }

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }

    /** Called on the client; body only executes client-side (see MistSyncPacket). */
    public static void handle(ClientboundStormWavePacket packet, IPayloadContext ctx) {
        ctx.enqueueWork(() ->
                com.iridium126.createmanaindustry.client.particles.allaystorm.AllayStormClientHandler
                        .onWave(packet));
    }
}
