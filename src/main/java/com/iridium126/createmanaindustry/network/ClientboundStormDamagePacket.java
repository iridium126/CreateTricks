package com.iridium126.createmanaindustry.network;

import com.iridium126.createmanaindustry.CreateManaIndustry;

import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.neoforged.neoforge.network.handling.IPayloadContext;

/**
 * Storm member damage broadcast, server → clients (~14 bytes), sent to every
 * ACTIVE player including the attacker. Carries the authoritative death bit —
 * clients must never decide death from their local HP mirror (a client that
 * joined mid-fight has a 20 HP baseline and does not know past damage) — plus
 * the attacker-side hit position so every client eases the struck member to
 * the exact spot the hit landed before playing hurt-flash / corpse / poof
 * there (the one moment per-hit where sub-block agreement matters visually).
 * <p>
 * Knockback rides the same packet: every client applies the identical impulse
 * to the same member, which keeps post-hit divergence small during the death
 * animation window without any position traffic.
 */
public record ClientboundStormDamagePacket(
        int memberIdx, float damage, float kbX, float kbZ, float light,
        boolean died, double relX, double relY, double relZ) implements CustomPacketPayload {

    public static final CustomPacketPayload.Type<ClientboundStormDamagePacket> TYPE =
            new CustomPacketPayload.Type<>(CreateManaIndustry.modLoc("storm_damage"));

    public static final StreamCodec<RegistryFriendlyByteBuf, ClientboundStormDamagePacket> STREAM_CODEC =
            StreamCodec.of(ClientboundStormDamagePacket::encode, ClientboundStormDamagePacket::decode);

    private static void encode(RegistryFriendlyByteBuf buffer, ClientboundStormDamagePacket p) {
        ByteBufCodecs.VAR_INT.encode(buffer, p.memberIdx);
        buffer.writeByte(clamp(Math.round(p.damage * 4), 0, 255));
        buffer.writeByte(clamp(Math.round(p.kbX * 64), -128, 127));
        buffer.writeByte(clamp(Math.round(p.kbZ * 64), -128, 127));
        buffer.writeByte(clamp(Math.round(p.light), 0, 255));
        buffer.writeByte(p.died ? 1 : 0);
        buffer.writeShort((short) clamp((int) Math.round(p.relX * 16), Short.MIN_VALUE, Short.MAX_VALUE));
        buffer.writeShort((short) clamp((int) Math.round(p.relY * 16), Short.MIN_VALUE, Short.MAX_VALUE));
        buffer.writeShort((short) clamp((int) Math.round(p.relZ * 16), Short.MIN_VALUE, Short.MAX_VALUE));
    }

    private static ClientboundStormDamagePacket decode(RegistryFriendlyByteBuf buffer) {
        int memberIdx = ByteBufCodecs.VAR_INT.decode(buffer);
        float damage = (buffer.readByte() & 0xFF) / 4.0f;
        float kbX = buffer.readByte() / 64.0f;
        float kbZ = buffer.readByte() / 64.0f;
        float light = (buffer.readByte() & 0xFF) / 1.0f;
        boolean died = buffer.readByte() != 0;
        double relX = buffer.readShort() / 16.0;
        double relY = buffer.readShort() / 16.0;
        double relZ = buffer.readShort() / 16.0;
        return new ClientboundStormDamagePacket(memberIdx, damage, kbX, kbZ, light, died, relX, relY, relZ);
    }

    private static int clamp(int v, int lo, int hi) {
        return Math.max(lo, Math.min(hi, v));
    }

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }

    /** Called on the client; body only executes client-side (see MistSyncPacket). */
    public static void handle(ClientboundStormDamagePacket packet, IPayloadContext ctx) {
        ctx.enqueueWork(() -> com.iridium126.createmanaindustry.client.particles.allaystorm.AllayStormClientHandler.onDamage(packet));
    }
}
