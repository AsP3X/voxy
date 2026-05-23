# LOD Resync Command Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add server-side `/voxy pregen resync [player]` and client-side `/voxy resync` commands that force a fresh LOD sync by clearing the target player's synced-chunk tracking and re-running the join-resync logic.

**Architecture:** A new client-to-server payload (`ClientRequestResyncPayload`) triggers the same logic as the server-side command. Both paths clear the player's `syncedChunks` set in `PlayerTracker` and call `ChunkGenerationManager.scheduleJoinLodResync()`. The existing join-resync worker then re-pushes all completed LOD columns in range over successive ticks.

**Tech Stack:** NeoForge networking API (CustomPacketPayload, StreamCodec, PacketDistributor), Brigadier command API.

---

## File Map

| File | Responsibility |
|------|--------------|
| `src/main/java/me/cortex/voxy/server/worldgen/VoxyWorldGenNetworking.java` | Define `ClientRequestResyncPayload` record + `handleClientResyncRequest(ServerPlayer)` handler |
| `src/main/java/me/cortex/voxy/server/worldgen/VoxyWorldGenBootstrap.java` | Register `ClientRequestResyncPayload` as `playToServer` |
| `src/main/java/me/cortex/voxy/server/VoxyServerCommands.java` | Add `resync` subcommand under `pregen` (self + optional target player) |
| `src/main/java/me/cortex/voxy/client/VoxyCommands.java` | Add top-level `resync` client command that sends the payload |

---

### Task 1: Add ClientRequestResyncPayload

**Files:**
- Modify: `src/main/java/me/cortex/voxy/server/worldgen/VoxyWorldGenNetworking.java`

- [ ] **Step 1: Add the new payload record**

Insert the following record inside `VoxyWorldGenNetworking`, after the `SyncTotalPayload` record (around line 121):

```java
    public record ClientRequestResyncPayload() implements CustomPacketPayload {
        public static final CustomPacketPayload.Type<ClientRequestResyncPayload> TYPE =
                new CustomPacketPayload.Type<>(ResourceLocation.fromNamespaceAndPath(VoxyMod.MODID, "client_request_resync"));
        public static final StreamCodec<FriendlyByteBuf, ClientRequestResyncPayload> STREAM_CODEC =
                StreamCodec.of((b, v) -> {}, b -> new ClientRequestResyncPayload());

        @Override
        public Type<? extends CustomPacketPayload> type() {
            return TYPE;
        }
    }
```

- [ ] **Step 2: Add imports if missing**

Ensure these imports exist at the top of the file:

```java
import me.cortex.voxy.server.worldgen.ChunkGenerationManager;
import me.cortex.voxy.server.worldgen.PlayerTracker;
```

(They may not exist yet — add them if absent.)

- [ ] **Step 3: Commit**

```bash
git add src/main/java/me/cortex/voxy/server/worldgen/VoxyWorldGenNetworking.java
git commit -m "feat(network): add ClientRequestResyncPayload"
```

---

### Task 2: Register ClientRequestResyncPayload on the server channel

**Files:**
- Modify: `src/main/java/me/cortex/voxy/server/worldgen/VoxyWorldGenBootstrap.java`

- [ ] **Step 1: Add `playToServer` registration**

Inside `registerPayloads`, after the existing `reg.playToClient(...)` calls and before the closing brace (around line 28), add:

```java
        reg.playToServer(
                VoxyWorldGenNetworking.ClientRequestResyncPayload.TYPE,
                VoxyWorldGenNetworking.ClientRequestResyncPayload.STREAM_CODEC,
                (payload, ctx) -> ctx.enqueueWork(() -> {
                    if (ctx.player() instanceof net.minecraft.server.level.ServerPlayer player) {
                        VoxyWorldGenNetworking.handleClientResyncRequest(player);
                    }
                }));
```

- [ ] **Step 2: Commit**

```bash
git add src/main/java/me/cortex/voxy/server/worldgen/VoxyWorldGenBootstrap.java
git commit -m "feat(network): register ClientRequestResyncPayload as playToServer"
```

---

### Task 3: Add server-side resync handler

**Files:**
- Modify: `src/main/java/me/cortex/voxy/server/worldgen/VoxyWorldGenNetworking.java`

- [ ] **Step 1: Add `handleClientResyncRequest` method**

Insert the following static method inside `VoxyWorldGenNetworking`, after `safeSendToPlayer` (around line 271):

```java
    public static void handleClientResyncRequest(ServerPlayer player) {
        ChunkGenerationManager mgr = ChunkGenerationManager.getInstance();
        if (!mgr.isRunning()) {
            return;
        }
        var synced = PlayerTracker.getInstance().getSyncedChunks(player.getUUID());
        if (synced != null) {
            synced.clear();
        }
        mgr.scheduleJoinLodResync(player.getUUID());
    }
```

- [ ] **Step 2: Commit**

```bash
git add src/main/java/me/cortex/voxy/server/worldgen/VoxyWorldGenNetworking.java
git commit -m "feat(network): add handleClientResyncRequest handler"
```

---

### Task 4: Add server-side `/voxy pregen resync [player]` command

**Files:**
- Modify: `src/main/java/me/cortex/voxy/server/VoxyServerCommands.java`

- [ ] **Step 1: Add required imports**

Add these imports at the top of the file, after the existing imports:

```java
import me.cortex.voxy.server.worldgen.ChunkGenerationManager;
import me.cortex.voxy.server.worldgen.PlayerTracker;
import net.minecraft.commands.arguments.EntityArgument;
import net.minecraft.server.level.ServerPlayer;
```

(Note: `ChunkGenerationManager` is already imported on line 7, so only add the missing two.)

