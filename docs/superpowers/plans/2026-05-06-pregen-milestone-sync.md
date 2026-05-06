# Pregen Milestone LOD Sync Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Suppress live per-chunk LOD broadcast during pregen and trigger batched delta sync at 25%, 50%, 75%, and 100% completion milestones.

**Architecture:** Add a bitmask to `ChunkGenerationManager` to track reached milestones. In `dispatchBatch`, skip `broadcastLODData` when `pregenMode != NONE` while still ingesting and caching the column. In `tick()`, compute progress and call `scheduleDeltaSync` for players in active pregen dimensions when a milestone is first crossed.

**Tech Stack:** Java 21, NeoForge 1.20.1

---

## File Map

| Path | Status | Responsibility |
|---|---|---|
| `src/main/java/me/cortex/voxy/server/worldgen/ChunkGenerationManager.java` | Modify | Milestone tracking, broadcast suppression, sync triggering |

---

## Task 1: Add milestone tracking fields

**Files:**
- Modify: `src/main/java/me/cortex/voxy/server/worldgen/ChunkGenerationManager.java`

- [ ] **Step 1: Add `milestonesReached` field and reset it in start methods**

  In `ChunkGenerationManager.java`, add a new field near the other atomic/boolean fields (around line 77-79):

  ```java
  /** Bitmask of reached milestones: bit 0 = 25%, bit 1 = 50%, bit 2 = 75%, bit 3 = 100% */
  private byte milestonesReached = 0;
  ```

  In `startDynamic()` (around line 229), after `stats.reset();` and before `pregenMode = PregenMode.DYNAMIC;`, add:

  ```java
  milestonesReached = 0;
  ```

  In `startRegion()` (around line 277), after `stats.reset();` and before `pregenMode = PregenMode.REGION;`, add:

  ```java
  milestonesReached = 0;
  ```

- [ ] **Step 2: Add helper methods for milestone tracking**

  Add these private methods near the bottom of the class, before the `// Public accessors` comment (around line 1027):

  ```java
  /** Check if a given percentage milestone has already been triggered. */
  private boolean isMilestoneReached(int pct) {
      int bit = pct / 25 - 1;
      return bit >= 0 && bit < 4 && (milestonesReached & (1 << bit)) != 0;
  }

  /** Mark a percentage milestone as triggered. */
  private void setMilestoneReached(int pct) {
      int bit = pct / 25 - 1;
      if (bit >= 0 && bit < 4) {
          milestonesReached |= (byte) (1 << bit);
      }
  }

  /** Compute current completion percentage (0-100). */
  private int computeProgressPercent() {
      long target = totalTarget.get();
      long remaining = totalRemaining.get();
      if (target <= 0) return 0;
      long done = target - remaining;
      return (int) ((done * 100L) / target);
  }
  ```

- [ ] **Step 3: Verify the project compiles**

  ```
  .\gradlew compileJava
  ```

  Expected: `BUILD SUCCESSFUL`

- [ ] **Step 4: Commit**

  ```bash
  git add src/main/java/me/cortex/voxy/server/worldgen/ChunkGenerationManager.java
  git commit -m "feat(pregen): add milestone tracking bitmask and helpers"
  ```

---

## Task 2: Suppress live broadcast during pregen

**Files:**
- Modify: `src/main/java/me/cortex/voxy/server/worldgen/ChunkGenerationManager.java`

