# Server-Side LOD Payload Cache Design

## Problem

The server currently ingests chunks into `WorldEngine` (a voxelized internal format) and sends LOD payloads to clients at chunk-load time. However:
1. The server does **not** persistently cache the native `LodColumnPayload` bytes it sends.
2. `scheduleJoinLodResync` only re-sends chunks marked as "completed" in `DistanceGraph` — a pregen tracking structure that does not cover naturally-loaded chunks.
3. New players joining a server see LOD holes because they never received payloads for chunks that loaded before they joined.

## Goal

Create a server-side persistent cache that stores every `LodColumnPayload` ever built. On player join or resync, the server iterates the entire cache for the player's dimension and re-sends all stored payloads — giving new players a complete, fully-populated LOD view of the world.

## Architecture

### New Class: `ServerLodPayloadStore`

A singleton service that owns an in-memory `Map<ResourceKey<Level>, Map<Long, LodColumnPayload>>` and persists it to disk per-dimension.

**Key methods:**
- `storeColumn(ServerLevel level, LodColumnPayload payload)` — called whenever the server builds a payload; saves it in-memory (overwriting any previous entry for that chunk).
- `scheduleFullSync(ServerPlayer player)` — clears the player's `syncedChunks`, iterates ALL stored payloads for the player's current dimension, and sends them in small batches over successive ticks.
- `load(ServerLevel level)` — loads persisted cache from disk on dimension initialization.
- `save(ServerLevel level)` — flushes in-memory cache to disk on shutdown or dimension unload.

**Disk format:**
- One binary file per dimension: `<world>/voxy_lod_<dimId>.bin`
- Uses the exact same serialization as `LodColumnPayload` (reusing `writeBuf`/`readBuf` from `VoxyWorldGenNetworking`). This means no custom format and no risk of drift.
- Header: `int magic = 0xVOXY_LOD`, `int version = 1`
- Body: repeated `LodColumnPayload` entries written back-to-back
- Footer: `int count` (for fast load verification)

### Integration Points

**1. Hook into payload building:**
- `VoxyWorldGenNetworking.buildSections()` → after building sections, also save via `ServerLodPayloadStore.storeColumn()`
- `VoxyWorldGenNetworking.sendLODDataPrebuilt()` → also save, since sections are already built

**2. Hook into player lifecycle:**
- `VoxyServerLifecycle.onPlayerLoggedIn()` → instead of `scheduleJoinLodResync`, call `ServerLodPayloadStore.scheduleFullSync()`
- The existing `scheduleJoinLodResync` can remain for fallback / backward compatibility.

**3. Hook into resync commands:**
- Both `/voxy pregen resync` and `/voxy resync` should call `ServerLodPayloadStore.scheduleFullSync()` in addition to (or instead of) `scheduleJoinLodResync`.

## Data Flow

```
Chunk Load / Pregen Complete
    → buildSections(chunk) → LodColumnPayload
        → send to all nearby players (existing)
        → ServerLodPayloadStore.storeColumn() (NEW)
            → in-memory Map<dim, Map<pos, payload>>
            → (eventually) flush to disk

New Player Joins
    → PlayerTracker.addPlayer()
    → ServerLodPayloadStore.scheduleFullSync(player)
        → clear syncedChunks
        → iterate ALL stored payloads for player's dim
        → send in small batches over ticks

Resync Command
    → same as join: scheduleFullSync()
```

## Error Handling

- If the store file is corrupted on load, log an error and start with an empty cache (safe fallback).
- If saving fails, log an error but do not crash (the in-memory cache is still valid for the current session).
- If a stored payload fails to send to a player (network error), skip it and continue — the player may request another resync later.

## Testing

1. Start a dedicated server, generate some chunks, observe LODs on client A.
2. Have client B join. Without moving, client B should immediately see all LODs that client A saw.
3. Disconnect client B, have server generate more chunks, reconnect client B — client B should see the newly generated LODs too.
4. Restart the server, reconnect client B — LODs should persist across restarts.
5. Run `/voxy pregen resync` as client B — should receive all stored LODs again.

## Files Changed

| File | Change |
|------|--------|
| `ServerLodPayloadStore.java` | New class — in-memory cache, disk persistence, full sync scheduler |
| `VoxyWorldGenNetworking.java` | Call `storeColumn()` from `buildSections()` and `sendLODDataPrebuilt()` |
| `VoxyServerLifecycle.java` | Replace `scheduleJoinLodResync` with `scheduleFullSync` on login |
| `VoxyServerCommands.java` | `resync` command calls `scheduleFullSync` |
| `VoxyWorldGenNetworking.java` | `handleClientResyncRequest` calls `scheduleFullSync` |

## Scope Note

This spec is intentionally scoped to **storing and re-sending** existing payloads. It does NOT:
- Change how payloads are built
- Change the client-side ingestion
- Add compression or deduplication
- Change the pregen cache (`ChunkPersistence` / `voxy_gen_*.bin`)

Those can be follow-up improvements.
