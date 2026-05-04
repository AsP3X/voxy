# Delta Sync Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Replace the full-replay-on-login sync with a generation-watermark delta sync so returning players only receive LOD columns that are new or updated since their last session.

**Architecture:** A global `AtomicLong storeGeneration` in `ServerLodPayloadStore` stamps every stored column with a monotonic version. A new `PlayerSyncStateStore` singleton persists one watermark `long` per player per dimension to disk. On login, only columns with `storeVersion > watermark` are collected, sorted nearest-first by Chebyshev distance, and streamed in 128-column batches per tick. The watermark is written when the batch stream completes.

**Tech Stack:** Java 21, NeoForge 1.20.1, JUnit 5, Gradle (NeoForge ModDev 2.x)

---

## File Map

| Path | Status | Responsibility |
|---|---|---|
| `src/main/java/me/cortex/voxy/server/worldgen/ServerLodPayloadStore.java` | Modify | Add `VersionedColumn`, `storeGeneration`, `scheduleDeltaSync`, `runDeltaSyncStep`; update `storeColumn`, `save`, `load`; remove old `runFullSyncStep`; keep `scheduleFullSync` as one-line wrapper |
| `src/main/java/me/cortex/voxy/server/worldgen/PlayerSyncStateStore.java` | Create | Per-player watermark map; load/save from `voxy_player_sync/<uuid>.bin` |
| `src/main/java/me/cortex/voxy/server/VoxyServerLifecycle.java` | Modify | Wire `loadPlayer` on login, `savePlayer` on logout, `saveAll` on stop; swap `scheduleFullSync` → `scheduleDeltaSync` |
| `src/main/java/me/cortex/voxy/server/worldgen/VoxyWorldGenNetworking.java` | Modify | Update `handleClientResyncRequest` to reset watermarks then call `scheduleDeltaSync` |
| `build.gradle` | Modify | Add JUnit 5 test dependency and `useJUnitPlatform()` |
| `src/test/java/me/cortex/voxy/server/worldgen/PlayerSyncStateStoreTest.java` | Create | Round-trip and edge-case tests for the binary file format |
| `src/test/java/me/cortex/voxy/server/worldgen/ServerLodPayloadStoreTest.java` | Create | Tests for `collectDelta` filtering and distance ordering |

---

## Task 1: Add JUnit 5 test infrastructure

**Files:**
- Modify: `build.gradle`

- [ ] **Step 1: Add JUnit 5 dependencies and test configuration to `build.gradle`**

  Open `build.gradle`. Inside the existing `dependencies { ... }` block, add at the end (before the closing brace):

  ```groovy
  testImplementation 'org.junit.jupiter:junit-jupiter:5.11.0'
  testRuntimeOnly 'org.junit.platform:junit-platform-launcher'
  testImplementation sourceSets.main.compileClasspath
  testImplementation sourceSets.main.output
  ```

  Also add a new top-level block after the `dependencies { }` block:

  ```groovy
  tasks.named('test') {
      useJUnitPlatform()
  }
  ```

- [ ] **Step 2: Verify the build still compiles**

  ```
  .\gradlew compileJava compileTestJava
  ```

  Expected: `BUILD SUCCESSFUL`

- [ ] **Step 3: Commit**

  ```
  git add build.gradle
  git commit -m "test: add JUnit 5 test infrastructure"
  ```

---

## Task 2: Add `VersionedColumn` record and `storeGeneration` to `ServerLodPayloadStore`

**Files:**
- Modify: `src/main/java/me/cortex/voxy/server/worldgen/ServerLodPayloadStore.java`
- Create: `src/test/java/me/cortex/voxy/server/worldgen/ServerLodPayloadStoreTest.java`

The cache currently holds `LodColumnPayload` directly. We need to wrap it with a version stamp.

