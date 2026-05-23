# Server-Side LOD Payload Cache Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Create a persistent server-side cache that stores every `LodColumnPayload` ever built. On player join or resync, the server iterates the entire cache for the player's dimension and re-sends all stored payloads — giving new players a complete LOD view.

**Architecture:** A new `ServerLodPayloadStore` singleton holds `Map<dim, Map<chunkPos, LodColumnPayload>>` in memory and flushes to disk on shutdown. Hooks into `VoxyWorldGenNetworking` store payloads after `buildSections()`. `scheduleFullSync` sends all cached payloads in small batches over successive ticks.

**Tech Stack:** NeoForge networking, Java NIO for disk I/O, existing `RegistryFriendlyByteBuf` serialization.

---

## File Map

| File | Responsibility |
|------|--------------|
| `src/main/java/me/cortex/voxy/server/worldgen/ServerLodPayloadStore.java` | **New** — in-memory cache, disk persistence, full-sync scheduler |
| `src/main/java/me/cortex/voxy/server/worldgen/VoxyWorldGenNetworking.java` | Call `store.storeColumn()` after `buildSections()` in `broadcastLODData` and `sendLODData` |
| `src/main/java/me/cortex/voxy/server/VoxyServerLifecycle.java` | Replace `scheduleJoinLodResync` with `scheduleFullSync` on player login |
| `src/main/java/me/cortex/voxy/server/worldgen/VoxyWorldGenNetworking.java` | Update `handleClientResyncRequest` to call `scheduleFullSync` |
| `src/main/java/me/cortex/voxy/server/VoxyServerCommands.java` | Update `doResync` to call `scheduleFullSync` |

---

### Task 1: Create ServerLodPayloadStore class

**Files:**
- Create: `src/main/java/me/cortex/voxy/server/worldgen/ServerLodPayloadStore.java`

- [ ] **Step 1: Write the class skeleton and in-memory storage**

```java
package me.cortex.voxy.server.worldgen;

import me.cortex.voxy.VoxyMod;
import me.cortex.voxy.common.Logger;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.TickTask;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.ChunkPos;

import java.io.*;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
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
        if (sections.isEmpty()) return;
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
}
```

- [ ] **Step 2: Add `scheduleFullSync` method**

Append to `ServerLodPayloadStore`:

```java
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
                () -> runFullSyncStep(player.getUUID(), dim, payloads, 0)));
    }

    private void runFullSyncStep(java.util.UUID playerId, ResourceKey<Level> dim,
                                 List<VoxyWorldGenNetworking.LodColumnPayload> payloads, int offset) {
        var server = this.getServer();
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
                    () -> runFullSyncStep(playerId, dim, payloads, end)));
        }
    }

    private net.minecraft.server.MinecraftServer getServer() {
        return net.minecraft.server.MinecraftServer.getServer();
    }
```

- [ ] **Step 3: Add disk persistence**

Append to `ServerLodPayloadStore`:

```java
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
                out.writeInt(dimCache.size());
                for (var payload : dimCache.values()) {
                    RegistryFriendlyByteBuf buf = new RegistryFriendlyByteBuf(
                            new net.minecraft.network.FriendlyByteBuf(new io.netty.buffer.UnpooledByteBufAllocator(false).buffer()),
                            level.registryAccess());
                    try {
                        VoxyWorldGenNetworking.LodColumnPayload.STREAM_CODEC.encode(buf, payload);
                        byte[] bytes = new byte[buf.readableBytes()];
                        buf.readBytes(bytes);
                        out.writeInt(bytes.length);
                        out.write(bytes);
                    } finally {
                        buf.release();
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
            Map<Long, VoxyWorldGenNetworking.LodColumnPayload> dimCache = new HashMap<>();
            for (int i = 0; i < count; i++) {
                int len = in.readInt();
                byte[] bytes = new byte[len];
                in.readFully(bytes);
                RegistryFriendlyByteBuf buf = new RegistryFriendlyByteBuf(
                        new net.minecraft.network.FriendlyByteBuf(io.netty.buffer.Unpooled.wrappedBuffer(bytes)),
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
        String dimId = level.dimension().location().toString().replace(":", "_").replace("/", "_");
        return level.getServer().getWorldPath(net.minecraft.world.level.storage.LevelResource.ROOT)
                .resolve("voxy_lod_" + dimId + ".bin");
    }
```

