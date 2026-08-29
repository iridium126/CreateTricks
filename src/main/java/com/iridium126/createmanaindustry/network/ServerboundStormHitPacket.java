package com.iridium126.createmanaindustry.network;

import com.iridium126.createmanaindustry.CreateManaIndustry;

import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.neoforged.neoforge.network.handling.IPayloadContext;

/**
 * Melee hit report, client → server (~13 bytes). The server cannot validate
 * hits (member positions are client-side GPU state), so the attacking client
 * reports its local hit — the server applies rate limits, a damage cap and
 * dead-member checks, then decrements its authoritative HP and broadcasts a
 * {@link ClientboundStormDamagePacket} to every active player INCLUDING the
 * attacker (pure server circuit: the attacker does not apply damage locally).
 * <p>
 * Quantization: damage 0.25 steps (byte, cap 63.75 — beyond any vanilla
 * melee hit), knockback components 1/64 steps (signed byte; the values are
 * the pre-multiplied kb direction × strength, range ~±1.5), light the packed
 * {@code blockLight + 16·skyLight} (0..255 by construction), hit position
 * relative to the storm anchor at 1/16-block steps (shorts; members stay
 * within ~±300 blocks of the anchor).
 */
public record ServerboundStormHitPacket(
        int memberIdx, float damage, float kbX, float kbZ, float light,
        double relX, double relY, double relZ) implements CustomPacketPayload {

    public static final CustomPacketPayload.Type<ServerboundStormHitPacket> TYPE =
            new CustomPacketPayload.Type<>(CreateManaIndustry.modLoc("storm_hit"));

    public static final StreamCodec<RegistryFriendlyByteBuf, ServerboundStormHitPacket> STREAM_CODEC =
            StreamCodec.of(ServerboundStormHitPacket::encode, ServerboundStormHitPacket::decode);

    private static void encode(RegistryFriendlyByteBuf buffer, ServerboundStormHitPacket p) {
        ByteBufCodecs.VAR_INT.encode(buffer, p.memberIdx);
        buffer.writeByte(clamp(Math.round(p.damage * 4), 0, 255));
        buffer.writeByte(clamp(Math.round(p.kbX * 64), -128, 127));
        buffer.writeByte(clamp(Math.round(p.kbZ * 64), -128, 127));
        buffer.writeByte(clamp(Math.round(p.light), 0, 255));
        buffer.writeShort((short) clamp((int) Math.round(p.relX * 16), Short.MIN_VALUE, Short.MAX_VALUE));
        buffer.writeShort((short) clamp((int) Math.round(p.relY * 16), Short.MIN_VALUE, Short.MAX_VALUE));
        buffer.writeShort((short) clamp((int) Math.round(p.relZ * 16), Short.MIN_VALUE, Short.MAX_VALUE));
    }

    private static ServerboundStormHitPacket decode(RegistryFriendlyByteBuf buffer) {
        int memberIdx = ByteBufCodecs.VAR_INT.decode(buffer);
        float damage = (buffer.readByte() & 0xFF) / 4.0f;
        float kbX = buffer.readByte() / 64.0f;
        float kbZ = buffer.readByte() / 64.0f;
        float light = (buffer.readByte() & 0xFF) / 1.0f;
        double relX = buffer.readShort() / 16.0;
        double relY = buffer.readShort() / 16.0;
        double relZ = buffer.readShort() / 16.0;
        return new ServerboundStormHitPacket(memberIdx, damage, kbX, kbZ, light, relX, relY, relZ);
    }

    private static int clamp(int v, int lo, int hi) {
        return Math.max(lo, Math.min(hi, v));
    }

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }

    /** Called on the server. */
    public static void handle(ServerboundStormHitPacket packet, IPayloadContext ctx) {
        ctx.enqueueWork(() -> com.iridium126.createmanaindustry.content.allaystorm.AllayStormManager.handleHit(packet, ctx));
    }
}
