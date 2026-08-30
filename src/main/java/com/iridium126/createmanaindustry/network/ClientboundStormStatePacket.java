package com.iridium126.createmanaindustry.network;

import com.iridium126.createmanaindustry.CreateManaIndustry;
import com.iridium126.createmanaindustry.content.allaystorm.AllayStormData;

import io.netty.handler.codec.DecoderException;

import net.minecraft.core.BlockPos;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.neoforged.neoforge.network.handling.IPayloadContext;

/**
 * Allay Storm lifecycle state, server → client, event-driven (zero per-tick
 * traffic). One packet covers four actions:
 * <ul>
 *   <li><b>ACTIVATE</b> (also the join / dimension-change / re-enter-range
 *       path): full parameters + stormSeed + the dead-member bitmap. The
 *       client spawns exactly the alive members with seed-derived identity,
 *       so member counts agree across clients and restarts by construction.
     *   <li><b>UPDATE</b>: anchor-only retarget; member
     *       identity, HP and deaths are preserved — springs retarget.
     *       The command's count argument only sizes a NEW storm; an active
     *       storm's population is a growing quantity and never restarts on it.
 *   <li><b>DEACTIVATE</b>: the player left the activation range — the client
 *       disperses its local swarm; server state is untouched.
 *   <li><b>STOP</b>: the storm is over — clients disperse and forget state.
 * </ul>
 * The {@code authority} bit tells THIS recipient it is the authority client
 * (runs the position-snapshot readback at {@code correctionHz}); it flips
 * only via re-sent UPDATE packets on handoff.
 * <p>
 * Wire format: anchor as {@code BlockPos} VAR_LONG, count varint (the
 * GENERATED population), seed varint, the FROZEN GROWTH LAW (creation radius,
 * final radius, growth rate — floats — and the creation gameTime long; the
 * vortex phase integrals are closed-form functions of these four constants,
 * identical on every client), dead bitmap length-prefixed raw bytes (worst
 * case 16 KB once per activation). The storm RADIUS and ANGULAR VELOCITY ride
 * NO wire: the client derives the radius from the synced population every
 * frame ({@code AllayStormData.vortexRadius}) and ω from the seed's low bit
 * ({@code AllayStormData.vortexOmega}).
 */
