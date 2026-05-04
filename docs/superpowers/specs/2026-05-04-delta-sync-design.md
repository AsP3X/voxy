# Delta Sync Design

**Date:** 2026-05-04  
**Branch:** feature-neoforge-worldgen-integration  
**Status:** Approved

## Problem

On every player login the server replays the entire `ServerLodPayloadStore` to that player —
regardless of whether they have already received those LOD columns. There is no tracking of what
a player already knows, and no way to detect that a stored column changed since they last received
it. The result is unnecessary bandwidth on re-login and the possibility of players holding stale LOD
data after a chunk is regenerated while they were offline.

## Goals

- Returning players receive only LOD columns they have not yet seen, or that have changed since
  they last received them.
- The sync state survives server restarts (persisted to disk).
- Delta columns are delivered nearest-first so the player's immediate surroundings load first.
- The `/voxy resync` command continues to trigger a full re-sync on demand.
- The solution must scale from hundreds to hundreds of thousands of columns per dimension.

## Chosen Approach: Global Sequence Watermark

Each stored column is stamped with a monotonically increasing global generation counter when it
enters the store. Each player tracks one watermark per dimension — the generation value at the
time their last delta sync completed. On login, only columns with `storeVersion > watermark` are
sent. Per-player state is a single `long` per dimension regardless of world size.

## Section 1: Data Model

### `VersionedColumn` record

New package-private record inside `ServerLodPayloadStore`:

```java
record VersionedColumn(LodColumnPayload payload, long storeVersion) {}
```

### `ServerLodPayloadStore` changes

| Change | Detail |
|---|---|
| `cache` map type | `Map<ResourceKey<Level>, Map<Long, VersionedColumn>>` (was `…LodColumnPayload`) |
| New field | `AtomicLong storeGeneration` — incremented on every `storeColumn()` call |
| `storeColumn()` | Stamps the new `VersionedColumn` with `storeGeneration.getAndIncrement()` |

### `PlayerSyncStateStore` — new class

Singleton. Holds `Map<UUID, Map<ResourceKey<Level>, Long>>` in memory.  
One `long` per player per dimension: their last fully-completed delta-sync generation.  
If no persisted record exists for a player, the watermark defaults to `0` (triggers a full sync —
correct behaviour for new players and for existing installs upgrading to this feature).

### Binary format — `voxy_lod_<dim>.bin`

VERSION constant is bumped. Each column entry gains an 8-byte `storeVersion: long` prefix before
the payload length field. Files with the old VERSION are discarded on load and the dimension
rebuilds from live chunk events (same behaviour as today for a corrupt or missing file).

## Section 2: Delta Sync Flow

### Login path

`VoxyServerLifecycle.onPlayerLoggedIn` replaces the `scheduleFullSync` call with
`scheduleDeltaSync(player)` (same 20-tick delay).

`scheduleDeltaSync` steps (executed at the scheduled tick):

1. Read player's watermark from `PlayerSyncStateStore` (already loaded from disk at login —
   see Section 3).
2. Snapshot `snapshotGeneration = storeGeneration.get()`.
3. Collect delta: iterate the dimension map, keep entries where
   `entry.storeVersion > watermark`. Produces `List<VersionedColumn>`.
4. Sort by Chebyshev distance from player's current chunk position, nearest first.
   One-time O(n log n) sort over the delta list only.
5. Clear player's `syncedChunks` set.
6. Call `runDeltaSyncStep(player, dim, sortedDelta, offset=0, snapshotGeneration)`.

### `runDeltaSyncStep` batching

Identical mechanics to the existing `runFullSyncStep`:
- 128 columns per tick.
- Before each send, check `syncedChunks` — skip if already delivered live by `onChunkLoad`.
- Add sent position to `syncedChunks`.
- If more remain, reschedule at `tick + 1`.
- When `offset >= delta.size()`: write `snapshotGeneration` as the player's new watermark in
  `PlayerSyncStateStore`.

### `onChunkLoad` — unchanged

Continues to stamp new columns into the store via `storeColumn()` (which bumps `storeGeneration`)
and broadcasts immediately to online players not yet in `syncedChunks`. Columns delivered live
during an in-progress delta sync have `storeVersion > snapshotGeneration` and will be re-sent on
the next login's delta — a small, correct redundancy that avoids complex in-flight bookkeeping.

### Mid-sync logout behaviour

If a player disconnects before `runDeltaSyncStep` reaches `offset >= delta.size()`, the
watermark is never written by the completion callback. `savePlayer` on logout therefore persists
the old (pre-login) watermark. On next login the same delta is replayed from scratch — safe and
correct, with no data loss.

### `scheduleFullSync` retention

Kept as a package-private method. With watermark `0`, `scheduleDeltaSync` produces the entire
store as the delta, so `scheduleFullSync` becomes a one-line wrapper calling `scheduleDeltaSync`.
No separate code path is needed.

## Section 3: Persistence

### `PlayerSyncStateStore` lifecycle hooks

| `VoxyServerLifecycle` event | Action |
|---|---|
| Player login | `PlayerSyncStateStore.loadPlayer(uuid)` — reads file; inserts watermark `0` if missing |
| Player logout | `PlayerSyncStateStore.savePlayer(uuid)` — writes to disk, then evicts from memory |
| Server stop | `PlayerSyncStateStore.saveAll()` — flushes all remaining in-memory entries |

### File format — `voxy_player_sync_<uuid>.bin`

```
[MAGIC: int]
[VERSION: int]
[count: int]
  per entry:
    [dimKey length: short]
    [dimKey UTF-8 bytes]    e.g. "minecraft:overworld"
    [watermark: long]
```

Written atomically: write to `<filename>.tmp`, then rename. Prevents corruption on crash.  
A typical three-dimension player file is ~100 bytes.

### `voxy_lod_<dim>.bin` migration

If VERSION does not match the new constant, `load()` logs a warning, discards the file, and
returns an empty store. No explicit migration step required.

## Section 4: Resync Command

Server-side handler for `ClientRequestResyncPayload`:

1. Reset player's watermark to `0` for **all dimensions** in `PlayerSyncStateStore`.
2. Immediately persist the reset (so a crash during the ensuing sync leaves the player in a
   known clean state on next login).
3. Call `scheduleDeltaSync(player)` with no tick delay. Watermark `0` produces a full sync.

`ClientRequestResyncPayload` and the client-side `/voxy resync` command are unchanged.

## Files Affected

| File | Change |
|---|---|
| `ServerLodPayloadStore.java` | Add `storeGeneration`, `VersionedColumn`, `scheduleDeltaSync`, `runDeltaSyncStep`; update `storeColumn`, `save`, `load`; keep `scheduleFullSync` as wrapper |
| `PlayerSyncStateStore.java` | New class — watermark map, load/save per player, saveAll |
| `VoxyServerLifecycle.java` | Wire `loadPlayer` on login, `savePlayer` on logout, `saveAll` on stop; swap `scheduleFullSync` → `scheduleDeltaSync` |
| `ChunkGenerationManager.java` | No change |
| `PlayerTracker.java` | No change |
| `VoxyWorldGenNetworking.java` | Update `handleClientResyncRequest` — reset watermarks + call `scheduleDeltaSync` |

## Out of Scope

- Dynamic re-ordering of the delta mid-stream as the player moves (future enhancement).
- Per-dimension delta sync on dimension change (first login to a new dimension is always a full
  sync since watermark defaults to `0`).
- Compressing or diffing payload bytes.