- [ ] **Step 4: Commit**

```bash
git add src/main/java/me/cortex/voxy/server/worldgen/ServerLodPayloadStore.java
git commit -m "feat(server): add ServerLodPayloadStore with in-memory cache and disk persistence"
```

---

### Task 2: Hook storage into payload building

**Files:**
- Modify: `src/main/java/me/cortex/voxy/server/worldgen/VoxyWorldGenNetworking.java`

- [ ] **Step 1: Add storage calls to `broadcastLODData`**

In `broadcastLODData(LevelChunk chunk)` (around line 124), after `buildSections(chunk)` and before the player loop, add:

```java
        // Store for full-sync replay to new players
        ServerLodPayloadStore.getInstance().storeColumn(dim, pos, minY, sections);
```

The method should now read:
```java
    public static void broadcastLODData(LevelChunk chunk) {
        ChunkPos pos = chunk.getPos();
        int minY = chunk.getMinSection();
        List<LodSectionPayload> sections = buildSections(chunk);

        double maxDistSq = 4096.0 * 4096.0;
        ResourceKey<Level> dim = chunk.getLevel().dimension();

        // Store for full-sync replay to new players
        ServerLodPayloadStore.getInstance().storeColumn(dim, pos, minY, sections);

        for (ServerPlayer player : PlayerTracker.getInstance().getPlayers()) {
            // ... existing code unchanged ...
        }
    }
```

- [ ] **Step 2: Add storage call to `sendLODData`**

In `sendLODData(ServerPlayer player, LevelChunk chunk)` (around line 150), after `buildSections(chunk)`, add:

```java
        // Store for full-sync replay
        ServerLodPayloadStore.getInstance().storeColumn(chunk.getLevel().dimension(), pos, minY, sections);
```

- [ ] **Step 3: Commit**

```bash
git add src/main/java/me/cortex/voxy/server/worldgen/VoxyWorldGenNetworking.java
git commit -m "feat(network): store every built LOD payload in ServerLodPayloadStore"
```

---

### Task 3: Hook store load/save into dimension lifecycle

**Files:**
- Modify: `src/main/java/me/cortex/voxy/server/worldgen/ChunkGenerationManager.java`
- Modify: `src/main/java/me/cortex/voxy/server/VoxyServerLifecycle.java`

- [ ] **Step 1: Load store when dimension is initialized**

In `ChunkGenerationManager.ensureDimensionLoaded()` (around line 516), after `state.loaded = true;`, add:

```java
        // Load persisted LOD payloads for this dimension
        ServerLodPayloadStore.getInstance().load(level);
```

- [ ] **Step 2: Save store on server shutdown**

In `VoxyServerLifecycle.onServerStopping()` (around line 47), after `PlayerTracker.getInstance().clear();`, add:

```java
        // Save all persisted LOD payloads before shutdown
        for (ServerLevel level : event.getServer().getAllLevels()) {
            ServerLodPayloadStore.getInstance().save(level);
        }
```

- [ ] **Step 3: Commit**

```bash
git add src/main/java/me/cortex/voxy/server/worldgen/ChunkGenerationManager.java
git add src/main/java/me/cortex/voxy/server/VoxyServerLifecycle.java
git commit -m "feat(server): load/save ServerLodPayloadStore on dimension init/shutdown"
```

---

### Task 4: Update player login to use full sync

**Files:**
- Modify: `src/main/java/me/cortex/voxy/server/VoxyServerLifecycle.java`

- [ ] **Step 1: Replace `scheduleJoinLodResync` with `scheduleFullSync`**

In `onPlayerLoggedIn()` (around line 62), change:
```java
        player.getServer().tell(new net.minecraft.server.TickTask(
                player.getServer().getTickCount() + 20,
                () -> {
                    VoxyWorldGenNetworking.sendSyncTotal(player);
                    ChunkGenerationManager.getInstance().scheduleJoinLodResync(player.getUUID());
                }));
```

