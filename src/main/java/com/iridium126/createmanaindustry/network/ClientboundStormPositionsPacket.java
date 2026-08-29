package com.iridium126.createmanaindustry.network;

import com.iridium126.createmanaindustry.CreateManaIndustry;

import io.netty.handler.codec.DecoderException;

import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.neoforged.neoforge.network.handling.IPayloadContext;

/**
 * Storm position snapshot relay, server → clients (every active player
 * EXCEPT the authority). Content and wire format are identical to
 * {@link ServerboundStormPositionsPacket} — the server relays the authority's
 * payload without re-quantization; the {@code gameTime} stamp lets receivers
 * extrapolate targets by the shared clock instead of trusting stale
 * positions.
 * <p>
 * Clients write each entry into the per-member correction-slot buffer; the
 * update compute pass eases members toward the extrapolated target with a
 * strength that ramps up as the snapshot ages (early window = local physics
 * rules, late window = converge). Corrections are soft: if the stream stops
 * (handoff gap, packet loss) local simulation simply continues.
 */
public record ClientboundStormPositionsPacket(long gameTime, float[] entries) implements CustomPacketPayload {

    public static final CustomPacketPayload.Type<ClientboundStormPositionsPacket> TYPE =
            new CustomPacketPayload.Type<>(CreateManaIndustry.modLoc("storm_positions_down"));

    public static final StreamCodec<RegistryFriendlyByteBuf, ClientboundStormPositionsPacket> STREAM_CODEC =
            StreamCodec.of(ClientboundStormPositionsPacket::encode, ClientboundStormPositionsPacket::decode);

    private static void encode(RegistryFriendlyByteBuf buffer, ClientboundStormPositionsPacket p) {
        ByteBufCodecs.VAR_LONG.encode(buffer, p.gameTime);
        int count = p.entries.length / ServerboundStormPositionsPacket.STRIDE;
        ByteBufCodecs.VAR_INT.encode(buffer, count);
        for (int i = 0; i < count; i++) {
            int o = i * ServerboundStormPositionsPacket.STRIDE;
            ByteBufCodecs.VAR_INT.encode(buffer, (int) p.entries[o]); // memberIdx (offset 1 is padding, skipped)
            buffer.writeShort(ServerboundStormPositionsPacket.clamp((int) Math.round(p.entries[o + 2] * 16)));
            buffer.writeShort(ServerboundStormPositionsPacket.clamp((int) Math.round(p.entries[o + 3] * 16)));
            buffer.writeShort(ServerboundStormPositionsPacket.clamp((int) Math.round(p.entries[o + 4] * 16)));
            buffer.writeByte(clampByte((int) Math.round(p.entries[o + 5] * 16)));
            buffer.writeByte(clampByte((int) Math.round(p.entries[o + 6] * 16)));
            buffer.writeByte(clampByte((int) Math.round(p.entries[o + 7] * 16)));
        }
    }

    private static ClientboundStormPositionsPacket decode(RegistryFriendlyByteBuf buffer) {
        long gameTime = ByteBufCodecs.VAR_LONG.decode(buffer);
        int count = ByteBufCodecs.VAR_INT.decode(buffer);
        // Same cap as the upstream Serverbound packet (the server relays its
        // payload verbatim, so a legit snapshot can never exceed it). The
        // count drives the entry array allocation — an out-of-bounds value is
        // a protocol violation and kills the connection instead.
        if (count < 0 || count > ServerboundStormPositionsPacket.MAX_ENTRIES)
            throw new DecoderException("storm positions snapshot count out of bounds: " + count);
        float[] entries = new float[count * ServerboundStormPositionsPacket.STRIDE];
        for (int i = 0; i < count; i++) {
            int o = i * ServerboundStormPositionsPacket.STRIDE;
            entries[o] = ByteBufCodecs.VAR_INT.decode(buffer);
            entries[o + 1] = 0.0f; // padding slot, kept for the GPU staging layout
            entries[o + 2] = buffer.readShort() / 16.0f;
            entries[o + 3] = buffer.readShort() / 16.0f;
            entries[o + 4] = buffer.readShort() / 16.0f;
            entries[o + 5] = buffer.readByte() / 16.0f;
            entries[o + 6] = buffer.readByte() / 16.0f;
            entries[o + 7] = buffer.readByte() / 16.0f;
        }
        return new ClientboundStormPositionsPacket(gameTime, entries);
    }

    private static int clampByte(int v) {
        return Math.max(Byte.MIN_VALUE, Math.min(Byte.MAX_VALUE, v));
    }

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }

    /** Called on the client; body only executes client-side (see MistSyncPacket). */
    public static void handle(ClientboundStormPositionsPacket packet, IPayloadContext ctx) {
        ctx.enqueueWork(() -> com.iridium126.createmanaindustry.client.particles.allaystorm.AllayStormClientHandler.onPositions(packet));
    }
}