- [ ] **Step 1: Write the failing test**

  Create `src/test/java/me/cortex/voxy/server/worldgen/ServerLodPayloadStoreTest.java`:

  ```java
  package me.cortex.voxy.server.worldgen;

  import net.minecraft.core.registries.Registries;
  import net.minecraft.resources.ResourceKey;
  import net.minecraft.resources.ResourceLocation;
  import net.minecraft.world.level.ChunkPos;
  import net.minecraft.world.level.Level;
  import org.junit.jupiter.api.Test;

  import java.util.List;
  import java.util.Map;
  import java.util.concurrent.ConcurrentHashMap;

  import static org.junit.jupiter.api.Assertions.*;

  class ServerLodPayloadStoreTest {

      private static ResourceKey<Level> overworld() {
          return ResourceKey.create(Registries.DIMENSION,
                  ResourceLocation.fromNamespaceAndPath("minecraft", "overworld"));
      }

      private static ServerLodPayloadStore.VersionedColumn col(int x, int z, long version) {
          var payload = new VoxyWorldGenNetworking.LodColumnPayload(
                  overworld(), new ChunkPos(x, z), 0, List.of());
          return new ServerLodPayloadStore.VersionedColumn(payload, version);
      }

      @Test
      void collectDelta_excludesColumnsAtOrBelowWatermark() {
          Map<Long, ServerLodPayloadStore.VersionedColumn> cache = new ConcurrentHashMap<>();
          cache.put(new ChunkPos(0, 0).toLong(), col(0, 0, 5L));
          cache.put(new ChunkPos(1, 0).toLong(), col(1, 0, 10L));
          cache.put(new ChunkPos(2, 0).toLong(), col(2, 0, 15L));

          var delta = ServerLodPayloadStore.collectDelta(cache, 10L, new ChunkPos(0, 0));

          assertEquals(1, delta.size());
          assertEquals(new ChunkPos(2, 0), delta.get(0).payload().pos());
      }

      @Test
      void collectDelta_sortedByDistanceNearestFirst() {
          Map<Long, ServerLodPayloadStore.VersionedColumn> cache = new ConcurrentHashMap<>();
          cache.put(new ChunkPos(10, 0).toLong(), col(10, 0, 1L));
          cache.put(new ChunkPos(1, 0).toLong(),  col(1, 0, 1L));
          cache.put(new ChunkPos(5, 0).toLong(),  col(5, 0, 1L));

          var delta = ServerLodPayloadStore.collectDelta(cache, 0L, new ChunkPos(0, 0));

          assertEquals(3, delta.size());
          assertEquals(new ChunkPos(1, 0),  delta.get(0).payload().pos());
          assertEquals(new ChunkPos(5, 0),  delta.get(1).payload().pos());
          assertEquals(new ChunkPos(10, 0), delta.get(2).payload().pos());
      }

      @Test
      void collectDelta_watermarkZero_returnsAll() {
          Map<Long, ServerLodPayloadStore.VersionedColumn> cache = new ConcurrentHashMap<>();
          cache.put(new ChunkPos(0, 0).toLong(), col(0, 0, 1L));
          cache.put(new ChunkPos(1, 0).toLong(), col(1, 0, 2L));

          var delta = ServerLodPayloadStore.collectDelta(cache, 0L, new ChunkPos(0, 0));

          assertEquals(2, delta.size());
      }
  }
  ```

- [ ] **Step 2: Run the test to confirm it fails (class not found)**

  ```
  .\gradlew test --tests "me.cortex.voxy.server.worldgen.ServerLodPayloadStoreTest"
  ```

  Expected: FAIL — `VersionedColumn` and `collectDelta` do not exist yet.

