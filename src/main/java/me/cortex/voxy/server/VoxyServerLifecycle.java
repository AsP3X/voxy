package me.cortex.voxy.server;

import me.cortex.voxy.commonImpl.VoxyCommon;
import me.cortex.voxy.server.worldgen.ChunkGenerationManager;
import me.cortex.voxy.server.worldgen.PlayerTracker;
import me.cortex.voxy.server.worldgen.VoxyWorldGenConfig;
import me.cortex.voxy.server.worldgen.PlayerSyncStateStore;
import me.cortex.voxy.server.worldgen.ServerLodPayloadStore;
import me.cortex.voxy.server.worldgen.VoxyWorldGenNetworking;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.ChunkPos;
import java.util.List;
import net.minecraft.world.level.chunk.LevelChunk;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.RegisterCommandsEvent;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import net.neoforged.neoforge.event.level.ChunkEvent;
import net.neoforged.neoforge.event.server.ServerStartingEvent;
import net.neoforged.neoforge.event.server.ServerStoppingEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;

public final class VoxyServerLifecycle {
    private VoxyServerLifecycle() {}

    public static void register(IEventBus neoForgeBus) {
        neoForgeBus.addListener(VoxyServerLifecycle::onServerStarting);
        neoForgeBus.addListener(VoxyServerLifecycle::onServerStopping);
        neoForgeBus.addListener(VoxyServerLifecycle::onPlayerLoggedIn);
        neoForgeBus.addListener(VoxyServerLifecycle::onPlayerLoggedOut);
        neoForgeBus.addListener(VoxyServerLifecycle::onServerTickPost);
        neoForgeBus.addListener(VoxyServerLifecycle::onChunkLoad);
        neoForgeBus.addListener(RegisterCommandsEvent.class,
                event -> event.getDispatcher().register(VoxyServerCommands.register()));
    }

    private static void onServerStarting(ServerStartingEvent event) {
        VoxyWorldGenConfig.load();
        if (VoxyCommon.IS_DEDICATED_SERVER) {
            VoxyDedicatedServerInstance.bindServer(event.getServer());
            if (VoxyCommon.getInstance() == null) {
                VoxyCommon.createInstance();
            }
        }
        ChunkGenerationManager.getInstance().initialize(event.getServer());
    }

    private static void onServerStopping(ServerStoppingEvent event) {
        ChunkGenerationManager.getInstance().shutdown();
        PlayerTracker.getInstance().clear();
        PlayerSyncStateStore.getInstance().saveAll(event.getServer());
        for (ServerLevel level : event.getServer().getAllLevels()) {
            ServerLodPayloadStore.getInstance().save(level);
        }
        if (VoxyCommon.IS_DEDICATED_SERVER) {
            VoxyCommon.shutdownInstance();
            VoxyDedicatedServerInstance.unbindServer();
        }
    }

    private static void onPlayerLoggedIn(PlayerEvent.PlayerLoggedInEvent event) {
        if (!(event.getEntity() instanceof ServerPlayer player)) return;
        PlayerTracker.getInstance().addPlayer(player);
        VoxyWorldGenNetworking.sendHandshake(player);
        // Force a full sync on every login: the client’s LOD cache is in-memory only
        // and is lost on disconnect, so any persisted watermark would cause us to skip
        // columns the player never actually received.
        PlayerSyncStateStore.getInstance().resetWatermarks(player.getUUID(), player.getServer());
        player.getServer().tell(new net.minecraft.server.TickTask(
                player.getServer().getTickCount() + 20,
                () -> {
                    VoxyWorldGenNetworking.sendSyncTotal(player);
                    ServerLodPayloadStore.getInstance().scheduleDeltaSync(player);
                }));
        // Fallback: live chunks that were never persisted (e.g. server crashed before
        // auto-save) can still be synced from the in-world chunk data.
        player.getServer().tell(new net.minecraft.server.TickTask(
                player.getServer().getTickCount() + 40,
                () -> ChunkGenerationManager.getInstance().scheduleJoinLodResync(player.getUUID())));
    }

    private static void onPlayerLoggedOut(PlayerEvent.PlayerLoggedOutEvent event) {
        if (!(event.getEntity() instanceof ServerPlayer player)) return;
        ChunkGenerationManager.getInstance().clearJoinResyncState(player.getUUID());
        // Do not save the watermark: the client’s LOD cache is discarded on disconnect,
        // so persisting it would cause the next login to receive only delta updates
        // and miss everything that was already in the store.
        ServerLodPayloadStore.getInstance().clearPlayer(player.getUUID());
        PlayerTracker.getInstance().removePlayer(player);
    }

    private static void onServerTickPost(ServerTickEvent.Post event) {
        ChunkGenerationManager.getInstance().tick();
    }

    private static void onChunkLoad(ChunkEvent.Load event) {
        if (!(event.getLevel() instanceof ServerLevel level) || event.getLevel().isClientSide()) return;
        if (!(event.getChunk() instanceof LevelChunk chunk)) return;

        List<ServerPlayer> targets = null;
        for (ServerPlayer player : PlayerTracker.getInstance().getPlayers()) {
            if (player.level() != level) continue;
            // Skip players that already received this chunk via the sync worker
            var synced = PlayerTracker.getInstance().getSyncedChunks(player.getUUID());
            if (synced != null && synced.contains(chunk.getPos().toLong())) continue;
            if (targets == null) targets = new java.util.ArrayList<>();
            targets.add(player);
        }
        if (targets == null) return;

        // Build sections once, fan out to all eligible players
        var sections = VoxyWorldGenNetworking.buildSections(chunk);
        if (sections.isEmpty()) return;

        ChunkPos pos   = chunk.getPos();
        int minY       = chunk.getMinSection();
        var dim        = level.dimension();

        // Cache for full-sync replay to new players
        ServerLodPayloadStore.getInstance().storeColumn(dim, pos, minY, sections);

        for (ServerPlayer player : targets) {
            VoxyWorldGenNetworking.sendLODDataPrebuilt(player, dim, pos, minY, sections);
        }
    }
}
