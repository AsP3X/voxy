package me.cortex.voxy.server.worldgen;

import me.cortex.voxy.common.world.service.VoxelIngestService;
import me.cortex.voxy.commonImpl.VoxyCommon;
import me.cortex.voxy.commonImpl.WorldIdentifier;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.chunk.DataLayer;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.LevelChunkSection;

public final class WorldGenVoxyHooks {
    private WorldGenVoxyHooks() {}

    public static void ingestChunk(LevelChunk chunk) {
        VoxelIngestService.tryAutoIngestChunk(chunk);
    }

    public static void rawIngest(Level level, LevelChunkSection section, int cx, int cy, int cz, DataLayer blockLight, DataLayer skyLight) {
        WorldIdentifier id = WorldIdentifier.of(level);
        if (id == null) return;
        VoxelIngestService.rawIngest(id, section, cx, cy, cz, blockLight, skyLight);
    }

    /**
     * When false, the background worker sleeps (matches legacy "wait until Voxy is usable" behaviour).
     */
    public static boolean isGenerationUnpaused() {
        if (VoxyCommon.getInstance() == null) {
            return false;
        }
        return VoxyWorldGenConfig.DATA.enabled && VoxyWorldGenConfig.DATA.ingestEnabled;
    }
}