- [ ] **Step 3: Add `VersionedColumn` record, `storeGeneration` field, update `cache` type and `storeColumn()` in `ServerLodPayloadStore.java`**

  At the top of the class body, after the constants, add:

  ```java
  private static final int BATCH_SIZE = 128;

  record VersionedColumn(VoxyWorldGenNetworking.LodColumnPayload payload, long storeVersion) {}

  private final java.util.concurrent.atomic.AtomicLong storeGeneration =
          new java.util.concurrent.atomic.AtomicLong(0);
  ```

  Change the `cache` field declaration:

  ```java
  // old:
  private final Map<ResourceKey<Level>, Map<Long, VoxyWorldGenNetworking.LodColumnPayload>> cache
          = new ConcurrentHashMap<>();

  // new:
  private final Map<ResourceKey<Level>, Map<Long, VersionedColumn>> cache
          = new ConcurrentHashMap<>();
  ```

  Replace the `storeColumn` method:

  ```java
  public void storeColumn(ResourceKey<Level> dimension, ChunkPos pos, int minY,
                          List<VoxyWorldGenNetworking.LodSectionPayload> sections) {
      if (sections == null || sections.isEmpty()) return;
      long version = storeGeneration.getAndIncrement();
      var dimCache = cache.computeIfAbsent(dimension, k -> new ConcurrentHashMap<>());
      dimCache.put(pos.toLong(), new VersionedColumn(
              new VoxyWorldGenNetworking.LodColumnPayload(dimension, pos, minY, sections), version));
  }
  ```

  Update `hasDimension` and `getColumnCount` — they still compile unchanged since they only call `cache.get(dim)` and check `isEmpty()`/`size()`. No changes needed there.

  Add the package-private `collectDelta` and `chebyshev` helpers (add before `scheduleFullSync`):

  ```java
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
  ```

  The existing `scheduleFullSync` still compiles because it calls `new ArrayList<>(dimCache.values())` — but `dimCache` now holds `VersionedColumn`, so this will fail. Leave `scheduleFullSync` broken for now; it will be replaced in Task 5.

  To keep the file compiling, temporarily change `scheduleFullSync` to a stub:

  ```java
  public void scheduleFullSync(ServerPlayer player) {
      scheduleDeltaSync(player); // implemented in a later task
  }
  ```

  And add a placeholder `scheduleDeltaSync`:

  ```java
  public void scheduleDeltaSync(ServerPlayer player) {
      // TODO: implemented in Task 5
  }
  ```

  Also update `runFullSyncStep` — since it references the old payload type, temporarily delete or comment it out. It will be replaced by `runDeltaSyncStep` in Task 5.

  Remove `runFullSyncStep` entirely for now (it is private and the only caller was `scheduleFullSync`).

- [ ] **Step 4: Verify the project compiles**

  ```
  .\gradlew compileJava
  ```

  Expected: `BUILD SUCCESSFUL`

- [ ] **Step 5: Run the tests — they should now pass**

  ```
  .\gradlew test --tests "me.cortex.voxy.server.worldgen.ServerLodPayloadStoreTest"
  ```

  Expected: 3 tests PASS.

- [ ] **Step 6: Commit**

  ```
  git add src/main/java/me/cortex/voxy/server/worldgen/ServerLodPayloadStore.java
  git add src/test/java/me/cortex/voxy/server/worldgen/ServerLodPayloadStoreTest.java
  git commit -m "feat(store): add VersionedColumn record and storeGeneration counter"
  ```

---

## Task 3: Update `ServerLodPayloadStore` binary format (save/load)

**Files:**
- Modify: `src/main/java/me/cortex/voxy/server/worldgen/ServerLodPayloadStore.java`

Bump VERSION to 2. Each saved column entry now writes `storeVersion` (8 bytes, `long`) before the payload. On load, restore `storeGeneration` past the max loaded version.

- [ ] **Step 1: Update the VERSION constant**

  In `ServerLodPayloadStore.java`, change:

  ```java
  // old:
  private static final int VERSION = 1;

  // new:
  private static final int VERSION = 2;
  ```

