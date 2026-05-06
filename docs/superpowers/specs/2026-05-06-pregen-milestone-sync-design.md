# Pregen-Only Milestone LOD Sync Design

## Goal
During `/voxy pregen` (dynamic or region mode), suppress the normal live per-chunk LOD broadcast to players. Instead, send all cached LOD columns to players in bulk at 25%, 50%, 75%, and 100% completion milestones using the existing batched `scheduleDeltaSync`. Normal chunk loading while walking around must continue to broadcast live LOD immediately.

## Architecture

### Two changes in `ChunkGenerationManager`

1. **Live broadcast suppression during pregen:** In `dispatchBatch`, when a chunk finishes generating, replace `VoxyWorldGenNetworking.broadcastLODData(chunk)` with just `WorldGenVoxyHooks.ingestChunk(chunk)` + `ServerLodPayloadStore.storeColumn(...)`. Voxel data is still ingested and cached for sync, but players do not receive live per-chunk packets.

2. **Milestone-triggered delta sync:** Track which percentage milestones (25%, 50%, 75%, 100%) have been reached. On each `tick()`, compute current progress from `totalTarget` and `totalRemaining`. When a milestone is first crossed, call `ServerLodPayloadStore.scheduleDeltaSync(player)` for every player in an active pregen dimension. `scheduleDeltaSync` is already rate-limited (32 columns/tick, 50 ms batch spacing) so it will not hang the server.

### No changes to normal paths
- `onChunkLoad` (player walking into a chunk) still calls `broadcastLODData` — unaffected.
- `ChunkUpdateTracker` (block updates) still calls `broadcastLODData` — unaffected.
- `scheduleDeltaSync` already deduplicates via `PlayerTracker.getSyncedChunks()`, so any overlap is harmless.

## Components

### `ChunkGenerationManager`
- New field: `byte milestonesReached = 0` (bitmask: bit 0 = 25%, bit 1 = 50%, bit 2 = 75%, bit 3 = 100%).
- Reset `milestonesReached = 0` in `startDynamic()` and `startRegion()`.
- New method: `private void checkMilestones()` called from `tick()`.
- New method: `private void triggerMilestoneSync(int pct)` which finds eligible players and calls `scheduleDeltaSync`.
- Modify `dispatchBatch` internal callback: skip `broadcastLODData` when `pregenMode != NONE`. Still do `ingestChunk` + `storeColumn`.

### `ServerLodPayloadStore` — no changes
Reuses existing `scheduleDeltaSync` which handles batching and rate-limiting.

### `VoxyWorldGenNetworking` — no changes
`scheduleDeltaSync` already sends `SyncCompletePayload` at the end.

## Data Flow

```
Pregen worker finishes a chunk
  -> dispatchBatch callback
    -> if pregenMode != NONE:
         ingestChunk(chunk)        // voxel data still ingested
         storeColumn(...)          // cached for delta sync
         [skip broadcastLODData]   // no live player broadcast
    -> else (normal walking):
         ingestChunk + broadcastLODData as before

Server tick
  -> tick()
    -> checkMilestones()
      -> compute pct = (target - remaining) / target
      -> if pct crosses 25/50/75/100 for first time:
           for each player in active pregen dimension(s):
             scheduleDeltaSync(player)
             // sends cached columns in 32-col batches over multiple ticks
             // ends with SyncCompletePayload
```

## Milestone Sync Scope

- **Dynamic mode:** Iterate all `PlayerTracker.getInstance().getPlayers()`, filter to those in dimensions where `DimensionState.remainingInRadius > 0`. Call `scheduleDeltaSync` for each.
- **Region mode:** Iterate all players in `regionDimension`. Call `scheduleDeltaSync` for each.
- **Rate limiting:** `scheduleDeltaSync` enforces 50 ms wall-clock spacing between batches. Multiple overlapping syncs are safe because `scheduleDeltaSync` uses `TickTask` and the `synced` set deduplicates.

## Error Handling

- **Zero players online during milestone:** `scheduleDeltaSync` is a no-op (no players to iterate). When a player later joins, the existing login flow calls `scheduleDeltaSync` anyway, and since the store is already populated, they get the full delta.
- **Player switches dimension during sync:** `scheduleDeltaSync` checks `player.level().dimension().equals(dim)` in `runDeltaSyncStep` and aborts if mismatch. Safe.
- **Pregen stops before 100%:** `stop()` resets `milestonesReached`. No partial sync sent.
- **Server tick catch-up:** `scheduleDeltaSync` enforces 50 ms wall-clock spacing between batches. Already handles this.

## Files Touched

| File | Change |
|---|---|
| `ChunkGenerationManager.java` | Suppress `broadcastLODData` in pregen; add milestone tracking + sync triggering |

## Test Plan

1. **Start region pregen.** Verify no per-chunk LOD packets arrive immediately. Verify voxel ingestion still works (check voxy world on client after sync completes).
2. **Wait for 25% milestone.** Observe delta sync begins for players in that dimension. Verify toast/chat notification when sync completes.
3. **Verify 50%, 75%, 100% milestones** each trigger another delta sync.
4. **Dynamic pregen test:** Start dynamic pregen with a player in world. Walk around — verify normal chunk loading still sends live LOD immediately. Verify pregen-generated chunks only arrive at milestones.
5. **No players test:** Start region pregen with no players. Let it complete. Join a player — verify they get the full delta sync on login (existing behavior, should still work).
