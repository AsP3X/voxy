# Server Pregen Progress Overlay Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add a client-side HUD overlay showing server `/voxy pregen` progress on dedicated servers, togglable via `/voxy overlay progress server_progress enabled|disabled` and disabled by default.

**Architecture:** A new server→client payload (`ServerPregenProgressPayload`) broadcasts progress state every 20 ticks. The client stores it in `ServerProgressState`, and `WorldgenProgressOverlay` renders it when local pregen is inactive. The command handler nests under the existing `/voxy overlay progress` branch.

**Tech Stack:** NeoForge networking (`CustomPacketPayload`, `StreamCodec`), `GuiGraphics` HUD rendering, Brigadier commands.

---

## File Mapping

| File | Action | Responsibility |
|------|--------|--------------|
| `server/worldgen/ServerPregenProgressPayload.java` | **Create** | Packet definition + codec for server→client pregen progress. |
| `client/worldgen/ServerProgressState.java` | **Create** | Static client-side holder for last-received values; timeout detection. |
| `client/worldgen/WorldgenProgressOverlay.java` | **Modify** | Add `serverProgressVisible` flag + `renderServerProgress()`; integrate into `render()` branching. |
| `client/VoxyCommands.java` | **Modify** | Add `server_progress enabled/disabled` under `overlay → progress`. |
| `server/worldgen/ChunkGenerationManager.java` | **Modify** | Broadcast `ServerPregenProgressPayload` to all `PlayerTracker` players every 20 ticks. |
| `client/worldgen/VoxyWorldGenClientReceiver.java` | **Modify** | Handle incoming `ServerPregenProgressPayload` and write into `ServerProgressState`. |
| `VoxyMod.java` | **Modify** | Register the new payload type on both sides. |

---

## Task 1: ServerPregenProgressPayload

**Files:**
- Create: `src/main/java/me/cortex/voxy/server/worldgen/ServerPregenProgressPayload.java`

- [ ] **Step 1: Create the packet record**

```java
package me.cortex.voxy.server.worldgen;

import io.netty.buffer.ByteBuf;
import me.cortex.voxy.VoxyMod;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

public record ServerPregenProgressPayload(
        long totalTarget,
        long totalRemaining,
        double chunksPerSecond,
        int activeTaskCount,
        byte pregenMode,
        boolean paused
) implements CustomPacketPayload {
    public static final Type<ServerPregenProgressPayload> TYPE =
            new Type<>(ResourceLocation.fromNamespaceAndPath(VoxyMod.MODID, "server_pregen_progress"));

    public static final StreamCodec<ByteBuf, ServerPregenProgressPayload> STREAM_CODEC =
            StreamCodec.of(
                    (buf, p) -> {
                        buf.writeLong(p.totalTarget);
                        buf.writeLong(p.totalRemaining);
                        buf.writeDouble(p.chunksPerSecond);
                        buf.writeVarInt(p.activeTaskCount);
                        buf.writeByte(p.pregenMode);
                        buf.writeBoolean(p.paused);
                    },
                    buf -> new ServerPregenProgressPayload(
                            buf.readLong(),
                            buf.readLong(),
                            buf.readDouble(),
                            buf.readVarInt(),
                            buf.readByte(),
                            buf.readBoolean()
                    )
            );

    @Override
    public Type<? extends CustomPacketPayload> type() { return TYPE; }
}
```

- [ ] **Step 2: Commit**

```bash
git add src/main/java/me/cortex/voxy/server/worldgen/ServerPregenProgressPayload.java
git commit -m "feat(network): add ServerPregenProgressPayload packet"
```

---

## Task 2: ServerProgressState

**Files:**
- Create: `src/main/java/me/cortex/voxy/client/worldgen/ServerProgressState.java`

- [ ] **Step 1: Create the client-side state holder**

