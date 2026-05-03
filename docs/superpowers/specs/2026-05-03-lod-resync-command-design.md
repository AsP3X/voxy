# LOD Resync Command Design

## Problem

When running `/voxy pregen start` on a dedicated server, the server may report that every column is already in the pregen cache. However, the client can still have LOD holes because the **per-player synced-chunk set** (`PlayerTracker.syncedChunks`) thinks those chunks were already sent. If a chunk was previously synced but its LOD data is stale or missing on the client, there is no way for an operator (or the player themselves) to ask the server to re-push all completed LODs in range.

## Goal

Add server-side and client-side commands that force a fresh LOD sync for a specific player.

## Commands

### Server-side: `/voxy pregen resync [player]`

- **No argument:** resyncs the player who ran the command.
- **With `player` argument:** resyncs the target player (requires gamemaster permission).
- Works on dedicated servers and integrated servers.

### Client-side: `/voxy resync`

- Sends a request payload to the server.
- The server treats it exactly like the server-side command (resync self).
- Usable in singleplayer (integrated server) and on dedicated servers.
- Shows a failure message if the server does not have Voxy sync active (`NetworkState.isServerConnected()` is false).

## New Payload

`ClientRequestResyncPayload` — a zero-field payload sent **client → server**.

```java
public record ClientRequestResyncPayload() implements CustomPacketPayload {
    public static final Type<ClientRequestResyncPayload> TYPE = ...;
    public static final StreamCodec<FriendlyByteBuf, ClientRequestResyncPayload> STREAM_CODEC = ...;
}
```

Registered in `VoxyWorldGenBootstrap` via `registrar.playToServer(...)`.

## Mechanics

When the server receives a resync request (from command or payload):

1. Look up the target `ServerPlayer`.
2. Clear the player's `syncedChunks` set in `PlayerTracker`.
3. Call `ChunkGenerationManager.scheduleJoinLodResync(playerUUID)`.

`scheduleJoinLodResync` already:
- Clears `joinResyncUnloadedStreak` and `joinResyncCollectSkip`.
- Schedules `runJoinResyncStep` on the next server tick.

`runJoinResyncStep` iterates over completed chunks in the player's radius that are **not** in `syncedChunks` (which we just cleared), builds section payloads, and sends them. This re-uses the existing, well-tested join-resync path.

## Files Changed

| File | Change |
|------|--------|
| `VoxyWorldGenNetworking.java` | Add `ClientRequestResyncPayload` record + `handleClientResyncRequest(ServerPlayer)` |
| `VoxyWorldGenBootstrap.java` | Register `ClientRequestResyncPayload` as `playToServer` |
| `VoxyServerCommands.java` | Add `resync` subcommand under `pregen` (self + optional target player) |
| `VoxyCommands.java` | Add top-level `resync` client command that sends the payload |

## Error Handling

- If `ChunkGenerationManager` is not running, the server-side command sends a failure.
- If the client is not connected to a Voxy-aware server, the client-side command sends a failure.
- If the target player is offline, the server-side command sends a failure.

## Testing

- Run `/voxy pregen resync` as a player on a dedicated server with completed pregen.
- Observe that `PlayerTracker.syncedChunks` for that player is cleared and `scheduleJoinLodResync` is invoked.
- Verify the client receives LOD column packets again for chunks in range.
- Run `/voxy resync` from the client and confirm the same behavior.
