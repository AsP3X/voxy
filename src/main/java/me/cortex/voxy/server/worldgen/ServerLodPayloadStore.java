package me.cortex.voxy.server.worldgen;

import me.cortex.voxy.common.Logger;
import me.cortex.voxy.server.worldgen.VoxyWorldGenNetworking;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.TickTask;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;

import io.netty.buffer.Unpooled;
import io.netty.buffer.ByteBuf;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;

public final class ServerLodPayloadStore {
    private static final ServerLodPayloadStore INSTANCE = new ServerLodPayloadStore();
    private static final int MAGIC = 0x564F5859; // "VOXY"
    private static final int VERSION = 2;
    private static final int BATCH_SIZE = 32;
    /** Minimum real-time milliseconds between LOD sync batches to prevent burst-flooding
     *  Netty's outbound buffer during server tick catch-up (avoids keepalive timeout). */
    private static final long BATCH_INTERVAL_MS = 50;

    record VersionedColumn(VoxyWorldGenNetworking.LodColumnPayload payload, long storeVersion) {}

    private final AtomicLong storeGeneration = new AtomicLong(0);
    private final ConcurrentHashMap<UUID, Long> nextBatchTimeMs = new ConcurrentHashMap<>();

    private final Map<ResourceKey<Level>, Map<Long, VersionedColumn>> cache
            = new ConcurrentHashMap<>();
    /** Guard so overlapping auto-saves for the same dimension do not race on temp files. */
    private final Set<ResourceKey<Level>> saving = ConcurrentHashMap.newKeySet();

    private ServerLodPayloadStore() {}

    public static ServerLodPayloadStore getInstance() {
        return INSTANCE;
    }

    /** Remove per-player rate-limit state when a player disconnects. */
    public void clearPlayer(UUID playerId) {
        nextBatchTimeMs.remove(playerId);
    }

    /** Store a payload in memory. Skips the update (and version bump) if the cached data is
     *  byte-for-byte identical, preventing spurious delta-syncs caused by chunk re-loads. */
    public void storeColumn(ResourceKey<Level> dimension, ChunkPos pos, int minY,
                            List<VoxyWorldGenNetworking.LodSectionPayload> sections) {
        if (sections == null || sections.isEmpty()) return;
        var dimCache = cache.computeIfAbsent(dimension, k -> new ConcurrentHashMap<>());
        long posKey = pos.toLong();
        VersionedColumn existing = dimCache.get(posKey);
        if (existing != null && sectionsEqual(existing.payload().sections(), sections)) return;
        long version = storeGeneration.getAndIncrement();
        dimCache.put(posKey, new VersionedColumn(
                new VoxyWorldGenNetworking.LodColumnPayload(dimension, pos, minY, sections), version));
    }

    private static boolean sectionsEqual(List<VoxyWorldGenNetworking.LodSectionPayload> a,
                                         List<VoxyWorldGenNetworking.LodSectionPayload> b) {
        if (a.size() != b.size()) return false;
        for (int i = 0; i < a.size(); i++) {
            var sa = a.get(i);
            var sb = b.get(i);
            if (sa.y() != sb.y()) return false;
            if (!java.util.Arrays.equals(sa.states(), sb.states())) return false;
            if (!java.util.Arrays.equals(sa.biomes(), sb.biomes())) return false;
        }
        return true;
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
                .filter(vc -> watermark == 0 || vc.storeVersion() > watermark)
                .sorted(java.util.Comparator.comparingInt(vc -> chebyshev(vc.payload().pos(), playerChunk)))
                .toList();
    }

    private static int chebyshev(ChunkPos a, ChunkPos b) {
        return Math.max(Math.abs(a.x - b.x), Math.abs(a.z - b.z));
    }

    /** Delegates to {@link #scheduleDeltaSync}. Callers with a zero watermark get a full sync. */
    public void scheduleFullSync(ServerPlayer player) {
        scheduleDeltaSync(player);
    }

    public void scheduleDeltaSync(ServerPlayer player) {
        var server = player.getServer();
        if (server == null) return;

        UUID playerId = player.getUUID();
        ResourceKey<Level> dim = player.level().dimension();
        long watermark = PlayerSyncStateStore.getInstance().getWatermark(playerId, dim);
        long snapshotGeneration = storeGeneration.get();

        var dimCache = cache.get(dim);
        if (dimCache == null || dimCache.isEmpty()) return;

        List<VersionedColumn> delta = collectDelta(dimCache, watermark, player.chunkPosition());
        if (delta.isEmpty()) {
            // Do not advance the watermark when there is nothing to send.
            // The cache may still be loading from disk on a background thread,
            // and bumping the watermark now would permanently skip older entries
            // that arrive after the load finishes.
            return;
        }

        server.tell(new TickTask(server.getTickCount() + 1,
                () -> runDeltaSyncStep(server, playerId, dim, delta, 0, snapshotGeneration)));
    }

