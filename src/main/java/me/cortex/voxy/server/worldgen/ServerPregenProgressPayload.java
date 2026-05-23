package me.cortex.voxy.server.worldgen;

import me.cortex.voxy.VoxyMod;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

public record ServerPregenProgressPayload(
        long totalTarget,
        long totalRemaining,
        double chunksPerSecond,
        int activeTaskCount,
        byte pregenMode,
        boolean paused
) implements CustomPacketPayload {
    public static final CustomPacketPayload.Type<ServerPregenProgressPayload> TYPE =
            new CustomPacketPayload.Type<>(ResourceLocation.fromNamespaceAndPath(VoxyMod.MODID, "server_pregen_progress"));

    public static final StreamCodec<FriendlyByteBuf, ServerPregenProgressPayload> STREAM_CODEC =
            StreamCodec.of(
                    (buf, p) -> {
                        buf.writeLong(p.totalTarget);
                        buf.writeLong(p.totalRemaining);
                        buf.writeDouble(p.chunksPerSecond);
                        buf.writeVarInt(p.activeTaskCount);
                        buf.writeByte(p.pregenMode);
                        buf.writeBoolean(p.paused);
                    },
                    buf -> new ServerPregenProgressPayload(
                            buf.readLong(),
                            buf.readLong(),
                            buf.readDouble(),
                            buf.readVarInt(),
                            buf.readByte(),
                            buf.readBoolean()
                    )
            );

    @Override
    public CustomPacketPayload.Type<? extends CustomPacketPayload> type() { return TYPE; }
}