- [ ] **Step 2: Update `save()` to write `storeVersion` per column**

  Replace the `save` method. The only change is:
  1. `snapshot` is now `List<VersionedColumn>` (already, since `cache` holds `VersionedColumn`).
  2. Add `out.writeLong(vc.storeVersion())` before writing payload bytes.

  Full replacement of the `save` method:

  ```java
  public void save(ServerLevel level) {
      ResourceKey<Level> dim = level.dimension();
      var dimCache = cache.get(dim);
      if (dimCache == null || dimCache.isEmpty()) return;

      Path path = getStorePath(level);
      if (path == null) return;

      var snapshot = new java.util.ArrayList<>(dimCache.values());

      try {
          Files.createDirectories(path.getParent());
          try (DataOutputStream out = new DataOutputStream(
                  new BufferedOutputStream(Files.newOutputStream(path)))) {
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
          Logger.info("Saved " + snapshot.size() + " LOD columns to " + path);
      } catch (Exception e) {
          Logger.error("Failed to save LOD payload store for " + dim, e);
      }
  }
  ```

- [ ] **Step 3: Update `load()` to read `storeVersion` and advance `storeGeneration`**

  Replace the `load` method:

  ```java
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
          java.util.Map<Long, VersionedColumn> dimCache = new java.util.HashMap<>();
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
          cache.put(dim, new ConcurrentHashMap<>(dimCache));
          Logger.info("Loaded " + dimCache.size() + " LOD columns from " + path);
      } catch (Exception e) {
          Logger.error("Failed to load LOD payload store for " + dim, e);
      }
  }
  ```

- [ ] **Step 4: Verify the project compiles**

  ```
  .\gradlew compileJava
  ```

  Expected: `BUILD SUCCESSFUL`

- [ ] **Step 5: Commit**

  ```
  git add src/main/java/me/cortex/voxy/server/worldgen/ServerLodPayloadStore.java
  git commit -m "feat(store): version-stamp columns in binary save/load format (VERSION 1→2)"
  ```

---

## Task 4: Create `PlayerSyncStateStore`

**Files:**
- Create: `src/main/java/me/cortex/voxy/server/worldgen/PlayerSyncStateStore.java`
- Create: `src/test/java/me/cortex/voxy/server/worldgen/PlayerSyncStateStoreTest.java`

New singleton that persists `Map<UUID, Map<String, Long>>` to disk, one file per player at `<world>/voxy_player_sync/<uuid>.bin`.

- [ ] **Step 1: Write the failing test**

  Create `src/test/java/me/cortex/voxy/server/worldgen/PlayerSyncStateStoreTest.java`:

  ```java
  package me.cortex.voxy.server.worldgen;

  import org.junit.jupiter.api.Test;
  import org.junit.jupiter.api.io.TempDir;

  import java.io.DataOutputStream;
  import java.io.IOException;
  import java.nio.file.Files;
  import java.nio.file.Path;
  import java.util.HashMap;
  import java.util.Map;

  import static org.junit.jupiter.api.Assertions.*;

  class PlayerSyncStateStoreTest {

      @Test
      void roundTrip_emptyMap(@TempDir Path dir) throws IOException {
          Path file = dir.resolve("test.bin");
          PlayerSyncStateStore.writeFile(file, Map.of());
          Map<String, Long> result = PlayerSyncStateStore.readFile(file);
          assertTrue(result.isEmpty());
      }

      @Test
      void roundTrip_singleEntry(@TempDir Path dir) throws IOException {
          Path file = dir.resolve("test.bin");
          PlayerSyncStateStore.writeFile(file, Map.of("minecraft:overworld", 12345L));
          Map<String, Long> result = PlayerSyncStateStore.readFile(file);
          assertEquals(1, result.size());
          assertEquals(12345L, result.get("minecraft:overworld"));
      }

      @Test
      void roundTrip_multipleDimensions(@TempDir Path dir) throws IOException {
          Path file = dir.resolve("test.bin");
          Map<String, Long> data = new HashMap<>();
          data.put("minecraft:overworld", 100L);
          data.put("minecraft:the_nether", 200L);
          data.put("minecraft:the_end", 300L);
          PlayerSyncStateStore.writeFile(file, data);
          Map<String, Long> result = PlayerSyncStateStore.readFile(file);
          assertEquals(3, result.size());
          assertEquals(100L, result.get("minecraft:overworld"));
          assertEquals(200L, result.get("minecraft:the_nether"));
          assertEquals(300L, result.get("minecraft:the_end"));
      }

      @Test
      void wrongMagic_returnsEmpty(@TempDir Path dir) throws IOException {
          Path file = dir.resolve("bad.bin");
          try (DataOutputStream out = new DataOutputStream(Files.newOutputStream(file))) {
              out.writeInt(0xDEADBEEF);
              out.writeInt(1);
              out.writeInt(0);
          }
          Map<String, Long> result = PlayerSyncStateStore.readFile(file);
          assertTrue(result.isEmpty());
      }

      @Test
      void missingFile_throws(@TempDir Path dir) {
          Path file = dir.resolve("nonexistent.bin");
          assertThrows(java.nio.file.NoSuchFileException.class,
                  () -> PlayerSyncStateStore.readFile(file));
      }
  }
  ```