```java
package me.cortex.voxy.client.worldgen;

import me.cortex.voxy.server.worldgen.ChunkGenerationManager;

import java.util.concurrent.atomic.AtomicLong;

public final class ServerProgressState {
    private static volatile long lastUpdateTime;
    private static final AtomicLong totalTarget = new AtomicLong();
    private static final AtomicLong totalRemaining = new AtomicLong();
    private static volatile double chunksPerSecond;
    private static volatile int activeTaskCount;
    private static volatile ChunkGenerationManager.PregenMode mode;
    private static volatile boolean paused;

    private ServerProgressState() {}

    public static boolean isActive() {
        return System.currentTimeMillis() - lastUpdateTime < 5000L;
    }

    public static void update(long target, long remaining, double cps, int tasks, byte modeByte, boolean isPaused) {
        totalTarget.set(target);
        totalRemaining.set(remaining);
        chunksPerSecond = cps;
        activeTaskCount = tasks;
        mode = ChunkGenerationManager.PregenMode.values()[modeByte];
        paused = isPaused;
        lastUpdateTime = System.currentTimeMillis();
    }

    public static void reset() {
        lastUpdateTime = 0;
        totalTarget.set(0);
        totalRemaining.set(0);
        chunksPerSecond = 0;
        activeTaskCount = 0;
        mode = ChunkGenerationManager.PregenMode.NONE;
        paused = false;
    }

    public static long getTotalTarget() { return totalTarget.get(); }
    public static long getTotalRemaining() { return totalRemaining.get(); }
    public static double getChunksPerSecond() { return chunksPerSecond; }
    public static int getActiveTaskCount() { return activeTaskCount; }
    public static ChunkGenerationManager.PregenMode getMode() { return mode; }
    public static boolean isPaused() { return paused; }
}
```

- [ ] **Step 2: Commit**

```bash
git add src/main/java/me/cortex/voxy/client/worldgen/ServerProgressState.java
git commit -m "feat(client): add ServerProgressState for server pregen overlay"
```

---

## Task 3: WorldgenProgressOverlay — server progress rendering

**Files:**
- Modify: `src/main/java/me/cortex/voxy/client/worldgen/WorldgenProgressOverlay.java`

- [ ] **Step 1: Add `serverProgressVisible` flag and getters/setters**

Insert after `memoryPressureVisible` (line ~31):

```java
    /** Server pregen progress panel; disabled by default, enable via /voxy overlay progress server_progress enabled. */
    private static boolean serverProgressVisible = false;

    public static boolean isServerProgressVisible() { return serverProgressVisible; }
    public static void setServerProgressVisible(boolean v) { serverProgressVisible = v; }
```

- [ ] **Step 2: Add `renderServerProgress()` method**

Insert after `renderNetwork()` (line ~179):

```java
    // -------------------------------------------------------------------------
    // Remote server pregen mode

    private static void renderServerProgress(GuiGraphics gfx, Font font, float partialTick) {
        long remaining = ServerProgressState.getTotalRemaining();
        long total     = ServerProgressState.getTotalTarget();
        boolean paused = ServerProgressState.isPaused();
        double cps     = paused ? 0 : ServerProgressState.getChunksPerSecond();
        int tasks      = paused ? 0 : ServerProgressState.getActiveTaskCount();

        if (remaining <= 0 && !paused) return;

        long completed = Math.max(0, total - remaining);
        double progress = total > 0 ? 1.0 - ((double) remaining / total) : 0.0;

        boolean isRegion = ServerProgressState.getMode() == ChunkGenerationManager.PregenMode.REGION;

        String titleStr;
        if (paused) {
            titleStr = isRegion ? "Voxy Server Region Pre-gen ⏸ Paused" : "Voxy Server Pre-generation ⏸ Paused";
        } else {
            titleStr = isRegion ? "Voxy Server Region Pre-generation" : "Voxy Server Pre-generation";
        }
        String pctStr   = String.format(java.util.Locale.ROOT, "%.1f%%", progress * 100.0);
        String statsStr = paused
                ? String.format(java.util.Locale.ROOT, "%,d / %,d chunks  ·  paused  ·  /voxy pregen resume",
                        completed, total)
                : String.format(java.util.Locale.ROOT, "%,d / %,d chunks  ·  %.1f c/s  ·  %d tasks",
                        completed, total, cps, tasks);

        int titleColor = paused ? 0xFFFFCC66 : (isRegion ? 0xFFCCFFCC : 0xFFCCEEFF);
        int pctColor   = paused ? 0xFFFF9900 : (isRegion ? 0xFF00FF88 : 0xFF00FFCC);

        int sw   = gfx.guiWidth();
        int panX = sw / 2 - PANEL_W / 2;
        int panY = 12;

        drawBackground(gfx, panX, panY, paused, isRegion);
        drawTitleRow(gfx, font, panX, panY, titleStr, pctStr, titleColor, pctColor);
        drawBar(gfx, panX, panY, progress, paused, isRegion, partialTick);
        drawStats(gfx, font, panX, panY, statsStr);
        drawMemoryPressureDot(gfx, panX, panY);
    }
```

- [ ] **Step 3: Integrate into `render()` branching**

Replace the body of `render()` (lines 57-88) with:

