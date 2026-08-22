package com.iridium126.createmanaindustry.network;

import com.iridium126.createmanaindustry.CreateManaIndustry;
import com.iridium126.createmanaindustry.client.render.mist.MistClientHandler;

import net.minecraft.core.BlockPos;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.world.level.Level;
import net.neoforged.neoforge.fluids.FluidStack;
import net.neoforged.neoforge.network.PacketDistributor;
import net.neoforged.neoforge.network.handling.IPayloadContext;

/**
 * Syncs mist state to tracking clients. Sent when a recipe-based mist
 * activates or deactivates (basin recipes don't have the atomizer's
 * built-in BE sync).
 */
public record ClientboundMistSyncPacket(BlockPos pos, FluidStack fluid, int radius)
        implements CustomPacketPayload {

    public static final CustomPacketPayload.Type<ClientboundMistSyncPacket> TYPE =
            new CustomPacketPayload.Type<>(CreateManaIndustry.modLoc("mist_sync"));

    public static final StreamCodec<RegistryFriendlyByteBuf, ClientboundMistSyncPacket> STREAM_CODEC =
            StreamCodec.of(
                    (buffer, packet) -> {
                        BlockPos.STREAM_CODEC.encode(buffer, packet.pos);
                        ByteBufCodecs.VAR_INT.encode(buffer, packet.radius);
                        boolean hasFluid = !packet.fluid.isEmpty();
                        buffer.writeBoolean(hasFluid);
                        if (hasFluid) {
                            FluidStack.STREAM_CODEC.encode(buffer, packet.fluid);
                        }
                    },
                    buffer -> {
                        BlockPos pos = BlockPos.STREAM_CODEC.decode(buffer);
                        int radius = ByteBufCodecs.VAR_INT.decode(buffer);
                        boolean hasFluid = buffer.readBoolean();
                        FluidStack fluid = hasFluid ? FluidStack.STREAM_CODEC.decode(buffer) : FluidStack.EMPTY;
                        return new ClientboundMistSyncPacket(pos, fluid, radius);
                    });

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }

    /** Called on the client. */
    public static void handle(ClientboundMistSyncPacket packet, IPayloadContext ctx) {
        ctx.enqueueWork(() -> {
            // Veil is optional — without it MistClientHandler can't be loaded.
            // The guard short-circuits before the class reference is resolved.
            if (CreateManaIndustry.VEIL_ACTIVE)
                MistClientHandler.setActive(packet.pos, packet.fluid, packet.radius);
        });
    }

    /** Send to all players tracking the chunk containing {@code pos}. */
    public static void sendToTracking(Level level, BlockPos pos, FluidStack fluid, int radius) {
        if (level.isClientSide)
            return;
        PacketDistributor.sendToPlayersTrackingChunk(
                (net.minecraft.server.level.ServerLevel) level,
                level.getChunkAt(pos).getPos(),
                new ClientboundMistSyncPacket(pos.immutable(), fluid, radius));
    }
}