- [ ] **Step 2: Run the test to confirm it fails**

  ```
  .\gradlew test --tests "me.cortex.voxy.server.worldgen.PlayerSyncStateStoreTest"
  ```

  Expected: FAIL — `PlayerSyncStateStore` does not exist.

- [ ] **Step 3: Create `PlayerSyncStateStore.java`**

  Create `src/main/java/me/cortex/voxy/server/worldgen/PlayerSyncStateStore.java`:

  ```java
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
  ```

- [ ] **Step 4: Run tests — they should pass**

  ```
  .\gradlew test --tests "me.cortex.voxy.server.worldgen.PlayerSyncStateStoreTest"
  ```

  Expected: 5 tests PASS.

- [ ] **Step 5: Commit**

  ```
  git add src/main/java/me/cortex/voxy/server/worldgen/PlayerSyncStateStore.java
  git add src/test/java/me/cortex/voxy/server/worldgen/PlayerSyncStateStoreTest.java
  git commit -m "feat(store): add PlayerSyncStateStore with per-player watermark persistence"
  ```

---

## Task 5: Add `scheduleDeltaSync` and `runDeltaSyncStep` to `ServerLodPayloadStore`

**Files:**
- Modify: `src/main/java/me/cortex/voxy/server/worldgen/ServerLodPayloadStore.java`

Replace the placeholder `scheduleDeltaSync` stub (and empty `scheduleFullSync`) with the real implementations.

- [ ] **Step 1: Replace the placeholder `scheduleDeltaSync` stub with the real implementation**

  Find the current placeholder:

  ```java
  public void scheduleDeltaSync(ServerPlayer player) {
      // TODO: implemented in Task 5
  }
  ```

  Replace it with:

  ```java
  public void scheduleDeltaSync(ServerPlayer player) {
      var server = player.getServer();
      if (server == null) return;

      UUID playerId = player.getUUID();
      ResourceKey<Level> dim = player.level().dimension();
      long watermark = PlayerSyncStateStore.getInstance().getWatermark(playerId, dim);
      long snapshotGeneration = storeGeneration.get();

      var dimCache = cache.get(dim);
      if (dimCache == null || dimCache.isEmpty()) return;

      var synced = PlayerTracker.getInstance().getSyncedChunks(playerId);
      if (synced != null) synced.clear();

      List<VersionedColumn> delta = collectDelta(dimCache, watermark, player.chunkPosition());
      if (delta.isEmpty()) {
          PlayerSyncStateStore.getInstance().setWatermark(playerId, dim, snapshotGeneration);
          return;
      }

      server.tell(new net.minecraft.server.TickTask(server.getTickCount() + 1,
              () -> runDeltaSyncStep(server, playerId, dim, delta, 0, snapshotGeneration)));
  }

  private void runDeltaSyncStep(net.minecraft.server.MinecraftServer server,
                                UUID playerId, ResourceKey<Level> dim,
                                List<VersionedColumn> delta, int offset,
                                long snapshotGeneration) {
      ServerPlayer player = server.getPlayerList().getPlayer(playerId);
      if (player == null) return;
      if (!player.level().dimension().equals(dim)) return;

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
          server.tell(new net.minecraft.server.TickTask(server.getTickCount() + 1,
                  () -> runDeltaSyncStep(server, playerId, dim, delta, end, snapshotGeneration)));
      } else {
          PlayerSyncStateStore.getInstance().setWatermark(playerId, dim, snapshotGeneration);
      }
  }
  ```

  Also add `import java.util.UUID;` at the top of the file if not already present, and `import java.util.List;`.

