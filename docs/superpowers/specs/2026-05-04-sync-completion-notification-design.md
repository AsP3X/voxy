# Sync Completion Notification

## Goal
When a player finishes receiving LOD data after joining or after a manual `/voxy resync`, show both a chat message and a toast notification so the player knows the sync is done.

## Architecture

### New Payload — `SyncCompletePayload`
- Server → client packet sent once when `runDeltaSyncStep` processes its final batch.
- Carries the number of chunks synced in this run so the message can be specific.
- Defined in `VoxyWorldGenNetworking.java` using the existing `CustomPacketPayload` / `StreamCodec` pattern.

### Server-side — `ServerLodPayloadStore`
- `runDeltaSyncStep` already detects the last batch (`end >= delta.size()`).
- After calling `PlayerSyncStateStore.getInstance().setWatermark(...)`, send `SyncCompletePayload` to the player with `delta.size()` as the synced count.

### Client-side — `VoxyWorldGenClientReceiver`
- New `onSyncComplete(SyncCompletePayload payload)` handler.
- Chat message: `"Voxy LOD sync complete — <count> chunks loaded"`
- Toast: use `Minecraft.getInstance().getToasts().addToast(...)` with a small custom `Toast` implementation. Title: *Voxy Sync Complete*, body: *<count> chunks loaded*.
- Only fires while `NetworkState.isServerConnected()` is true.

### Registration — `VoxyWorldGenBootstrap`
- One additional `reg.playToClient(...)` line to register the payload type, codec, and handler.

## Scope
- Applies to both **initial join sync** and **manual resync** because both flow through `scheduleDeltaSync` → `runDeltaSyncStep`.
- Not sent for live incremental broadcasts (`broadcastLODData`) because those are not a sync session.

## Files touched
| File | Change |
|---|---|
| `VoxyWorldGenNetworking.java` | Add `SyncCompletePayload` record + codec |
| `ServerLodPayloadStore.java` | Send `SyncCompletePayload` in `runDeltaSyncStep` on last batch |
| `VoxyWorldGenClientReceiver.java` | Add `onSyncComplete` handler |
| `VoxyWorldGenBootstrap.java` | Register new payload |

## Test plan
1. Join a server with cached LOD data. Wait for the sync overlay to fill. Observe chat message + toast when it completes.
2. Run `/voxy resync`. Observe the same notification when resync completes.
3. Disconnect and rejoin. Confirm notification appears again.
4. Confirm no notification appears when there is zero delta (empty store or no changes since last sync).