- [ ] **Step 2: Add the `resync` subcommand in `buildPregen()`**

Inside `buildPregen()`, after the `.then(Commands.literal("resume")...)` block and before the final closing `;` (around line 146), add:

```java
                // /voxy pregen resync [player]
                .then(Commands.literal("resync")
                        .executes(VoxyServerCommands::resyncSelf)
                        .then(Commands.argument("player", EntityArgument.player())
                                .executes(VoxyServerCommands::resyncTarget)))
```

- [ ] **Step 3: Add the handler methods**

Insert these two private methods after `startRegion` (around line 177):

```java
    private static int resyncSelf(CommandContext<CommandSourceStack> ctx) {
        ServerPlayer self = ctx.getSource().getPlayer();
        if (self == null) {
            ctx.getSource().sendFailure(Component.literal(
                    "This command must be run by a player, or specify a target player"));
            return 1;
        }
        return doResync(ctx, self);
    }

    private static int resyncTarget(CommandContext<CommandSourceStack> ctx) {
        ServerPlayer target;
        try {
            target = EntityArgument.getPlayer(ctx, "player");
        } catch (Exception e) {
            ctx.getSource().sendFailure(Component.literal("Invalid target player"));
            return 1;
        }
        return doResync(ctx, target);
    }

    private static int doResync(CommandContext<CommandSourceStack> ctx, ServerPlayer target) {
        ChunkGenerationManager mgr = ChunkGenerationManager.getInstance();
        if (!mgr.isRunning()) {
            ctx.getSource().sendFailure(Component.literal(
                    "Voxy worldgen is not active on this server"));
            return 1;
        }
        var synced = PlayerTracker.getInstance().getSyncedChunks(target.getUUID());
        if (synced != null) {
            synced.clear();
        }
        mgr.scheduleJoinLodResync(target.getUUID());
        ctx.getSource().sendSuccess(() -> Component.literal(
                "Voxy LOD resync started for " + target.getName().getString()), true);
        return 0;
    }
```

- [ ] **Step 4: Commit**

```bash
git add src/main/java/me/cortex/voxy/server/VoxyServerCommands.java
git commit -m "feat(commands): add /voxy pregen resync [player] server command"
```

---

### Task 5: Add client-side `/voxy resync` command

**Files:**
- Modify: `src/main/java/me/cortex/voxy/client/VoxyCommands.java`

- [ ] **Step 1: Add required imports**

Add these imports at the top of the file, after the existing imports:

```java
import me.cortex.voxy.client.worldgen.NetworkState;
import me.cortex.voxy.server.worldgen.VoxyWorldGenNetworking;
import net.neoforged.neoforge.network.PacketDistributor;
```

- [ ] **Step 2: Add the `resync` literal to the main command tree**

Inside `register()`, in the chain that builds the main `Commands.literal("voxy")` return value, after the `.then(overlay)` call and before the final `;` (around line 152), add:

```java
                .then(Commands.literal("resync")
                        .executes(ctx -> {
                            if (!NetworkState.isServerConnected()) {
                                ctx.getSource().sendFailure(Component.literal(
                                        "Not connected to a Voxy-aware server"));
                                return 1;
                            }
                            PacketDistributor.sendToServer(
                                    new VoxyWorldGenNetworking.ClientRequestResyncPayload());
                            ctx.getSource().sendSuccess(() -> Component.literal(
                                    "Voxy LOD resync requested"), false);
                            return 0;
                        }))
```

- [ ] **Step 3: Commit**

```bash
git add src/main/java/me/cortex/voxy/client/VoxyCommands.java
git commit -m "feat(commands): add /voxy resync client command"
```

---

## Manual Testing Steps

These steps validate the feature in-game. Run them after all tasks are complete.

1. **Dedicated server test (server-side command):**
   - Start a dedicated server with Voxy.
   - Join as a player, wait for some LODs to sync.
   - Run `/voxy pregen resync` as that player.
   - Observe the message: "Voxy LOD resync started for <player>".
   - Watch the client sync overlay — it should show activity as chunks are re-pushed.

2. **Dedicated server test (targeting another player):**
   - Have Player A join and move away so some LODs sync.
   - As an op (Player B), run `/voxy pregen resync PlayerA`.
   - Player A should see the sync overlay re-activate.

3. **Client-side command test:**
   - Join the dedicated server.
   - Run `/voxy resync`.
   - Observe the message: "Voxy LOD resync requested".
   - Verify the sync overlay shows activity.

4. **Offline server test:**
   - In singleplayer (integrated server), run `/voxy resync`.
   - It should succeed and re-sync LODs from the integrated server.

5. **Error case test:**
   - Connect to a non-Voxy server (or before the handshake completes).
   - Run `/voxy resync`.
   - Expect failure: "Not connected to a Voxy-aware server".

---

## Self-Review

**1. Spec coverage:**
- Server-side command with optional player target → Task 4
- Client-side command → Task 5
- New payload definition → Task 1
- Payload registration → Task 2
- Server-side handler that clears synced chunks and calls `scheduleJoinLodResync` → Task 3
- Error handling (server not running, no target player, not connected) → covered in Task 3, 4, 5 code
- Manual testing steps → listed above
- No gaps found.

**2. Placeholder scan:**
- No "TBD", "TODO", "implement later", or vague instructions found.
- Every step contains exact code and exact file paths.

**3. Type consistency:**
- `ClientRequestResyncPayload` defined in Task 1 and used in Tasks 2, 3, 5 — name is consistent.
- `handleClientResyncRequest` defined in Task 3 and called in Task 2 — name is consistent.
- `resyncSelf`, `resyncTarget`, `doResync` defined and used in Task 4 — names are consistent.
- All method signatures match the existing codebase patterns.

Plan is ready.
