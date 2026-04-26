package me.cortex.voxy.server.worldgen;

import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ChunkPos;

/**
 * Placeholder for Tellus mod integration (Fabric voxy_worldgen_v2). Disabled on NeoForge port.
 */
public final class TellusGenStub {
    private TellusGenStub() {}

    public static void shutdown() {
        // no-op
    }

    public static boolean isTellusWorld(ServerLevel level) {
        return false;
    }

    public static void enqueueGenerate(ServerLevel level, ChunkPos pos, Runnable onComplete) {
        if (onComplete != null) {
            onComplete.run();
        }
    }
}