public record ClientboundStormStatePacket(
        int action, boolean authority, double correctionHz,
        BlockPos anchor, int count, int stormSeed,
        float creationRadius, float finalRadius, float growthPerSecond, long createdAt,
        byte[] deadBitmap) implements CustomPacketPayload {

    public static final int ACTION_ACTIVATE = 0;
    public static final int ACTION_UPDATE = 1;
    public static final int ACTION_DEACTIVATE = 2;
    public static final int ACTION_STOP = 3;

    /**
     * Maximum ACTIVATE dead-bitmap size the decoder accepts: the worst
     * legitimate case is one bit per member ({@code StormData.MAX_COUNT / 8}
     * = 16 KB — the server builds it from {@code BitSet.toByteArray()}). The
     * length is a raw VAR_INT and drives the byte array allocation, so a
     * longer value is a protocol violation: the decoder throws a
     * {@link DecoderException} and the network layer drops the connection
     * instead of allocating an attacker-sized array.
     */
    public static final int MAX_DEAD_BYTES = (AllayStormData.MAX_COUNT + 7) / 8;

    public static final ClientboundStormStatePacket ACTIVATE(BlockPos anchor, int count,
            int stormSeed, boolean authority, double hz, byte[] deadBitmap,
            float creationRadius, float finalRadius, float growthPerSecond, long createdAt) {
        return new ClientboundStormStatePacket(ACTION_ACTIVATE, authority, hz,
                anchor, count, stormSeed,
                creationRadius, finalRadius, growthPerSecond, createdAt, deadBitmap);
    }

    public static final ClientboundStormStatePacket UPDATE(BlockPos anchor, int count,
            int stormSeed, boolean authority, double hz,
            float creationRadius, float finalRadius, float growthPerSecond, long createdAt) {
        return new ClientboundStormStatePacket(ACTION_UPDATE, authority, hz,
                anchor, count, stormSeed,
                creationRadius, finalRadius, growthPerSecond, createdAt, null);
    }

    public static final ClientboundStormStatePacket DEACTIVATE() {
        return new ClientboundStormStatePacket(ACTION_DEACTIVATE, false, 0, BlockPos.ZERO, 0, 0,
                0, 0, 0, 0, null);
    }

    public static final ClientboundStormStatePacket STOP() {
        return new ClientboundStormStatePacket(ACTION_STOP, false, 0, BlockPos.ZERO, 0, 0,
                0, 0, 0, 0, null);
    }

    public static final CustomPacketPayload.Type<ClientboundStormStatePacket> TYPE =
            new CustomPacketPayload.Type<>(CreateManaIndustry.modLoc("storm_state"));

    public static final StreamCodec<RegistryFriendlyByteBuf, ClientboundStormStatePacket> STREAM_CODEC =
            StreamCodec.of(ClientboundStormStatePacket::encode, ClientboundStormStatePacket::decode);

    private static void encode(RegistryFriendlyByteBuf buffer, ClientboundStormStatePacket p) {
        buffer.writeByte((p.action & 3) | (p.authority ? 4 : 0));
        ByteBufCodecs.VAR_INT.encode(buffer, (int) Math.round(p.correctionHz * 10));
        if (p.action == ACTION_ACTIVATE || p.action == ACTION_UPDATE) {
            BlockPos.STREAM_CODEC.encode(buffer, p.anchor);
            ByteBufCodecs.VAR_INT.encode(buffer, p.count);
            ByteBufCodecs.VAR_INT.encode(buffer, p.stormSeed);
            buffer.writeFloat(p.creationRadius);
            buffer.writeFloat(p.finalRadius);
            buffer.writeFloat(p.growthPerSecond);
            buffer.writeLong(p.createdAt);
        }
        if (p.action == ACTION_ACTIVATE) {
            byte[] bm = p.deadBitmap == null ? new byte[0] : p.deadBitmap;
            ByteBufCodecs.VAR_INT.encode(buffer, bm.length);
            buffer.writeBytes(bm);
        }
    }

    private static ClientboundStormStatePacket decode(RegistryFriendlyByteBuf buffer) {
        int flags = buffer.readByte() & 0xFF;
        int action = flags & 3;
        boolean authority = (flags & 4) != 0;
        double hz = ByteBufCodecs.VAR_INT.decode(buffer) / 10.0;
        BlockPos anchor = BlockPos.ZERO;
        int count = 0;
        int seed = 0;
        float creationRadius = 0;
        float finalRadius = 0;
        float growthPerSecond = 0;
        long createdAt = 0;
        if (action == ACTION_ACTIVATE || action == ACTION_UPDATE) {
            anchor = BlockPos.STREAM_CODEC.decode(buffer);
            count = ByteBufCodecs.VAR_INT.decode(buffer);
            seed = ByteBufCodecs.VAR_INT.decode(buffer);
            creationRadius = buffer.readFloat();
            finalRadius = buffer.readFloat();
            growthPerSecond = buffer.readFloat();
            createdAt = buffer.readLong();
        }
        byte[] dead = null;
        if (action == ACTION_ACTIVATE) {
            int len = ByteBufCodecs.VAR_INT.decode(buffer);
            if (len < 0 || len > MAX_DEAD_BYTES)
                throw new DecoderException("storm dead bitmap length out of bounds: " + len);
            dead = new byte[len];
            buffer.readBytes(dead);
        }
        return new ClientboundStormStatePacket(action, authority, hz,
                anchor, count, seed,
                creationRadius, finalRadius, growthPerSecond, createdAt, dead);
    }

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }

    /** Called on the client; body only executes client-side (see MistSyncPacket). */
    public static void handle(ClientboundStormStatePacket packet, IPayloadContext ctx) {
        ctx.enqueueWork(() -> com.iridium126.createmanaindustry.client.particles.allaystorm.AllayStormClientHandler.onState(packet));
    }
}