- [ ] **Step 1: Replace `broadcastLODData` calls in `dispatchBatch` with conditional logic**

  In `dispatchBatch`, there are two places where `broadcastLODData` is called inside the `server.execute(...)` callback.

  **First location** (around line 648):

  Find:
  ```java
  if (existingChunk != null && chunkHasRenderableData(existingChunk)) {
      WorldGenVoxyHooks.ingestChunk(existingChunk);
      VoxyWorldGenNetworking.broadcastLODData(existingChunk);
  }
  ```

  Replace with:
  ```java
  if (existingChunk != null && chunkHasRenderableData(existingChunk)) {
      WorldGenVoxyHooks.ingestChunk(existingChunk);
      // During pregen, suppress live broadcast; data is cached for milestone sync.
      if (pregenMode == PregenMode.NONE) {
          VoxyWorldGenNetworking.broadcastLODData(existingChunk);
      }
  }
  ```

  **Second location** (around line 670):

  Find:
  ```java
  if (chunkHasRenderableData(chunk)) {
      WorldGenVoxyHooks.ingestChunk(chunk);
      VoxyWorldGenNetworking.broadcastLODData(chunk);
  }
  ```

  Replace with:
  ```java
  if (chunkHasRenderableData(chunk)) {
      WorldGenVoxyHooks.ingestChunk(chunk);
      // During pregen, suppress live broadcast; data is cached for milestone sync.
      if (pregenMode == PregenMode.NONE) {
          VoxyWorldGenNetworking.broadcastLODData(chunk);
      }
  }
  ```

  > **Important:** Do NOT remove `WorldGenVoxyHooks.ingestChunk(chunk)` or `ServerLodPayloadStore.storeColumn(...)`. The column is still cached by `broadcastLODData` internally — but since we skip `broadcastLODData`, we need to ensure caching happens. Wait — `broadcastLODData` internally calls `ServerLodPayloadStore.getInstance().storeColumn(...)`. If we skip `broadcastLODData`, we must manually store the column.

  Actually, re-reading `broadcastLODData` in `VoxyWorldGenNetworking.java`:
  ```java
  public static void broadcastLODData(LevelChunk chunk) {
      ...
      ServerLodPayloadStore.getInstance().storeColumn(dim, pos, minY, sections);
      ...
  }
  ```
  So when we skip `broadcastLODData`, we also skip `storeColumn`. We need to add `storeColumn` explicitly.

  **Revised replacement for both locations:**

  First location:
  ```java
  if (existingChunk != null && chunkHasRenderableData(existingChunk)) {
      WorldGenVoxyHooks.ingestChunk(existingChunk);
      List<VoxyWorldGenNetworking.LodSectionPayload> sections =
              VoxyWorldGenNetworking.buildSections(existingChunk);
      if (!sections.isEmpty()) {
          ServerLodPayloadStore.getInstance().storeColumn(
                  existingChunk.getLevel().dimension(), existingChunk.getPos(),
                  existingChunk.getMinSection(), sections);
          if (pregenMode == PregenMode.NONE) {
              VoxyWorldGenNetworking.sendSectionsInBatches(...); // No, this is complex
          }
      }
  }
  ```

  Hmm, this is getting verbose. A simpler approach: extract the `storeColumn` logic and keep `broadcastLODData` for the store, but suppress the player-send part.

  But `broadcastLODData` does everything in one call. The cleanest way without modifying `VoxyWorldGenNetworking` is to inline the store:

  Actually, the simplest fix is: call `broadcastLODData` as before, but we need to suppress only the player-send part. But `broadcastLODData` has no flag for that.

  Alternative: keep `broadcastLODData` but let it run. During pregen, no players are "nearby" because pregen generates chunks far from players in region mode, and in dynamic mode it generates around players but the chunks are loaded via forced tickets. Actually, in dynamic mode players ARE nearby. So we cannot just rely on distance.

  The cleanest solution without modifying other files is to inline the store + conditional broadcast. But `sendSectionsInBatches` is private. We can use `sendLODDataPrebuilt` for individual players.

  Wait, looking again at the design: "Replace `broadcastLODData` calls with just `ingestChunk` + `storeColumn`". This means during pregen, we don't broadcast at all. We just ingest and store. Then the milestone sync sends it via `scheduleDeltaSync`.

  So the revised code for both locations:

  First location (around line 648):
  Find:
  ```java
  if (existingChunk != null && chunkHasRenderableData(existingChunk)) {
      WorldGenVoxyHooks.ingestChunk(existingChunk);
      VoxyWorldGenNetworking.broadcastLODData(existingChunk);
  }
  ```
  Replace with:
  ```java
  if (existingChunk != null && chunkHasRenderableData(existingChunk)) {
      WorldGenVoxyHooks.ingestChunk(existingChunk);
      // Cache the column for milestone sync, but do not live-broadcast during pregen.
      var sections = VoxyWorldGenNetworking.buildSections(existingChunk);
      if (!sections.isEmpty()) {
          ServerLodPayloadStore.getInstance().storeColumn(
                  existingChunk.getLevel().dimension(), existingChunk.getPos(),
                  existingChunk.getMinSection(), sections);
          if (pregenMode == PregenMode.NONE) {
              VoxyWorldGenNetworking.broadcastLODData(existingChunk);
          }
      }
  }
  ```

  Second location (around line 670):
  Find:
  ```java
  if (chunkHasRenderableData(chunk)) {
      WorldGenVoxyHooks.ingestChunk(chunk);
      VoxyWorldGenNetworking.broadcastLODData(chunk);
  }
  ```
  Replace with:
  ```java
  if (chunkHasRenderableData(chunk)) {
      WorldGenVoxyHooks.ingestChunk(chunk);
      // Cache the column for milestone sync, but do not live-broadcast during pregen.
      var sections = VoxyWorldGenNetworking.buildSections(chunk);
      if (!sections.isEmpty()) {
          ServerLodPayloadStore.getInstance().storeColumn(
                  chunk.getLevel().dimension(), chunk.getPos(),
                  chunk.getMinSection(), sections);
          if (pregenMode == PregenMode.NONE) {
              VoxyWorldGenNetworking.broadcastLODData(chunk);
          }
      }
  }
  ```