    private void runDeltaSyncStep(MinecraftServer server,
                                  UUID playerId, ResourceKey<Level> dim,
                                  List<VersionedColumn> delta, int offset,
                                  long snapshotGeneration) {
        ServerPlayer player = server.getPlayerList().getPlayer(playerId);
        if (player == null) return;
        if (!player.level().dimension().equals(dim)) return;

        // Enforce minimum wall-clock interval between batches.
        // During server tick catch-up, tick+1 tasks fire back-to-back with no real-time delay,
        // which would burst all 76k+ columns into Netty's outbound buffer simultaneously and
        // starve keepalive packets, causing a 30-second timeout disconnect.
        long now = System.currentTimeMillis();
        long nextSend = nextBatchTimeMs.getOrDefault(playerId, 0L);
        if (now < nextSend) {
            server.tell(new TickTask(server.getTickCount() + 1,
                    () -> runDeltaSyncStep(server, playerId, dim, delta, offset, snapshotGeneration)));
            return;
        }
        nextBatchTimeMs.put(playerId, now + BATCH_INTERVAL_MS);

        var synced = PlayerTracker.getInstance().getSyncedChunks(playerId);
        int end = Math.min(offset + BATCH_SIZE, delta.size());
        for (int i = offset; i < end; i++) {
            var vc = delta.get(i);
            long posKey = vc.payload().pos().toLong();
            if (synced != null && synced.contains(posKey)) continue;
            VoxyWorldGenNetworking.safeSendToPlayer(player, vc.payload());
            if (synced != null) synced.add(posKey);
        }

        if (end < delta.size()) {
            server.tell(new TickTask(server.getTickCount() + 1,
                    () -> runDeltaSyncStep(server, playerId, dim, delta, end, snapshotGeneration)));
        } else {
            PlayerSyncStateStore.getInstance().setWatermark(playerId, dim, snapshotGeneration);
            VoxyWorldGenNetworking.safeSendToPlayer(player,
                    new VoxyWorldGenNetworking.SyncCompletePayload(delta.size()));
        }
    }

    public void save(ServerLevel level) {
        ResourceKey<Level> dim = level.dimension();
        var dimCache = cache.get(dim);
        if (dimCache == null || dimCache.isEmpty()) return;

        Path path = getStorePath(level);
        if (path == null) return;
        if (!saving.add(dim)) return; // another save for this dim is already running

        // Snapshot references on the caller thread (fast), then serialize + write in background.
        var snapshot = new java.util.ArrayList<>(dimCache.values());
        Path tmp = path.resolveSibling(path.getFileName() + ".tmp");
        Thread t = new Thread(() -> {
            try {
                Files.createDirectories(path.getParent());
                try (DataOutputStream out = new DataOutputStream(
                        new BufferedOutputStream(Files.newOutputStream(tmp)))) {
                    out.writeInt(MAGIC);
                    out.writeInt(VERSION);
                    out.writeInt(snapshot.size());
                    for (var vc : snapshot) {
                        out.writeLong(vc.storeVersion());
                        ByteBuf raw = Unpooled.buffer();
                        RegistryFriendlyByteBuf buf = null;
                        try {
                            buf = new RegistryFriendlyByteBuf(
                                    new FriendlyByteBuf(raw), level.registryAccess());
                            VoxyWorldGenNetworking.LodColumnPayload.STREAM_CODEC.encode(buf, vc.payload());
                            byte[] bytes = new byte[buf.readableBytes()];
                            buf.readBytes(bytes);
                            out.writeInt(bytes.length);
                            out.write(bytes);
                        } finally {
                            if (buf != null) buf.release(); else raw.release();
                        }
                    }
                }
                Files.move(tmp, path, StandardCopyOption.REPLACE_EXISTING);
                Logger.info("Saved " + snapshot.size() + " LOD columns to " + path);
            } catch (Exception e) {
                Logger.error("Failed to save LOD payload store for " + dim, e);
                try { Files.deleteIfExists(tmp); } catch (java.io.IOException ignored) {}
            } finally {
                saving.remove(dim);
            }
        }, "Voxy-LOD-Store-Save");
        t.setDaemon(true);
        t.start();
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
                Logger.warn("LOD payload store file has wrong magic/version (expected VERSION="
                        + VERSION + "), skipping: " + path);
                return;
            }
            int count = in.readInt();
            if (count < 0 || count > 50_000_000) {
                Logger.warn("LOD payload store count out of bounds (" + count + "), skipping: " + path);
                return;
            }
            Map<Long, VersionedColumn> dimCache = new HashMap<>();
            for (int i = 0; i < count; i++) {
                long colVersion = in.readLong();
                int len = in.readInt();
                if (len < 0 || len > 50_000_000) {
                    Logger.warn("LOD payload entry length out of bounds (" + len + ") at index "
                            + i + ", skipping remainder: " + path);
                    return;
                }
                byte[] bytes = new byte[len];
                in.readFully(bytes);
                RegistryFriendlyByteBuf buf = new RegistryFriendlyByteBuf(
                        new FriendlyByteBuf(Unpooled.wrappedBuffer(bytes)),
                        level.registryAccess());
                try {
                    var payload = VoxyWorldGenNetworking.LodColumnPayload.STREAM_CODEC.decode(buf);
                    dimCache.put(payload.pos().toLong(), new VersionedColumn(payload, colVersion));
                } finally {
                    buf.release();
                }
            }
            // Advance the global counter past every loaded version so new writes never reuse a stamp.
            long maxVersion = dimCache.values().stream()
                    .mapToLong(VersionedColumn::storeVersion).max().orElse(0L);
            storeGeneration.accumulateAndGet(maxVersion + 1, Math::max);
            // Merge into live cache: entries already present (added during startup while load ran)
            // take precedence because they reflect the current world state.
            var liveCache = cache.computeIfAbsent(dim, k -> new ConcurrentHashMap<>());
            for (var entry : dimCache.entrySet()) {
                liveCache.putIfAbsent(entry.getKey(), entry.getValue());
            }
            Logger.info("Loaded " + dimCache.size() + " LOD columns from " + path);
        } catch (Exception e) {
            Logger.error("Failed to load LOD payload store for " + dim, e);
        }
    }

    /** Save LOD payloads for every level that has cached data. */
    public void saveAll(MinecraftServer server) {
        for (var level : server.getAllLevels()) {
            save(level);
        }
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
