package com.iridium126.createmanaindustry.network;

import com.iridium126.createmanaindustry.CreateManaIndustry;

import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.neoforged.neoforge.network.handling.IPayloadContext;

/**
 * Storm position snapshot, authority client → server, at the configured
 * {@code stormCorrectionHz}. Flat entry stride 7 floats:
 * {@code (memberIdx, px, py, pz, vx, vy, vz)} with positions RELATIVE to the
 * storm anchor and velocities in blocks/s. memberIdx rides a float slot —
 * exact for all values below 2^24, and 131072 is comfortably inside.
 * <p>
 * Wire quantization: position 1/16-block steps (short, ±2048 blocks around
 * the anchor), velocity 1/16-blocks-per-second steps (signed byte, ±8 b/s
 * covers the 6 b/s member speed cap). ~12 bytes per member; the snapshot is
 * capped at 256 members, so a full snapshot is ~3 KB.
 * <p>
 * The server cannot validate these positions (no GPU) — it applies a coarse
 * bounds check and relays the payload to the other active clients as a
 * {@link ClientboundStormPositionsPacket} (documented trust surface, same
 * tier as client-reported hit damage).
 */
public record ServerboundStormPositionsPacket(long gameTime, float[] entries) implements CustomPacketPayload {

    public static final int STRIDE = 8;

    public static final CustomPacketPayload.Type<ServerboundStormPositionsPacket> TYPE =
            new CustomPacketPayload.Type<>(CreateManaIndustry.modLoc("storm_positions_up"));

    public static final StreamCodec<RegistryFriendlyByteBuf, ServerboundStormPositionsPacket> STREAM_CODEC =
            StreamCodec.of(ServerboundStormPositionsPacket::encode, ServerboundStormPositionsPacket::decode);

    private static void encode(RegistryFriendlyByteBuf buffer, ServerboundStormPositionsPacket p) {
        ByteBufCodecs.VAR_LONG.encode(buffer, p.gameTime);
        int count = p.entries.length / STRIDE;
        ByteBufCodecs.VAR_INT.encode(buffer, count);
        for (int i = 0; i < count; i++) {
            int o = i * STRIDE;
            ByteBufCodecs.VAR_INT.encode(buffer, (int) p.entries[o]); // memberIdx (offset 1 is padding, skipped)
            buffer.writeShort(clamp((int) Math.round(p.entries[o + 2] * 16)));
            buffer.writeShort(clamp((int) Math.round(p.entries[o + 3] * 16)));
            buffer.writeShort(clamp((int) Math.round(p.entries[o + 4] * 16)));
            buffer.writeByte(clamp((int) Math.round(p.entries[o + 5] * 16)));
            buffer.writeByte(clamp((int) Math.round(p.entries[o + 6] * 16)));
            buffer.writeByte(clamp((int) Math.round(p.entries[o + 7] * 16)));
        }
    }

    private static ServerboundStormPositionsPacket decode(RegistryFriendlyByteBuf buffer) {
        long gameTime = ByteBufCodecs.VAR_LONG.decode(buffer);
        int count = ByteBufCodecs.VAR_INT.decode(buffer);
        float[] entries = new float[count * STRIDE];
        for (int i = 0; i < count; i++) {
            int o = i * STRIDE;
            entries[o] = ByteBufCodecs.VAR_INT.decode(buffer);
            entries[o + 1] = 0.0f; // padding slot, kept for the GPU staging layout
            entries[o + 2] = buffer.readShort() / 16.0f;
            entries[o + 3] = buffer.readShort() / 16.0f;
            entries[o + 4] = buffer.readShort() / 16.0f;
            entries[o + 5] = buffer.readByte() / 16.0f;
            entries[o + 6] = buffer.readByte() / 16.0f;
            entries[o + 7] = buffer.readByte() / 16.0f;
        }
        return new ServerboundStormPositionsPacket(gameTime, entries);
    }

    static int clamp(int v) {
        return Math.max(Short.MIN_VALUE, Math.min(Short.MAX_VALUE, v));
    }

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }

    /** Called on the server. */
    public static void handle(ServerboundStormPositionsPacket packet, IPayloadContext ctx) {
        ctx.enqueueWork(() -> com.iridium126.createmanaindustry.storm.StormManager.relayPositions(packet, ctx));
    }
}
