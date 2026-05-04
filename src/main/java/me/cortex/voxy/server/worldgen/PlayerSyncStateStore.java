package me.cortex.voxy.server.worldgen;

import me.cortex.voxy.common.Logger;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.storage.LevelResource;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

public final class PlayerSyncStateStore {
    private static final PlayerSyncStateStore INSTANCE = new PlayerSyncStateStore();
    private static final int MAGIC   = 0x564F5953; // "VOYS"
    private static final int VERSION = 1;

    /** playerId → (dimensionKey → lastSyncedGeneration) */
    private final Map<UUID, Map<String, Long>> watermarks = new ConcurrentHashMap<>();

    private PlayerSyncStateStore() {}

    public static PlayerSyncStateStore getInstance() { return INSTANCE; }

    /** Returns 0 if no watermark is recorded (triggers a full sync). */
    public long getWatermark(UUID playerId, ResourceKey<Level> dim) {
        var playerMap = watermarks.get(playerId);
        return playerMap == null ? 0L : playerMap.getOrDefault(dimKey(dim), 0L);
    }

    public void setWatermark(UUID playerId, ResourceKey<Level> dim, long version) {
        watermarks.computeIfAbsent(playerId, k -> new ConcurrentHashMap<>())
                    .put(dimKey(dim), version);
    }

    /**
     * Resets all watermarks for a player (forces full sync on next login).
     * Also deletes the persisted file so the reset survives a server restart.
     */
    public void resetWatermarks(UUID playerId, MinecraftServer server) {
        watermarks.remove(playerId);
        Path file = getPlayerFile(playerId, server);
        try {
            Files.deleteIfExists(file);
        } catch (IOException e) {
            Logger.error("Failed to delete player sync state for " + playerId, e);
        }
    }

    /** Load a player's watermarks from disk. Call on player login before scheduling sync. */
    public void loadPlayer(UUID playerId, MinecraftServer server) {
        Path file = getPlayerFile(playerId, server);
        if (!Files.exists(file)) {
            watermarks.put(playerId, new ConcurrentHashMap<>());
            return;
        }
        try {
            watermarks.put(playerId, new ConcurrentHashMap<>(readFile(file)));
        } catch (Exception e) {
            Logger.error("Failed to load player sync state for " + playerId + ", defaulting to 0", e);
            watermarks.put(playerId, new ConcurrentHashMap<>());
        }
    }

    /** Persist a player's watermarks to disk then evict them from memory. Call on player logout. */
    public void savePlayer(UUID playerId, MinecraftServer server) {
        Map<String, Long> playerMap = watermarks.remove(playerId);
        // playerMap is null if savePlayer was called before loadPlayer (should not happen),
        // or empty if the player disconnected before any sync completed — no file needed.
        if (playerMap == null || playerMap.isEmpty()) return;
        try {
            writeFile(getPlayerFile(playerId, server), playerMap);
        } catch (Exception e) {
            Logger.error("Failed to save player sync state for " + playerId, e);
        }
    }

    /** Persist all in-memory watermarks. Call on server stop to cover online players. */
    public void saveAll(MinecraftServer server) {
        for (UUID playerId : new ArrayList<>(watermarks.keySet())) {
            Map<String, Long> playerMap = watermarks.remove(playerId);
            if (playerMap == null || playerMap.isEmpty()) continue;
            try {
                writeFile(getPlayerFile(playerId, server), playerMap);
            } catch (Exception e) {
                Logger.error("Failed to save player sync state for " + playerId + " on shutdown", e);
            }
        }
    }

    // --- package-private for tests ---

    static Map<String, Long> readFile(Path file) throws IOException {
        try (DataInputStream in = new DataInputStream(
                new BufferedInputStream(Files.newInputStream(file)))) {
            int magic   = in.readInt();
            int version = in.readInt();
            if (magic != MAGIC || version != VERSION) {
                Logger.warn("Player sync state file has wrong magic/version, ignoring: " + file);
                return new HashMap<>();
            }
            int count = in.readInt();
            if (count < 0 || count > 1000) {
                Logger.warn("Player sync state entry count out of bounds (" + count + "), ignoring: " + file);
                return new HashMap<>();
            }
            Map<String, Long> result = new HashMap<>();
            for (int i = 0; i < count; i++) {
                int keyLen = in.readUnsignedShort();
                byte[] keyBytes = new byte[keyLen];
                in.readFully(keyBytes);
                String key = new String(keyBytes, StandardCharsets.UTF_8);
                long watermark = in.readLong();
                result.put(key, watermark);
            }
            return result;
        }
    }

    static void writeFile(Path file, Map<String, Long> data) throws IOException {
        Files.createDirectories(file.getParent());
        Path tmp = file.resolveSibling(file.getFileName() + ".tmp");
        try {
            try (DataOutputStream out = new DataOutputStream(
                    new BufferedOutputStream(Files.newOutputStream(tmp)))) {
                out.writeInt(MAGIC);
                out.writeInt(VERSION);
                out.writeInt(data.size());
                for (var entry : data.entrySet()) {
                    byte[] keyBytes = entry.getKey().getBytes(StandardCharsets.UTF_8);
                    out.writeShort(keyBytes.length);
                    out.write(keyBytes);
                    out.writeLong(entry.getValue());
                }
            }
            Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException e) {
            try { Files.deleteIfExists(tmp); } catch (IOException ignored) {}
            throw e;
        }
    }

    private static Path getPlayerFile(UUID playerId, MinecraftServer server) {
        return server.getWorldPath(LevelResource.ROOT)
                .resolve("voxy_player_sync")
                .resolve(playerId + ".bin");
    }

    private static String dimKey(ResourceKey<Level> dim) {
        return dim.location().toString();
    }
}