- [ ] **Step 2: Replace `scheduleFullSync` with a one-line wrapper**

  Find the current stub:

  ```java
  public void scheduleFullSync(ServerPlayer player) {
      scheduleDeltaSync(player); // implemented in a later task
  }
  ```

  The comment is now stale; clean it up:

  ```java
  public void scheduleFullSync(ServerPlayer player) {
      scheduleDeltaSync(player);
  }
  ```

  Note: callers of `scheduleFullSync` get identical behaviour — `PlayerSyncStateStore` is loaded before the 20-tick delay fires, and `getWatermark` returns 0 for a player whose watermarks were reset.

- [ ] **Step 3: Verify the project compiles**

  ```
  .\gradlew compileJava
  ```

  Expected: `BUILD SUCCESSFUL`

- [ ] **Step 4: Run all tests**

  ```
  .\gradlew test
  ```

  Expected: all tests PASS.

- [ ] **Step 5: Commit**

  ```
  git add src/main/java/me/cortex/voxy/server/worldgen/ServerLodPayloadStore.java
  git commit -m "feat(store): implement scheduleDeltaSync with distance-ordered batching"
  ```

---

## Task 6: Wire lifecycle hooks and update `handleClientResyncRequest`

**Files:**
- Modify: `src/main/java/me/cortex/voxy/server/VoxyServerLifecycle.java`
- Modify: `src/main/java/me/cortex/voxy/server/worldgen/VoxyWorldGenNetworking.java`

Connect `PlayerSyncStateStore` to player login/logout/server-stop events, and update the resync command handler.

- [ ] **Step 1: Add import to `VoxyServerLifecycle.java`**

  Add at the top of the import block:

  ```java
  import me.cortex.voxy.server.worldgen.PlayerSyncStateStore;
  ```

- [ ] **Step 2: Update `onPlayerLoggedIn` to load sync state and use `scheduleDeltaSync`**

  Replace the entire `onPlayerLoggedIn` method:

  ```java
  private static void onPlayerLoggedIn(PlayerEvent.PlayerLoggedInEvent event) {
      if (!(event.getEntity() instanceof ServerPlayer player)) return;
      PlayerTracker.getInstance().addPlayer(player);
      VoxyWorldGenNetworking.sendHandshake(player);
      // Load persisted watermarks immediately so scheduleDeltaSync can read them
      // when the TickTask fires 20 ticks later.
      PlayerSyncStateStore.getInstance().loadPlayer(player.getUUID(), player.getServer());
      player.getServer().tell(new net.minecraft.server.TickTask(
              player.getServer().getTickCount() + 20,
              () -> {
                  VoxyWorldGenNetworking.sendSyncTotal(player);
                  ServerLodPayloadStore.getInstance().scheduleDeltaSync(player);
              }));
  }
  ```

- [ ] **Step 3: Update `onPlayerLoggedOut` to save sync state**

  Replace the entire `onPlayerLoggedOut` method:

  ```java
  private static void onPlayerLoggedOut(PlayerEvent.PlayerLoggedOutEvent event) {
      if (!(event.getEntity() instanceof ServerPlayer player)) return;
      ChunkGenerationManager.getInstance().clearJoinResyncState(player.getUUID());
      PlayerSyncStateStore.getInstance().savePlayer(player.getUUID(), player.getServer());
      PlayerTracker.getInstance().removePlayer(player);
  }
  ```

