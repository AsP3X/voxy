package me.cortex.voxy.server.worldgen;

import me.cortex.voxy.common.Logger;
import me.cortex.voxy.server.worldgen.VoxyWorldGenNetworking;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.TickTask;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

public final class ServerLodPayloadStore {
    private static final ServerLodPayloadStore INSTANCE = new ServerLodPayloadStore();
    private static final int MAGIC = 0x564F5859; // "VOXY"
    private static final int VERSION = 1;
    private static final int BATCH_SIZE = 128;

    private final Map<ResourceKey<Level>, Map<Long, VoxyWorldGenNetworking.LodColumnPayload>> cache
            = new ConcurrentHashMap<>();

    private ServerLodPayloadStore() {}

    public static ServerLodPayloadStore getInstance() {
        return INSTANCE;
    }

    /** Store a payload in memory. Overwrites any previous entry for the same chunk. */
    public void storeColumn(ResourceKey<Level> dimension, ChunkPos pos, int minY,
                            List<VoxyWorldGenNetworking.LodSectionPayload> sections) {
        if (sections == null || sections.isEmpty()) return;
        var dimCache = cache.computeIfAbsent(dimension, k -> new ConcurrentHashMap<>());
        dimCache.put(pos.toLong(), new VoxyWorldGenNetworking.LodColumnPayload(dimension, pos, minY, sections));
    }

    /** Check if the store has any data for a dimension. */
    public boolean hasDimension(ResourceKey<Level> dimension) {
        var dimCache = cache.get(dimension);
        return dimCache != null && !dimCache.isEmpty();
    }

    /** Get the number of stored columns for a dimension. */
    public int getColumnCount(ResourceKey<Level> dimension) {
        var dimCache = cache.get(dimension);
        return dimCache != null ? dimCache.size() : 0;
    }

    /**
     * Schedule a full sync of every stored LOD column for the player's current dimension.
     * Clears the player's synced-chunk tracking and sends payloads in small batches over ticks.
     */
    public void scheduleFullSync(ServerPlayer player) {
        var server = player.getServer();
        if (server == null) return;

        var synced = PlayerTracker.getInstance().getSyncedChunks(player.getUUID());
        if (synced != null) {
            synced.clear();
        }

        ResourceKey<Level> dim = player.level().dimension();
        var dimCache = cache.get(dim);
        if (dimCache == null || dimCache.isEmpty()) {
            return;
        }

        List<VoxyWorldGenNetworking.LodColumnPayload> payloads = new ArrayList<>(dimCache.values());
        server.tell(new TickTask(server.getTickCount() + 1,
                () -> runFullSyncStep(server, player.getUUID(), dim, payloads, 0)));
    }

    private void runFullSyncStep(MinecraftServer server, java.util.UUID playerId, ResourceKey<Level> dim,
                                 List<VoxyWorldGenNetworking.LodColumnPayload> payloads, int offset) {
        if (server == null) return;
        ServerPlayer player = server.getPlayerList().getPlayer(playerId);
        if (player == null) return;
        if (!player.level().dimension().equals(dim)) return;

        int end = Math.min(offset + BATCH_SIZE, payloads.size());
        for (int i = offset; i < end; i++) {
            VoxyWorldGenNetworking.safeSendToPlayer(player, payloads.get(i));
        }

        if (end < payloads.size()) {
            server.tell(new TickTask(server.getTickCount() + 1,
                    () -> runFullSyncStep(server, playerId, dim, payloads, end)));
        }
    }

    public void save(ServerLevel level) {
        ResourceKey<Level> dim = level.dimension();
        var dimCache = cache.get(dim);
        if (dimCache == null || dimCache.isEmpty()) return;

        Path path = getStorePath(level);
        if (path == null) return;

        try {
            Files.createDirectories(path.getParent());
            try (DataOutputStream out = new DataOutputStream(
                    new BufferedOutputStream(Files.newOutputStream(path)))) {
                out.writeInt(MAGIC);
                out.writeInt(VERSION);
                // Snapshot to avoid concurrent-modification mismatch between count and entries
                var snapshot = new ArrayList<>(dimCache.values());
                out.writeInt(snapshot.size());
                for (var payload : snapshot) {
                    ByteBuf raw = Unpooled.buffer();
                    RegistryFriendlyByteBuf buf = null;
                    try {
                        buf = new RegistryFriendlyByteBuf(
                                new FriendlyByteBuf(raw), level.registryAccess());
                        VoxyWorldGenNetworking.LodColumnPayload.STREAM_CODEC.encode(buf, payload);
                        byte[] bytes = new byte[buf.readableBytes()];
                        buf.readBytes(bytes);
                        out.writeInt(bytes.length);
                        out.write(bytes);
                    } finally {
                        if (buf != null) {
                            buf.release();
                        } else {
                            raw.release();
                        }
                    }
                }
            }
            Logger.info("Saved " + dimCache.size() + " LOD columns to " + path);
        } catch (Exception e) {
            Logger.error("Failed to save LOD payload store for " + dim, e);
        }
    }

    public void load(ServerLevel level) {
        ResourceKey<Level> dim = level.dimension();
        Path path = getStorePath(level);
        if (path == null || !Files.exists(path)) return;

        try (DataInputStream in = new DataInputStream(
                new BufferedInputStream(Files.newInputStream(path)))) {
            int magic = in.readInt();
            int version = in.readInt();
            if (magic != MAGIC || version != VERSION) {
                Logger.warn("LOD payload store file has wrong magic/version, skipping: " + path);
                return;
            }
            int count = in.readInt();
            if (count < 0 || count > 50_000_000) {
                Logger.warn("LOD payload store count out of bounds (" + count + "), skipping: " + path);
                return;
            }
            Map<Long, VoxyWorldGenNetworking.LodColumnPayload> dimCache = new HashMap<>();
            for (int i = 0; i < count; i++) {
                int len = in.readInt();
                if (len < 0 || len > 50_000_000) {
                    Logger.warn("LOD payload store entry length out of bounds (" + len + ") at index " + i + ", skipping remainder: " + path);
                    return;
                }
                byte[] bytes = new byte[len];
                in.readFully(bytes);
                RegistryFriendlyByteBuf buf = new RegistryFriendlyByteBuf(
                        new FriendlyByteBuf(Unpooled.wrappedBuffer(bytes)),
                        level.registryAccess());
                try {
                    var payload = VoxyWorldGenNetworking.LodColumnPayload.STREAM_CODEC.decode(buf);
                    dimCache.put(payload.pos().toLong(), payload);
                } finally {
                    buf.release();
                }
            }
            cache.put(dim, new ConcurrentHashMap<>(dimCache));
            Logger.info("Loaded " + dimCache.size() + " LOD columns from " + path);
        } catch (Exception e) {
            Logger.error("Failed to load LOD payload store for " + dim, e);
        }
    }

    private static Path getStorePath(ServerLevel level) {
        if (level == null) return null;
        var server = level.getServer();
        if (server == null) return null;
        String dimId = level.dimension().location().toString().replace(":", "_").replace("/", "_");
        return server.getWorldPath(net.minecraft.world.level.storage.LevelResource.ROOT)
                .resolve("voxy_lod_" + dimId + ".bin");
    }
}
