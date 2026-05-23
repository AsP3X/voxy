# Server Pregen Progress Overlay

## Overview

Add a client-side HUD overlay that displays the server's active `/voxy pregen` task progress on dedicated servers. The feature is **disabled by default** and can be toggled via `/voxy overlay progress server_progress enabled|disabled`.

## Motivation

Currently `WorldgenProgressOverlay` only shows progress for local (singleplayer / integrated-server) pregen tasks. Players on dedicated servers have no visual indication of how far a server-side pregen has progressed unless they repeatedly run `/voxy pregen status` in chat. A real-time overlay eliminates this friction.

## Architecture

### Components

| Component | Responsibility |
|-----------|-------------|
| `ServerPregenProgressPayload` | New network packet sent server → client every 20 ticks while a pregen task is active. |
| `ServerProgressState` | Static client-side holder for the last-received server progress values + "last update" timestamp. |
| `WorldgenProgressOverlay` | Extended to render server progress when local pregen is inactive and server data is fresh. |
| `VoxyCommands` | New command branch `/voxy overlay progress server_progress enabled\|disabled`. |
| `ChunkGenerationManager` | Broadcasts the payload to all tracked players inside its existing server tick. |

### Data Flow

1. **Server tick** — `ChunkGenerationManager.tick()` already iterates `PlayerTracker.getPlayers()` every 100 ticks for `SyncTotalPayload`. We add a second broadcast every 20 ticks that sends `ServerPregenProgressPayload` when `isRunning() && getPregenMode() != NONE`.
2. **Client receive** — NeoForge payload handler writes values into `ServerProgressState` and updates `lastUpdateTime`.
3. **Overlay render** — `WorldgenProgressOverlay.render()` checks in order:
   - If local pregen active + `progressVisible` → `renderLocal()`
   - Else if server pregen active (`ServerProgressState.isActive()`) + `progressVisible` + `serverProgressVisible` → `renderServerProgress()`
   - Else if connected to Voxy server + `syncVisible` → `renderNetwork()`
4. **Disconnect / timeout** — `ServerProgressState.isActive()` returns `false` when no packet has arrived for >5 s, causing the overlay to hide automatically.

## Packet Format

```java
public record ServerPregenProgressPayload(
        long totalTarget,
        long totalRemaining,
        double chunksPerSecond,
        int activeTaskCount,
        byte pregenMode,   // 0=NONE, 1=DYNAMIC, 2=REGION
        boolean paused
) implements CustomPacketPayload { ... }
```

All fields are copied directly from `ChunkGenerationManager` atomics; no locking is required because the packet is assembled on the server thread.

## Client State

```java
public final class ServerProgressState {
    private static volatile long lastUpdateTime;
    private static final AtomicLong totalTarget = new AtomicLong();
    private static final AtomicLong totalRemaining = new AtomicLong();
    private static volatile double chunksPerSecond;
    private static volatile int activeTaskCount;
    private static volatile PregenMode mode;
    private static volatile boolean paused;

    public static boolean isActive() {
        return System.currentTimeMillis() - lastUpdateTime < 5000L;
    }
    // getters / setters omitted
}
```

## Overlay Rendering

Reuses the existing `WorldgenProgressOverlay` primitives:

- **Title:** `"Voxy Server Pre-generation"` (region mode → `"Voxy Server Region Pre-generation"`)
- **Accent color:** cyan (`0x00BBFF`) normally, green (`0x00EE66`) for region mode, orange (`0xFF9900`) when paused.
- **Progress bar:** deterministic fill based on `totalRemaining / totalTarget`.
- **Stats line:** `completed / total chunks  ·  c/s  ·  tasks` (paused replaces rate with `"paused · /voxy pregen resume"`).
- **Memory-pressure dot:** still drawn if `memoryPressureVisible` is true.

## Command Grammar

```
/voxy overlay progress server_progress enabled
/voxy overlay progress server_progress disabled
```

This nests under the existing `/voxy overlay progress` branch, following the same pattern as `memory_pressure_indicator`.

## Error Handling

| Scenario | Behavior |
|----------|----------|
| Packet decode failure | Logged and swallowed; client state unchanged. |
| Server crash / pregen abruptly stops | Client state times out after 5 s and overlay hides. |
| Client toggles `server_progress` off | Overlay hides immediately even if packets keep arriving. |
| Local pregen starts while server overlay visible | Local overlay takes priority (higher in render-branch order). |

## Testing

1. **Singleplayer:** Start a local pregen, confirm local overlay renders, confirm `server_progress` toggle has no effect (no server packets).
2. **Dedicated server — active pregen:** Start `/voxy pregen start ...`, join with client, enable overlay, verify bar fills and stats update every second.
3. **Dedicated server — paused:** Pause pregen on server, confirm overlay turns orange and shows `"paused"`.
4. **Dedicated server — stopped:** Stop pregen, confirm overlay disappears within 5 s.
5. **Toggle off:** Run `/voxy overlay progress server_progress disabled`, confirm overlay hides immediately while pregen continues.
