# Bottom Sync Overlay Design

## Summary

Add a compact, bottom-center HUD overlay that shows server LOD synchronization progress as a thin bar just above the vanilla XP bar. It is disabled by default and toggled via `/voxy overlay sync_hud enabled|disabled`.

## Motivation

The existing `WorldgenProgressOverlay` renders a full top-center panel (280×37 px) for server LOD sync. Some players want a more subtle, at-a-glance indicator near the hotbar so they can see sync progress without a large panel drawing attention away from gameplay.

## Placement & Sizing

- **Position**: Bottom-center, just above the vanilla XP bar.
- **Width**: 180 px (slightly narrower than the XP bar, aligned to its center).
- **Height**: ~12 px total (4 px bar + small text below with 1 px gap).
- **Z-layer**: Registered as a separate NeoForge GUI layer below the existing top overlay so the two do not conflict.

## Visual Design

```
┌────────────────────────┐
│ ████████████░░░░░░░░░░ │  ← 4 px bar, filled portion in cyan/blue gradient
│      Syncing... 47%    │  ← centered small text, #AAAAAA with shadow
└────────────────────────┘
```

- **Bar background**: `0xFF0A0A14` (same as existing overlay).
- **Bar fill**: gradient from `0xFF00AAFF` to `0xFF00FFCC` (same accent palette as existing sync UI).
- **Text**: vanilla `Font` with `drawString(..., false)` so Minecraft renders its default shadow.
- **Indeterminate state**: if `totalToSync` is unknown, show the same animated pulse bar used by `renderNetwork()` instead of a static fill.

## Data Flow

No new networking or server changes are required. The overlay reads from the existing `NetworkState` singleton:

| Source | Field / Method | Purpose |
|--------|----------------|---------|
| `NetworkState` | `isServerConnected()` | Show/hide decision |
| `NetworkState` | `getChunksReceived()` | Numerator for progress |
| `NetworkState` | `getTotalToSync()` | Denominator for progress |

**Show conditions** (all must be true):
1. `hudSyncVisible == true` (client toggle)
2. `mc.options.hideGui == false`
3. `NetworkState.isServerConnected() == true`
4. `received < total` (sync not yet complete)

**Hide conditions** (any triggers hide):
- Toggle set to disabled
- F1 pressed (`hideGui`)
- Server disconnected
- `received >= total`

## API / State Changes

### `WorldgenProgressOverlay`

Add a new static visibility flag:

```java
private static boolean hudSyncVisible = false;

public static boolean isHudSyncVisible() { return hudSyncVisible; }
public static void setHudSyncVisible(boolean v) { hudSyncVisible = v; }
```

Add a new render method:

```java
private static void renderHudSync(GuiGraphics gfx, Font font, float partialTick)
```

Call it from `render(...)` alongside the existing local/server/network branches. Because this is a separate visual element in a different screen region, it can render independently of the top-center overlay.

### `VoxyCommands`

Extend the `overlay` command tree with a new `sync_hud` node:

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

## Registering the New Layer

In `VoxyMod.java`, register the bottom sync layer below the existing top overlay so it renders underneath (or at a well-defined z-order):

```java
modEventBus.addListener(RegisterGuiLayersEvent.class, event -> {
    event.registerAboveAll(
        ResourceLocation.fromNamespaceAndPath(MODID, "worldgen_overlay"),
        (guiGraphics, deltaTracker) ->
            WorldgenProgressOverlay.render(guiGraphics,
                deltaTracker.getGameTimeDeltaPartialTick(true)));
    event.registerAbove(
        VanillaGuiLayers.EXPERIENCE_BAR,
        ResourceLocation.fromNamespaceAndPath(MODID, "sync_hud_overlay"),
        (guiGraphics, deltaTracker) ->
            WorldgenProgressOverlay.renderHudSyncLayer(guiGraphics,
                deltaTracker.getGameTimeDeltaPartialTick(true)));
});
```

*Note:* The exact layer ordering should use `VanillaGuiLayers.EXPERIENCE_BAR` (or `CHAT` if unavailable) so the bar sits just above the XP bar. If NeoForge layers do not provide exact pixel-level control, use a separate `RegisterGuiLayersEvent` registration and compute Y from `gfx.guiHeight()`.

## Edge Cases

| Case | Behavior |
|------|----------|
| F1 (hideGui) | Overlay hidden |
| Not connected to server | Overlay hidden |
| Sync completes | Overlay auto-hides |
| Both top and bottom overlays enabled | Both render independently; no overlap because they are in different screen regions |
| `totalToSync == 0` (unknown) | Show animated indeterminate pulse bar, text reads `Syncing LODs...` without percentage |
| Very high chunk counts | Format numbers with `String.format(Locale.ROOT, "%,d", value)` to keep text width bounded |

## Future Considerations

- If players request it, the text line could later be expanded to show ETA (`Syncing... 47% ~12s`) using rate data from `NetworkState.getReceiveRate()`.
- Color theming could be made configurable, but that is out of scope for this change.

## Out of Scope

- Full-screen blocking loading screen.
- Server-side changes (payloads, packets, progress tracking).
- Persistent config file storage for the toggle state (the existing overlays also use in-memory toggles set via commands).