- [ ] **Step 4: Update `onServerStopping` to flush all in-memory sync state**

  Replace the entire `onServerStopping` method:

  ```java
  private static void onServerStopping(ServerStoppingEvent event) {
      ChunkGenerationManager.getInstance().shutdown();
      PlayerTracker.getInstance().clear();
      PlayerSyncStateStore.getInstance().saveAll(event.getServer());
      for (ServerLevel level : event.getServer().getAllLevels()) {
          ServerLodPayloadStore.getInstance().save(level);
      }
      if (VoxyCommon.IS_DEDICATED_SERVER) {
          VoxyCommon.shutdownInstance();
          VoxyDedicatedServerInstance.unbindServer();
      }
  }
  ```

- [ ] **Step 5: Update `handleClientResyncRequest` in `VoxyWorldGenNetworking.java`**

  Find the existing method:

  ```java
  public static void handleClientResyncRequest(ServerPlayer player) {
      ChunkGenerationManager mgr = ChunkGenerationManager.getInstance();
      if (!mgr.isRunning()) {
          return;
      }
      var synced = PlayerTracker.getInstance().getSyncedChunks(player.getUUID());
      if (synced != null) {
          synced.clear();
      }
      ServerLodPayloadStore.getInstance().scheduleFullSync(player);
  }
  ```

  Replace it with:

  ```java
  public static void handleClientResyncRequest(ServerPlayer player) {
      ChunkGenerationManager mgr = ChunkGenerationManager.getInstance();
      if (!mgr.isRunning()) return;
      // Reset watermarks so scheduleDeltaSync sends the full store (watermark=0).
      // resetWatermarks also deletes the file so the reset survives a crash.
      PlayerSyncStateStore.getInstance().resetWatermarks(player.getUUID(), player.getServer());
      var synced = PlayerTracker.getInstance().getSyncedChunks(player.getUUID());
      if (synced != null) synced.clear();
      ServerLodPayloadStore.getInstance().scheduleDeltaSync(player);
  }
  ```

  Add the missing import at the top of `VoxyWorldGenNetworking.java`:

  ```java
  import me.cortex.voxy.server.worldgen.PlayerSyncStateStore;
  ```

- [ ] **Step 6: Verify the full project compiles**

  ```
  .\gradlew compileJava
  ```

  Expected: `BUILD SUCCESSFUL`

- [ ] **Step 7: Run all tests**

  ```
  .\gradlew test
  ```

  Expected: all tests PASS.

- [ ] **Step 8: Commit**

  ```
  git add src/main/java/me/cortex/voxy/server/VoxyServerLifecycle.java
  git add src/main/java/me/cortex/voxy/server/worldgen/VoxyWorldGenNetworking.java
  git commit -m "feat(sync): wire PlayerSyncStateStore lifecycle and update resync command handler"
  ```

---

## Manual Smoke Test

After all tasks are complete, boot a local dev server and verify:

1. **First login** — player receives all stored LOD columns (watermark=0 → full delta). Check logs for `Loaded N LOD columns`.
2. **Re-login** — player receives zero or only new/updated columns. Confirm via log output: `scheduleDeltaSync` delta size should be 0 if nothing changed.
3. **Chunk generation while offline** — disconnect, generate chunks via `/voxy` commands, reconnect. Only the newly generated columns should arrive.
4. **Server restart** — stop and restart server. Re-login should still produce a small delta, not a full replay, proving the watermark file survived.
5. **`/voxy resync`** — triggers a full replay despite a valid watermark. Confirm all columns arrive again.
6. **Player file** — check `<world>/voxy_player_sync/<your-uuid>.bin` exists after logout.

Run the dev server with:

```
.\gradlew runServer
```
