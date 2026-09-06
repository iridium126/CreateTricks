package com.iridium126.createmanaindustry.dimension.net;

import java.util.ArrayList;
import java.util.List;

import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.neoforged.neoforge.network.handling.IPayloadContext;

import com.iridium126.createmanaindustry.CreateManaIndustry;
import com.iridium126.createmanaindustry.dimension.AllvrDimensions;
import com.iridium126.createmanaindustry.dimension.cube.AllvrServerLevelDuck;
import com.iridium126.createmanaindustry.dimension.lod.AllvrLodMap;

/**
 * Batched C2S mesh requests for LOD nodes (doc §13 4c): pairs of
 * {@code (level byte, cellLong)} — batching 16 per packet keeps the
 * 64-requests/tick pacing at 4 packets/tick instead of 64. Invalid entries
 * (wrong dimension, out of range, over the in-flight cap) are answered with
 * a forget so the client's pending set cannot leak.
 */
public record ServerboundAllvrLodRequestPacket(long[] flat) implements CustomPacketPayload {

    public static final int MAX_ENTRIES = 16;

    public static final CustomPacketPayload.Type<ServerboundAllvrLodRequestPacket> TYPE =
        new CustomPacketPayload.Type<>(CreateManaIndustry.modLoc("allvr_lod_request"));

    public static final StreamCodec<RegistryFriendlyByteBuf, ServerboundAllvrLodRequestPacket> STREAM_CODEC =
        StreamCodec.of(ServerboundAllvrLodRequestPacket::encode, ServerboundAllvrLodRequestPacket::decode);

    private static void encode(RegistryFriendlyByteBuf buf, ServerboundAllvrLodRequestPacket p) {
        buf.writeVarInt(p.flat.length / 2);
        for (long v : p.flat) {
            buf.writeLong(v);
        }
    }

    private static ServerboundAllvrLodRequestPacket decode(RegistryFriendlyByteBuf buf) {
        int n = Math.min(buf.readVarInt(), MAX_ENTRIES);
        long[] flat = new long[n * 2];
        for (int i = 0; i < flat.length; i++) {
            flat[i] = buf.readLong();
        }
        return new ServerboundAllvrLodRequestPacket(flat);
    }

    /** level/cell pairs flattened as {level, cellLong} — built by the client. */
    public static ServerboundAllvrLodRequestPacket of(List<long[]> entries) {
        long[] flat = new long[entries.size() * 2];
        int i = 0;
        for (long[] e : entries) {
            flat[i++] = e[0];
            flat[i++] = e[1];
        }
        return new ServerboundAllvrLodRequestPacket(flat);
    }

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }

    public static void handle(ServerboundAllvrLodRequestPacket packet, IPayloadContext ctx) {
        if (!(ctx.player() instanceof net.minecraft.server.level.ServerPlayer player)
            || player.level().dimension() != AllvrDimensions.ALLAY_LEVEL) {
            return;
        }
        List<long[]> entries = new ArrayList<>(packet.flat.length / 2);
        for (int i = 0; i + 1 < packet.flat.length; i += 2) {
            entries.add(new long[] {packet.flat[i], packet.flat[i + 1]});
        }
        // the lazy duck access creates the maps — keep it on the server thread
        ctx.enqueueWork(() -> {
            AllvrLodMap lodMap = ((AllvrServerLevelDuck) player.level()).allvr$getLodMap();
            if (lodMap != null) {
                lodMap.onRequest(player, entries);
            }
        });
    }
}