To:
```java
        player.getServer().tell(new net.minecraft.server.TickTask(
                player.getServer().getTickCount() + 20,
                () -> {
                    VoxyWorldGenNetworking.sendSyncTotal(player);
                    ServerLodPayloadStore.getInstance().scheduleFullSync(player);
                }));
```

- [ ] **Step 2: Commit**

```bash
git add src/main/java/me/cortex/voxy/server/VoxyServerLifecycle.java
git commit -m "feat(server): use full LOD sync on player login"
```

---

### Task 5: Update resync commands to use full sync

**Files:**
- Modify: `src/main/java/me/cortex/voxy/server/worldgen/VoxyWorldGenNetworking.java`
- Modify: `src/main/java/me/cortex/voxy/server/VoxyServerCommands.java`

- [ ] **Step 1: Update `handleClientResyncRequest`**

In `handleClientResyncRequest(ServerPlayer player)` (around line 288), replace the body with:

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

- [ ] **Step 2: Update server-side `doResync`**

In `VoxyServerCommands.doResync()` (around line 209), replace the body with:

```java
    private static int doResync(CommandContext<CommandSourceStack> ctx, ServerPlayer target) {
        ChunkGenerationManager mgr = ChunkGenerationManager.getInstance();
        if (!mgr.isRunning()) {
            ctx.getSource().sendFailure(Component.literal(
                    "Voxy worldgen is not active on this server"));
            return 1;
        }
        var synced = PlayerTracker.getInstance().getSyncedChunks(target.getUUID());
        if (synced != null) {
            synced.clear();
        }
        ServerLodPayloadStore.getInstance().scheduleFullSync(target);
        ctx.getSource().sendSuccess(() -> Component.literal(
                "Voxy LOD resync started for " + target.getName().getString()), true);
        return 0;
    }
```

- [ ] **Step 3: Commit**

```bash
git add src/main/java/me/cortex/voxy/server/worldgen/VoxyWorldGenNetworking.java
git add src/main/java/me/cortex/voxy/server/VoxyServerCommands.java
git commit -m "feat(commands): resync commands use full LOD payload sync"
```

---

## Manual Testing Steps

1. **Initial sync test:**
   - Start dedicated server, have client A join and explore.
   - Have client B join. Client B should immediately see all LODs that client A saw, without moving.

2. **Persisted sync test:**
   - Restart the server.
   - Have a new client C join. Client C should see all previously stored LODs.

3. **Resync command test:**
   - Run `/voxy pregen resync` as a player. Should receive all stored LODs again.
   - Run `/voxy resync` from client. Should receive all stored LODs.

4. **New chunk test:**
   - Have player A load new chunks.
   - Player B (already online) should see new LODs appear.
   - Player C (joining after) should see all LODs including the new ones.

5. **Disk file verification:**
   - Check `world/voxy_lod_minecraft_overworld.bin` exists and grows as chunks are loaded.

---

## Self-Review

**1. Spec coverage:**
- Singleton `ServerLodPayloadStore` → Task 1
- In-memory `Map<dim, Map<pos, payload>>` → Task 1
- `storeColumn()` → Task 2
- `scheduleFullSync()` with batching → Task 1
- Disk load/save with binary format → Task 1
- Hook into dimension lifecycle → Task 3
- Update login resync → Task 4
- Update command resync → Task 5
- Manual testing steps → listed above
- No gaps.

**2. Placeholder scan:**
- No "TBD", "TODO", "implement later", or vague instructions.
- All code is exact and complete.

**3. Type consistency:**
- `ServerLodPayloadStore` defined in Task 1, used in Tasks 2, 3, 4, 5 — consistent.
- `scheduleFullSync` defined in Task 1, called in Tasks 4, 5 — consistent.
- `BATCH_SIZE = 128` used in Task 1 only — no conflicts.
- All method signatures match existing codebase patterns.

Plan is ready.
