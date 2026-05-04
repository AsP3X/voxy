package me.cortex.voxy.server.worldgen;

import me.cortex.voxy.common.Logger;
import me.cortex.voxy.server.worldgen.VoxyWorldGenNetworking;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;

import io.netty.buffer.Unpooled;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

public final class ServerLodPayloadStore {
    private static final ServerLodPayloadStore INSTANCE = new ServerLodPayloadStore();
    private static final int MAGIC = 0x564F5859; // "VOXY"
    private static final int VERSION = 1;
    private static final int BATCH_SIZE = 128;

    record VersionedColumn(VoxyWorldGenNetworking.LodColumnPayload payload, long storeVersion) {}

    private final AtomicLong storeGeneration = new AtomicLong(0);

    private final Map<ResourceKey<Level>, Map<Long, VersionedColumn>> cache
            = new ConcurrentHashMap<>();

    private ServerLodPayloadStore() {}

    public static ServerLodPayloadStore getInstance() {
        return INSTANCE;
    }

    /** Store a payload in memory. Overwrites any previous entry for the same chunk. */
    public void storeColumn(ResourceKey<Level> dimension, ChunkPos pos, int minY,
                            List<VoxyWorldGenNetworking.LodSectionPayload> sections) {
        if (sections == null || sections.isEmpty()) return;
        long version = storeGeneration.getAndIncrement();
        var dimCache = cache.computeIfAbsent(dimension, k -> new ConcurrentHashMap<>());
        dimCache.put(pos.toLong(), new VersionedColumn(
                new VoxyWorldGenNetworking.LodColumnPayload(dimension, pos, minY, sections), version));
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

    static List<VersionedColumn> collectDelta(Map<Long, VersionedColumn> dimCache,
                                               long watermark, ChunkPos playerChunk) {
        return dimCache.values().stream()
                .filter(vc -> vc.storeVersion() > watermark)
                .sorted(java.util.Comparator.comparingInt(vc -> chebyshev(vc.payload().pos(), playerChunk)))
                .toList();
    }

    private static int chebyshev(ChunkPos a, ChunkPos b) {
        return Math.max(Math.abs(a.x - b.x), Math.abs(a.z - b.z));
    }

    /**
     * Schedule a full sync of every stored LOD column for the player's current dimension.
     */
    public void scheduleFullSync(ServerPlayer player) {
        scheduleDeltaSync(player); // replaced in Task 5
    }

    public void scheduleDeltaSync(ServerPlayer player) {
        // TODO: implemented in Task 5
    }

    public void save(ServerLevel level) {
        // TODO: updated in Task 3
    }

    public void load(ServerLevel level) {
        // TODO: updated in Task 3
    }

    private static java.nio.file.Path getStorePath(ServerLevel level) {
        if (level == null) return null;
        var server = level.getServer();
        if (server == null) return null;
        String dimId = level.dimension().location().toString().replace(":", "_").replace("/", "_");
        return server.getWorldPath(net.minecraft.world.level.storage.LevelResource.ROOT)
                .resolve("voxy_lod_" + dimId + ".bin");
    }
}
