package me.cortex.voxy.server.worldgen;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import me.cortex.voxy.VoxyMod;
import net.minecraft.core.SectionPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.LightLayer;
import net.minecraft.world.level.chunk.DataLayer;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.LevelChunkSection;
import net.neoforged.neoforge.network.PacketDistributor;

import java.util.ArrayList;
import java.util.List;

public final class VoxyWorldGenNetworking {
    private static final int MAX_PACKET_BYTES = 32_768;

    public record HandshakePayload(boolean serverHasMod) implements CustomPacketPayload {
        public static final CustomPacketPayload.Type<HandshakePayload> TYPE =
                new CustomPacketPayload.Type<>(ResourceLocation.fromNamespaceAndPath(VoxyMod.MODID, "worldgen_handshake"));
        public static final StreamCodec<FriendlyByteBuf, HandshakePayload> STREAM_CODEC =
                StreamCodec.of((b, v) -> b.writeBoolean(v.serverHasMod()), b -> new HandshakePayload(b.readBoolean()));

        @Override
        public Type<? extends CustomPacketPayload> type() {
            return TYPE;
        }
    }

    public record LodSectionPayload(int y, byte[] states, byte[] biomes, byte[] blockLight, byte[] skyLight) {
        static void write(RegistryFriendlyByteBuf buf, LodSectionPayload s) {
            buf.writeInt(s.y);
            buf.writeByteArray(s.states);
            buf.writeByteArray(s.biomes);
            if (s.blockLight != null) {
                buf.writeBoolean(true);
                buf.writeByteArray(s.blockLight);
            } else {
                buf.writeBoolean(false);
            }
            if (s.skyLight != null) {
                buf.writeBoolean(true);
                buf.writeByteArray(s.skyLight);
            } else {
                buf.writeBoolean(false);
            }
        }

        static LodSectionPayload read(RegistryFriendlyByteBuf buf) {
            int y = buf.readInt();
            byte[] states = buf.readByteArray();
            byte[] biomes = buf.readByteArray();
            byte[] bl = buf.readBoolean() ? buf.readByteArray() : null;
            byte[] sl = buf.readBoolean() ? buf.readByteArray() : null;
            return new LodSectionPayload(y, states, biomes, bl, sl);
        }
    }

    public record LodColumnPayload(ResourceKey<Level> dimension, ChunkPos pos, int minY, List<LodSectionPayload> sections) implements CustomPacketPayload {
        public static final CustomPacketPayload.Type<LodColumnPayload> TYPE =
                new CustomPacketPayload.Type<>(ResourceLocation.fromNamespaceAndPath(VoxyMod.MODID, "lod_column"));
        public static final StreamCodec<RegistryFriendlyByteBuf, LodColumnPayload> STREAM_CODEC =
                StreamCodec.of(LodColumnPayload::writeBuf, LodColumnPayload::readBuf);

        private static void writeBuf(RegistryFriendlyByteBuf buf, LodColumnPayload p) {
            buf.writeResourceKey(p.dimension);
            buf.writeChunkPos(p.pos);
            buf.writeVarInt(p.minY);
            buf.writeVarInt(p.sections.size());
            for (LodSectionPayload s : p.sections) {
                LodSectionPayload.write(buf, s);
            }
        }

        private static LodColumnPayload readBuf(RegistryFriendlyByteBuf buf) {
            ResourceKey<Level> dim = buf.readResourceKey(Registries.DIMENSION);
            ChunkPos cpos = buf.readChunkPos();
            int minY = buf.readVarInt();
            int n = buf.readVarInt();
            List<LodSectionPayload> secs = new ArrayList<>(n);
            for (int i = 0; i < n; i++) {
                secs.add(LodSectionPayload.read(buf));
            }
            return new LodColumnPayload(dim, cpos, minY, secs);
        }

        @Override
        public Type<? extends CustomPacketPayload> type() {
            return TYPE;
        }
    }

    private VoxyWorldGenNetworking() {}

