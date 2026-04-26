package me.cortex.voxy.server;

import me.cortex.voxy.common.StorageConfigUtil;
import me.cortex.voxy.common.config.ConfigBuildCtx;
import me.cortex.voxy.common.config.section.SectionStorage;
import me.cortex.voxy.common.config.section.SectionStorageConfig;
import me.cortex.voxy.common.Logger;
import me.cortex.voxy.commonImpl.ImportManager;
import me.cortex.voxy.commonImpl.VoxyCommon;
import me.cortex.voxy.commonImpl.VoxyInstance;
import me.cortex.voxy.commonImpl.WorldIdentifier;
import me.cortex.voxy.server.worldgen.VoxyWorldGenConfig;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.storage.LevelResource;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Voxy storage + ingest on dedicated server (no Sodium / client renderer).
 * Used by server-side worldgen pre-generation to fill LOD databases.
 */
public final class VoxyDedicatedServerInstance extends VoxyInstance {
    private static volatile MinecraftServer boundServer;

    public static void bindServer(MinecraftServer server) {
        boundServer = server;
    }

    public static void unbindServer() {
        boundServer = null;
    }

    private final Path basePath;
    private final Config config;

    public VoxyDedicatedServerInstance() {
        super();
        var server = boundServer;
        if (server == null) {
            throw new IllegalStateException("bindServer(MinecraftServer) must run before creating VoxyDedicatedServerInstance");
        }
        this.basePath = server.getWorldPath(LevelResource.ROOT).resolve("voxy").normalize();
        if (VoxyCommon.IS_DEDICATED_SERVER) {
            tryRemoveLwjglZstdStorageConfigFile(this.basePath);
        }
        this.config = StorageConfigUtil.getCreateStorageConfig(Config.class, c -> c.version == 1 && c.sectionStorageConfig != null,
                () -> DEFAULT_STORAGE_CONFIG, this.basePath);
        this.updateDedicatedThreads();
    }

    /**
     * ZSTD section compression uses {@code org.lwjgl.util.zstd} and does not run on a dedicated
     * server. If an older {@code config.json} was created with ZSTD, delete it so we regenerate
     * with {@link StorageConfigUtil#createDefaultSerializerForDedicatedServer()}. Existing
     * on-disk voxy data that was only ever ZSTD-compressed (e.g. copied from client) is not
     * readable with LZ4 — users must re-ingest in that case.
     */
    private static void tryRemoveLwjglZstdStorageConfigFile(Path voxyBase) {
        Path j = voxyBase.resolve("config.json");
        if (!Files.isRegularFile(j)) {
            return;
        }
        String s;
        try {
            s = Files.readString(j);
        } catch (IOException e) {
            Logger.error("Failed to read " + j + ", leaving as-is", e);
            return;
        }
        // Section compressor: serialized with TYPE = ZSTD (Gson) + compressionLevel on ZSTD config.
        if (s.contains("compressionLevel")
                && (s.contains("\"TYPE\": \"ZSTD\"") || s.contains("\"TYPE\":\"ZSTD\""))) {
            try {
                Files.delete(j);
            } catch (IOException e) {
                Logger.error("Failed to remove ZSTD-based voxy config; dedicated server will likely crash. Delete manually: " + j, e);
                return;
            }
            Logger.warn("Removed voxy storage config.json that used ZSTD (requires LWJGL, unavailable on dedicated). "
                    + "Regenerating with LZ4. If you copied voxy data from a client world, you may need to clear "
                    + voxyBase + " and run pregen again for a clean state.");
        }
    }

    @Override
    public void updateDedicatedThreads() {
        this.setNumThreads(Math.max(1, VoxyWorldGenConfig.DATA.serviceThreads));
    }

    @Override
    protected ImportManager createImportManager() {
        return new ImportManager();
    }

    @Override
    protected SectionStorage createStorage(WorldIdentifier identifier) {
        var ctx = new ConfigBuildCtx();
        ctx.setProperty(ConfigBuildCtx.BASE_SAVE_PATH, this.basePath.toString());
        ctx.setProperty(ConfigBuildCtx.WORLD_IDENTIFIER, identifier.getWorldId());
        ctx.setProperty(ConfigBuildCtx.PLAYER_UUID, "server");
        ctx.pushPath(ConfigBuildCtx.DEFAULT_STORAGE_PATH);
        return this.config.sectionStorageConfig.build(ctx);
    }

    @Override
    public boolean isIngestEnabled(WorldIdentifier worldId) {
        return VoxyWorldGenConfig.DATA.ingestEnabled;
    }

    private static class Config {
        public int version = 1;
        public SectionStorageConfig sectionStorageConfig;
    }

    private static final Config DEFAULT_STORAGE_CONFIG;

    static {
        var c = new Config();
        c.version = 1;
        c.sectionStorageConfig = StorageConfigUtil.createDefaultSerializerForDedicatedServer();
        DEFAULT_STORAGE_CONFIG = c;
    }
}