- [ ] **Step 2: Verify the project compiles**

  ```
  .\gradlew compileJava
  ```

  Expected: `BUILD SUCCESSFUL`

- [ ] **Step 3: Commit**

  ```bash
  git add src/main/java/me/cortex/voxy/server/worldgen/ChunkGenerationManager.java
  git commit -m "feat(pregen): suppress live LOD broadcast during pregen, cache for milestone sync"
  ```

---

## Task 3: Trigger milestone sync from tick()

**Files:**
- Modify: `src/main/java/me/cortex/voxy/server/worldgen/ChunkGenerationManager.java`

- [ ] **Step 1: Add `checkMilestones()` and `triggerMilestoneSync()` methods**

  Add these methods after `computeProgressPercent()` (near the bottom, before public accessors):

  ```java
  /** Called from tick() to check if any new milestone has been reached. */
  private void checkMilestones() {
      if (pregenMode == PregenMode.NONE || userPaused.get()) return;
      int pct = computeProgressPercent();
      for (int milestone : new int[]{25, 50, 75, 100}) {
          if (pct >= milestone && !isMilestoneReached(milestone)) {
              setMilestoneReached(milestone);
              triggerMilestoneSync(milestone);
          }
      }
  }

  /** Send a batched delta sync to all players in active pregen dimension(s). */
  private void triggerMilestoneSync(int pct) {
      var players = PlayerTracker.getInstance().getPlayers();
      if (players.isEmpty()) return;

      if (pregenMode == PregenMode.REGION) {
          ServerLevel level = server != null ? server.getLevel(regionDimension) : null;
          if (level == null) return;
          for (ServerPlayer player : players) {
              if (player.level().dimension().equals(regionDimension)) {
                  ServerLodPayloadStore.getInstance().scheduleDeltaSync(player);
              }
          }
          Logger.info(String.format(Locale.ROOT,
                  "[Pregen REGION] %d%% milestone reached — scheduled LOD sync for %d player(s) in %s",
                  pct, players.size(), regionDimension.location()));
      } else if (pregenMode == PregenMode.DYNAMIC) {
          // Find dimensions that still have remaining work
          Set<ResourceKey<Level>> activeDims = new HashSet<>();
          for (DimensionState ds : dimensionStates.values()) {
              if (ds.remainingInRadius.get() > 0) {
                  activeDims.add(ds.level.dimension());
              }
          }
          int scheduled = 0;
          for (ServerPlayer player : players) {
              if (activeDims.contains(player.level().dimension())) {
                  ServerLodPayloadStore.getInstance().scheduleDeltaSync(player);
                  scheduled++;
              }
          }
          if (scheduled > 0) {
              Logger.info(String.format(Locale.ROOT,
                      "[Pregen DYNAMIC] %d%% milestone reached — scheduled LOD sync for %d player(s)",
                      pct, scheduled));
          }
      }
  }
  ```

  Also add the missing import for `java.util.Locale` if not already present. It is already imported at line 39, so no import needed.

- [ ] **Step 2: Wire `checkMilestones()` into `tick()`**

  In `tick()` (around line 698), after the `tpsMonitor.tick(); stats.tick();` block and before the `if (pregenMode == PregenMode.DYNAMIC)` block, add:

  ```java
  // Check for pregen progress milestones and trigger batched LOD sync
  checkMilestones();
  ```

- [ ] **Step 3: Reset milestones in `stop()`**

  In `stop()` (around line 293), after `pregenMode = PregenMode.NONE;`, add:

  ```java
  milestonesReached = 0;
  ```

- [ ] **Step 4: Verify the project compiles**

  ```
  .\gradlew compileJava
  ```

  Expected: `BUILD SUCCESSFUL`

- [ ] **Step 5: Commit**

  ```bash
  git add src/main/java/me/cortex/voxy/server/worldgen/ChunkGenerationManager.java
  git commit -m "feat(pregen): trigger batched delta sync at 25/50/75/100%% milestones"
  ```

---

## Manual Smoke Test

After all tasks are complete, boot a local dev server and verify:

1. **Start region pregen** (`/voxy pregen region ...`). Verify console shows no per-chunk LOD network spam. Voxel data is still ingested (voxy world builds up server-side).
2. **Watch for 25% milestone** in console log. Observe "scheduled LOD sync" message. Client should receive batched LOD data and eventually show sync completion toast.
3. **Verify 50%, 75%, 100%** each trigger another sync.
4. **Dynamic pregen test:** Start dynamic pregen. Walk around normally — verify chunk loading while walking still sends LOD immediately (not deferred to milestones). Pregen-generated chunks should only arrive at milestones.
5. **No players test:** Start region pregen with no players, let it complete. Join a player — verify they get the full delta sync on login.

Run the dev server with:
```
.\gradlew runServer
```