    public static void broadcastLODData(LevelChunk chunk) {
        ChunkPos pos = chunk.getPos();
        int minY = chunk.getMinSection();
        List<LodSectionPayload> sections = buildSections(chunk);
        if (sections.isEmpty()) return;

        double maxDistSq = 4096.0 * 4096.0;

        for (ServerPlayer player : PlayerTracker.getInstance().getPlayers()) {
            double dx = player.getX() - pos.getMiddleBlockX();
            double dz = player.getZ() - pos.getMiddleBlockZ();

            if (player.level() != chunk.getLevel() || (dx * dx + dz * dz > maxDistSq)) {
                setSyncedState(player, pos, false);
                continue;
            }

            sendSectionsInBatches(player, chunk.getLevel().dimension(), pos, minY, sections);
        }
    }

    public static void sendLODData(ServerPlayer player, LevelChunk chunk) {
        ChunkPos pos = chunk.getPos();
        int minY = chunk.getMinSection();
        List<LodSectionPayload> sections = buildSections(chunk);
        if (sections.isEmpty()) {
            setSyncedState(player, pos, false);
            return;
        }
        sendSectionsInBatches(player, chunk.getLevel().dimension(), pos, minY, sections);
        setSyncedState(player, pos, true);
    }

    private static void setSyncedState(ServerPlayer player, ChunkPos pos, boolean isSynced) {
        var synced = PlayerTracker.getInstance().getSyncedChunks(player.getUUID());
        if (synced != null) {
            if (isSynced) {
                synced.add(pos.toLong());
            } else {
                synced.remove(pos.toLong());
            }
        }
    }

    private static List<LodSectionPayload> buildSections(LevelChunk chunk) {
        ChunkPos pos = chunk.getPos();
        int minY = chunk.getMinSection();
        List<LodSectionPayload> sections = new ArrayList<>();
        var lightEngine = chunk.getLevel().getLightEngine();

        for (int i = 0; i < chunk.getSections().length; i++) {
            LevelChunkSection section = chunk.getSections()[i];
            if (section == null || section.hasOnlyAir()) continue;

            ByteBuf statesRaw = Unpooled.buffer();
            ByteBuf biomesRaw = Unpooled.buffer();
            byte[] states;
            byte[] biomes;
            try {
                RegistryFriendlyByteBuf statesBuf = new RegistryFriendlyByteBuf(new FriendlyByteBuf(statesRaw), chunk.getLevel().registryAccess());
                section.getStates().write(statesBuf);
                states = new byte[statesBuf.readableBytes()];
                statesBuf.readBytes(states);

                RegistryFriendlyByteBuf biomesBuf = new RegistryFriendlyByteBuf(new FriendlyByteBuf(biomesRaw), chunk.getLevel().registryAccess());
                section.getBiomes().write(biomesBuf);
                biomes = new byte[biomesBuf.readableBytes()];
                biomesBuf.readBytes(biomes);
            } finally {
                statesRaw.release();
                biomesRaw.release();
            }

            SectionPos sectionPos = SectionPos.of(pos, minY + i);
            DataLayer bl = lightEngine.getLayerListener(LightLayer.BLOCK).getDataLayerData(sectionPos);
            DataLayer sl = lightEngine.getLayerListener(LightLayer.SKY).getDataLayerData(sectionPos);

            sections.add(new LodSectionPayload(
                    minY + i,
                    states,
                    biomes,
                    bl != null ? bl.getData().clone() : null,
                    sl != null ? sl.getData().clone() : null));
        }
        return sections;
    }

    private static void sendSectionsInBatches(ServerPlayer player, ResourceKey<Level> dimension, ChunkPos pos, int minY, List<LodSectionPayload> sections) {
        List<LodSectionPayload> batch = new ArrayList<>();
        int batchBytes = 0;

        for (LodSectionPayload sd : sections) {
            int sectionBytes = sd.states().length + sd.biomes().length
                    + (sd.blockLight() != null ? sd.blockLight().length : 0)
                    + (sd.skyLight() != null ? sd.skyLight().length : 0);

            if (!batch.isEmpty() && batchBytes + sectionBytes > MAX_PACKET_BYTES) {
                PacketDistributor.sendToPlayer(player, new LodColumnPayload(dimension, pos, minY, batch));
                batch = new ArrayList<>();
                batchBytes = 0;
            }

            batch.add(sd);
            batchBytes += sectionBytes;
        }

        if (!batch.isEmpty()) {
            PacketDistributor.sendToPlayer(player, new LodColumnPayload(dimension, pos, minY, batch));
        }
    }

    public static void sendHandshake(ServerPlayer player) {
        PacketDistributor.sendToPlayer(player, new HandshakePayload(true));
    }
}
