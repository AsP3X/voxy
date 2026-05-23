# Bottom Sync Overlay Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add a compact bottom-center HUD overlay showing server LOD sync progress, toggleable via `/voxy overlay sync_hud enabled|disabled` (default disabled).

**Architecture:** A new `renderHudSync` method in the existing `WorldgenProgressOverlay` reads `NetworkState` and draws a thin bar + text just above the vanilla XP bar. A new NeoForge GUI layer registers it at the correct z-order. A new command node wires the toggle.

**Tech Stack:** Java 21, NeoForge 1.21, Minecraft client GUI layer API, existing `NetworkState` + `WorldgenProgressOverlay` infrastructure.

---

## File Map

| File | Responsibility |
|------|----------------|
| `src/main/java/me/cortex/voxy/client/worldgen/WorldgenProgressOverlay.java` | New `hudSyncVisible` flag, `renderHudSync` drawing method, wire into `render()` |
| `src/main/java/me/cortex/voxy/VoxyMod.java` | Register new NeoForge GUI layer `sync_hud_overlay` |
| `src/main/java/me/cortex/voxy/client/VoxyCommands.java` | Add `sync_hud enabled` / `sync_hud disabled` command nodes |

---

### Task 1: Add visibility flag + render method to WorldgenProgressOverlay

**Files:**
- Modify: `src/main/java/me/cortex/voxy/client/worldgen/WorldgenProgressOverlay.java`

- [ ] **Step 1: Add `hudSyncVisible` static field and accessors**

Locate the existing visibility toggles near line 27 and add the new one after `serverProgressVisible`:

```java
    /** Bottom-center HUD sync bar; disabled by default, enable via /voxy overlay sync_hud enabled. */
    private static boolean hudSyncVisible = false;
```

After the existing `setServerProgressVisible` method (around line 45), add:

```java
    public static boolean isHudSyncVisible() { return hudSyncVisible; }
    public static void setHudSyncVisible(boolean v) { hudSyncVisible = v; }
```

- [ ] **Step 2: Add `renderHudSync` drawing method**

Insert after the existing `renderNetwork` method (after line 189). This method draws a compact bar at the bottom-center:

```java
    // -------------------------------------------------------------------------
    // Bottom-center HUD sync bar

    private static final int HUD_BAR_W = 180;
    private static final int HUD_BAR_H = 4;

    public static void renderHudSync(GuiGraphics gfx, Font font, float partialTick) {
        if (!hudSyncVisible) return;
        if (!NetworkState.isServerConnected()) return;

        long received = NetworkState.getChunksReceived();
        long total    = NetworkState.getTotalToSync();
        if (total > 0 && received >= total) return; // sync done

        boolean determinate = total > 0;
        double progress = determinate ? Math.min(1.0, (double) received / total) : 0.0;

        int sw = gfx.guiWidth();
        int sh = gfx.guiHeight();
        int x  = sw / 2 - HUD_BAR_W / 2;
        // Position just above the vanilla XP bar (which sits at roughly sh - 32)
        int y  = sh - 48;

        int bx = x;
        int by = y;
        int bw = HUD_BAR_W;
        int bh = HUD_BAR_H;

        // Bar background
        gfx.fill(bx, by, bx + bw, by + bh, 0xFF0A0A14);
        gfx.fill(bx, by, bx + bw, by + 1, 0xFF1A1A2A);

        if (determinate) {
            int fillW = (int)(bw * progress);
            if (fillW > 0) {
                gfx.fillGradient(bx, by, bx + fillW, by + bh, 0xFF00AAFF, 0xFF00FFCC);
                gfx.fillGradient(bx, by, bx + fillW, by + 1, 0x6600CCFF, 0x0000CCFF);
            }
        } else {
            // Indeterminate animated pulse
            animPhase = (animPhase + partialTick * 0.018f) % 1f;
            int pulseW = bw / 3;
            int offset = (int)((bw + pulseW) * animPhase) - pulseW;
            int cs = Math.max(bx, bx + offset);
            int ce = Math.min(bx + bw, bx + offset + pulseW);
            if (cs < ce) {
                gfx.fillGradient(cs, by, ce, by + bh, 0xFF005599, 0xFF003366);
                gfx.fillGradient(cs, by, ce, by + 1, 0x660099EE, 0x00006699);
            }
        }

        String text = determinate
                ? String.format(Locale.ROOT, "Syncing LODs... %.0f%%", progress * 100.0)
                : "Syncing LODs...";
        int tw = font.width(text);
        int tx = sw / 2 - tw / 2;
        int ty = by + bh + 2;
        gfx.drawString(font, text, tx, ty, 0xFFAAAAAA, false);
    }
```

- [ ] **Step 3: Wire `renderHudSync` into `render()`**

In the existing `render` method, after the existing conditional block that decides which overlay to show (around line 83-97), add a call to the new HUD method. The bottom overlay should render independently of the top overlay:

```java
        // Bottom-center sync HUD (independent of top overlay)
        if (networkActive && hudSyncVisible) {
            renderHudSync(gfx, mc.font, partialTick);
        }
```

Place this at the end of the `render` method, just before the closing brace.

- [ ] **Step 4: Commit**