```java
    public static void render(GuiGraphics gfx, float partialTick) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.options.hideGui) return;

        ChunkGenerationManager mgr = ChunkGenerationManager.getInstance();
        boolean localRunning = mgr.isRunning();
        ChunkGenerationManager.PregenMode mode = mgr.getPregenMode();
        boolean localActive = localRunning && mode != ChunkGenerationManager.PregenMode.NONE;
        boolean serverActive = ServerProgressState.isActive();
        boolean networkActive = NetworkState.isServerConnected();

        // Reset peaks when a new local run begins
        if (localActive && !wasRunning) {
            peakRemaining = 0;
        }
        // Reset network peak when disconnecting from a server
        if (!networkActive) {
            networkPeakReceived = 0;
        }
        wasRunning = localActive;

        if (!localActive && !serverActive && !networkActive) return; // nothing running

        if (localActive) {
            if (progressVisible) {
                renderLocal(gfx, mc.font, mgr, mode, partialTick);
            }
        } else if (serverActive) {
            if (progressVisible && serverProgressVisible) {
                renderServerProgress(gfx, mc.font, partialTick);
            }
        } else {
            if (syncVisible) {
                renderNetwork(gfx, mc.font, partialTick);
            }
        }
    }
```

- [ ] **Step 4: Commit**

```bash
git add src/main/java/me/cortex/voxy/client/worldgen/WorldgenProgressOverlay.java
git commit -m "feat(render): add server pregen progress overlay rendering"
```

---

## Task 4: Command wiring

**Files:**
- Modify: `src/main/java/me/cortex/voxy/client/VoxyCommands.java`

- [ ] **Step 1: Insert `server_progress` subcommand into the overlay branch**

Inside the `overlay` builder in `register()` (around line 78), after the `sync` block (after line 138), insert:

```java
                .then(Commands.literal("server_progress")
                        .then(Commands.literal("enabled")
                                .executes(ctx -> {
                                    WorldgenProgressOverlay.setServerProgressVisible(true);
                                    ctx.getSource().sendSuccess(() -> Component.literal(
                                            "Voxy server progress overlay enabled"), false);
                                    return 0;
                                }))
                        .then(Commands.literal("disabled")
                                .executes(ctx -> {
                                    WorldgenProgressOverlay.setServerProgressVisible(false);
                                    ctx.getSource().sendSuccess(() -> Component.literal(
                                            "Voxy server progress overlay disabled"), false);
                                    return 0;
                                })))
```

- [ ] **Step 2: Commit**

```bash
git add src/main/java/me/cortex/voxy/client/VoxyCommands.java
git commit -m "feat(commands): add /voxy overlay progress server_progress toggle"
```

---

## Task 5: Server-side broadcast

**Files:**
- Modify: `src/main/java/me/cortex/voxy/server/worldgen/ChunkGenerationManager.java`

- [ ] **Step 1: Add counter field**

Add a new field near `syncTotalTickCounter` (line 74):

```java
    private int serverProgressTickCounter = 0;
```

- [ ] **Step 2: Broadcast payload in `tick()`**

Inside `tick()` (around line 667), after the `syncTotalTickCounter` block, insert:

```java
        // Broadcast server pregen progress to all players every 20 ticks (1 s)
        if (isRunning() && pregenMode != PregenMode.NONE) {
            if (++serverProgressTickCounter >= 20) {
                serverProgressTickCounter = 0;
                ServerPregenProgressPayload payload = new ServerPregenProgressPayload(
                        totalTarget.get(),
                        totalRemaining.get(),
                        stats.getChunksPerSecond(),
                        activeTaskCount.get(),
                        (byte) pregenMode.ordinal(),
                        userPaused.get()
                );
                for (ServerPlayer player : PlayerTracker.getInstance().getPlayers()) {
                    VoxyWorldGenNetworking.safeSendToPlayer(player, payload);
                }
            }
        } else {
            serverProgressTickCounter = 0;
        }
```

- [ ] **Step 3: Commit**

```bash
git add src/main/java/me/cortex/voxy/server/worldgen/ChunkGenerationManager.java
git commit -m "feat(server): broadcast ServerPregenProgressPayload every 20 ticks"
```

---

## Task 6: Client-side packet handler

**Files:**
- Modify: `src/main/java/me/cortex/voxy/client/worldgen/VoxyWorldGenClientReceiver.java`

- [ ] **Step 1: Handle `ServerPregenProgressPayload`**

Find the existing `handle(SyncTotalPayload payload)` method and add a new handler directly after it:

