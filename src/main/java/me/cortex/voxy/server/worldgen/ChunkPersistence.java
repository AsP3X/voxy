package me.cortex.voxy.server.worldgen;

import me.cortex.voxy.common.Logger;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.storage.LevelResource;

import java.io.*;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Set;

public class ChunkPersistence {
    
    public static void save(ServerLevel level, ResourceKey<Level> dimKey, Set<Long> completedChunks) {
        if (level == null || dimKey == null) return;
        
        try {
            Path savePath = getGenerationCachePath(level, dimKey);
            if (savePath == null) {
                return;
            }
            try (DataOutputStream out = new DataOutputStream(new BufferedOutputStream(Files.newOutputStream(savePath)))) {
                synchronized(completedChunks) {
                    out.writeInt(completedChunks.size());
                    for (Long chunkPos : completedChunks) {
                        out.writeLong(chunkPos);
                    }
                }
            }
        } catch (Exception e) {
            Logger.error("failed to save chunk generation cache", e);
        }
    }
    
    /**
     * World-root file where completed chunk column keys are stored for a dimension
     * (used to resume / skip voxy pregen). Deleting it forces a full re-run for that dimension.
     */
    public static Path getGenerationCachePath(ServerLevel level, ResourceKey<Level> dimKey) {
        if (level == null || dimKey == null) {
            return null;
        }
        String dimId = getDimensionId(dimKey);
        return level.getServer().getWorldPath(LevelResource.ROOT).resolve("voxy_gen_" + dimId + ".bin");
    }

    public static void load(ServerLevel level, ResourceKey<Level> dimKey, Set<Long> completedChunks) {
        completedChunks.clear();
        if (level == null || dimKey == null) return;
        
        try {
            Path savePath = getGenerationCachePath(level, dimKey);
            if (savePath != null && Files.exists(savePath)) {
                try (DataInputStream in = new DataInputStream(new BufferedInputStream(Files.newInputStream(savePath)))) {
                    int count = in.readInt();
                    for (int i = 0; i < count; i++) {
                        completedChunks.add(in.readLong());
                    }
                }
                Logger.info("loaded " + completedChunks.size() + " chunks from voxy generation cache for " + dimKey
                        + " (file: " + savePath + ")");
            }
        } catch (Exception e) {
            Logger.error("failed to load chunk generation cache", e);
        }
    }

    private static String getDimensionId(ResourceKey<Level> dimKey) {
        return dimKey.toString()
                .replace("ResourceKey[", "")
                .replace("]", "")
                .replace("/", "_")
                .replace(":", "_")
                .trim();
    }
}