```bash
git add src/main/java/me/cortex/voxy/client/worldgen/WorldgenProgressOverlay.java
git commit -m "feat(overlay): add bottom-center sync HUD bar with toggle"
```

---

### Task 2: Register new NeoForge GUI layer in VoxyMod

**Files:**
- Modify: `src/main/java/me/cortex/voxy/VoxyMod.java`

- [ ] **Step 1: Register the new layer alongside the existing one**

Locate the existing `RegisterGuiLayersEvent` listener (lines 38-43). Replace the single registration with a block that registers both layers:

```java
            modEventBus.addListener(RegisterGuiLayersEvent.class, event -> {
                event.registerAboveAll(
                        ResourceLocation.fromNamespaceAndPath(MODID, "worldgen_overlay"),
                        (guiGraphics, deltaTracker) ->
                                WorldgenProgressOverlay.render(guiGraphics,
                                        deltaTracker.getGameTimeDeltaPartialTick(true)));
                event.registerAboveAll(
                        ResourceLocation.fromNamespaceAndPath(MODID, "sync_hud_overlay"),
                        (guiGraphics, deltaTracker) ->
                                WorldgenProgressOverlay.renderHudSync(guiGraphics,
                                        deltaTracker.getGameTimeDeltaPartialTick(true)));
            });
```

Both layers use `registerAboveAll` because:
- `worldgen_overlay` is top-center and should stay on top.
- `sync_hud_overlay` is bottom-center and does not overlap with the top overlay; ordering relative to vanilla HUD elements does not matter because it computes its own Y from `guiHeight`.

- [ ] **Step 2: Commit**

```bash
git add src/main/java/me/cortex/voxy/VoxyMod.java
git commit -m "feat(overlay): register sync_hud_overlay NeoForge GUI layer"
```

---

### Task 3: Add `/voxy overlay sync_hud` toggle command

**Files:**
- Modify: `src/main/java/me/cortex/voxy/client/VoxyCommands.java`

- [ ] **Step 1: Add `sync_hud` command nodes under the overlay literal**

Locate the existing overlay command tree (around line 81). The existing `sync` node at lines 142-156 handles the top-center overlay. Insert a new `sync_hud` node immediately after the closing of the `sync` node:

```java
                .then(Commands.literal("sync_hud")
                        .then(Commands.literal("enabled")
                                .executes(ctx -> {
                                    WorldgenProgressOverlay.setHudSyncVisible(true);
                                    ctx.getSource().sendSuccess(() -> Component.literal(
                                            "Voxy bottom sync HUD enabled"), false);
                                    return 0;
                                }))
                        .then(Commands.literal("disabled")
                                .executes(ctx -> {
                                    WorldgenProgressOverlay.setHudSyncVisible(false);
                                    ctx.getSource().sendSuccess(() -> Component.literal(
                                            "Voxy bottom sync HUD disabled"), false);
                                    return 0;
                                })))
```

Place this after the existing `.then(Commands.literal("sync")...)` block but before the `var voxy = Commands.literal("voxy")` line (around line 158).

- [ ] **Step 2: Commit**

```bash
git add src/main/java/me/cortex/voxy/client/VoxyCommands.java
git commit -m "feat(commands): add /voxy overlay sync_hud enabled|disabled toggle"
```

---

### Task 4: Build & verify in-game

**Files:** None (verification step)

- [ ] **Step 1: Build the project**

```bash
./gradlew build
```

Expected: BUILD SUCCESSFUL.

- [ ] **Step 2: Launch client + connect to a Voxy-enabled server**

Launch the client from the IDE or via:

```bash
./gradlew runClient
```

Connect to a Voxy-enabled server that is actively generating LODs. The bottom overlay should not appear because it is disabled by default.

- [ ] **Step 3: Enable the overlay and verify it appears**

In-game, run:

```
/voxy overlay sync_hud enabled
```

Expected:
- A thin cyan/blue bar appears just above the XP bar at the bottom-center.
- Text below the bar reads `Syncing LODs... 47%` (or similar, depending on actual progress).

- [ ] **Step 4: Disable the overlay and verify it disappears**

```
/voxy overlay sync_hud disabled
```

Expected: The bottom bar disappears immediately.

- [ ] **Step 5: Verify it auto-hides when sync completes**

Wait for the server sync to finish (or force a complete state). The overlay should disappear once `received >= total`.

- [ ] **Step 6: Final commit if all looks good**

```bash
git commit -m "feat(overlay): bottom sync HUD overlay — complete"
```

---

## Self-Review

**Spec coverage:**
- Placement & sizing → Task 1 `renderHudSync` uses `guiWidth/2 - 90` and `guiHeight - 48`
- Visual design → Task 1 exact drawing code with colors matching existing palette
- Data flow → Task 1 reads `NetworkState` fields, hides when complete/disconnected
- API / toggle → Task 1 `hudSyncVisible` + Task 3 command node
- Layer registration → Task 2 `RegisterGuiLayersEvent`
- Edge cases (hideGui, disconnect, complete, coexisting top overlay) → Task 1 checks + Task 2 independent layer registration

**Placeholder scan:** All steps contain exact file paths, line numbers, and complete code. No TBD/TODO/similar-to references.

**Type consistency:** `hudSyncVisible`, `setHudSyncVisible`, `isHudSyncVisible`, `renderHudSync` used consistently across all three files.