```java
    public static void handle(ServerPregenProgressPayload payload) {
        ServerProgressState.update(
                payload.totalTarget(),
                payload.totalRemaining(),
                payload.chunksPerSecond(),
                payload.activeTaskCount(),
                payload.pregenMode(),
                payload.paused()
        );
    }
```

- [ ] **Step 2: Commit**

```bash
git add src/main/java/me/cortex/voxy/client/worldgen/VoxyWorldGenClientReceiver.java
git commit -m "feat(client): handle ServerPregenProgressPayload"
```

---

## Task 7: Payload registration

**Files:**
- Modify: `src/main/java/me/cortex/voxy/VoxyMod.java`

- [ ] **Step 1: Register payload on both sides**

Find the existing `registerPackets` (or `setupNetworking`) method and add the new payload alongside `SyncTotalPayload` and `LodColumnPayload`. The exact registration style depends on whether the mod uses `NeoForge.EVENT_BUS` or `RegisterPayloadHandlersEvent`. Typical NeoForge 1.20.1 pattern:

```java
// In the common/network setup (called from both client and server):
payloadRegistrar.playBidirectional(
    ServerPregenProgressPayload.TYPE,
    ServerPregenProgressPayload.STREAM_CODEC,
    (payload, context) -> {
        context.enqueueWork(() -> {
            if (context.flow().isClientbound()) {
                VoxyWorldGenClientReceiver.handle(payload);
            }
            // Serverbound handler not needed for this payload
        });
    }
);
```

If the codebase separates client and server registration, place the client-bound registration in the client setup and the server-bound (empty) in the server setup. The `playToClient` variant is also acceptable:

```java
payloadRegistrar.playToClient(
    ServerPregenProgressPayload.TYPE,
    ServerPregenProgressPayload.STREAM_CODEC,
    (payload, context) -> VoxyWorldGenClientReceiver.handle(payload)
);
```

- [ ] **Step 2: Commit**

```bash
git add src/main/java/me/cortex/voxy/VoxyMod.java
git commit -m "feat(network): register ServerPregenProgressPayload"
```

---

## Task 8: Disconnect reset

**Files:**
- Modify: `src/main/java/me/cortex/voxy/client/worldgen/NetworkState.java`

- [ ] **Step 1: Reset server progress on disconnect**

In `setServerConnected(boolean connected)`, add a call to reset `ServerProgressState` when disconnecting:

```java
    public static void setServerConnected(boolean connected) {
        serverConnected = connected;
        if (!connected) {
            chunksReceived.set(0);
            bytesReceived.set(0);
            totalToSync = 0;
            receiveRate = 0;
            bandwidthRate = 0;
            lastUpdateTime = 0;
            lastChunkCount = 0;
            lastByteCount = 0;
            ServerProgressState.reset(); // <-- add this line
        }
    }
```

- [ ] **Step 2: Commit**

```bash
git add src/main/java/me/cortex/voxy/client/worldgen/NetworkState.java
git commit -m "feat(client): reset ServerProgressState on disconnect"
```

---

## Self-Review

### Spec Coverage

| Spec Requirement | Task |
|----------------|------|
| New `ServerPregenProgressPayload` packet | Task 1 |
| Client `ServerProgressState` with 5 s timeout | Task 2 |
| Overlay `serverProgressVisible` flag + `renderServerProgress()` | Task 3 |
| Command `/voxy overlay progress server_progress enabled\|disabled` | Task 4 |
| Server broadcast every 20 ticks | Task 5 |
| Client packet handler | Task 6 |
| Payload registration | Task 7 |
| Reset on disconnect | Task 8 |

### Placeholder Scan
No placeholders found. Every step contains exact code, exact file paths, and exact commit messages.

### Type Consistency
- `ServerPregenProgressPayload.pregenMode()` is `byte`; `ServerProgressState.update()` accepts `byte`; `ChunkGenerationManager.PregenMode.ordinal()` is `int` cast to `byte` safely because there are only 3 enum values. ✓
- `WorldgenProgressOverlay.renderServerProgress()` mirrors `renderLocal()` signatures and reuses the same drawing helpers. ✓

---

**Plan complete and saved to `docs/superpowers/plans/2026-05-02-server-pregen-overlay.md`. Two execution options:**

1. **Subagent-Driven (recommended)** — I dispatch a fresh subagent per task, review between tasks, fast iteration.
2. **Inline Execution** — Execute tasks in this session using `executing-plans`, batch execution with checkpoints.

Which approach would you like?
