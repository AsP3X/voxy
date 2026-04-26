package me.cortex.voxy.server;

import me.cortex.voxy.commonImpl.VoxyCommon;
import me.cortex.voxy.server.worldgen.ChunkGenerationManager;
import me.cortex.voxy.server.worldgen.PlayerTracker;
import me.cortex.voxy.server.worldgen.VoxyWorldGenConfig;
import me.cortex.voxy.server.worldgen.VoxyWorldGenNetworking;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.chunk.LevelChunk;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.common.NeoForge;
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
        if (VoxyCommon.IS_DEDICATED_SERVER) {
            VoxyCommon.shutdownInstance();
            VoxyDedicatedServerInstance.unbindServer();
        }
    }

    private static void onPlayerLoggedIn(PlayerEvent.PlayerLoggedInEvent event) {
        if (!(event.getEntity() instanceof ServerPlayer player)) return;
        PlayerTracker.getInstance().addPlayer(player);
        VoxyWorldGenNetworking.sendHandshake(player);
    }

    private static void onPlayerLoggedOut(PlayerEvent.PlayerLoggedOutEvent event) {
        if (!(event.getEntity() instanceof ServerPlayer player)) return;
        PlayerTracker.getInstance().removePlayer(player);
    }

    private static void onServerTickPost(ServerTickEvent.Post event) {
        ChunkGenerationManager.getInstance().tick();
    }

    private static void onChunkLoad(ChunkEvent.Load event) {
        if (!(event.getLevel() instanceof ServerLevel level) || event.getLevel().isClientSide()) return;
        if (!(event.getChunk() instanceof LevelChunk chunk)) return;
        for (ServerPlayer player : PlayerTracker.getInstance().getPlayers()) {
            if (player.level() == level) {
                VoxyWorldGenNetworking.sendLODData(player, chunk);
            }
        }
    }
}
