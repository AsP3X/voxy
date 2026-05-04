package me.cortex.voxy.client;

import me.cortex.voxy.client.compat.FlashbackCompat;
import me.cortex.voxy.client.config.VoxyConfig;
import me.cortex.voxy.client.core.RenderResourceReuse;
import me.cortex.voxy.client.mixin.sodium.AccessorSodiumWorldRenderer;
import me.cortex.voxy.common.Logger;
import me.cortex.voxy.common.StorageConfigUtil;
import me.cortex.voxy.common.config.ConfigBuildCtx;
import me.cortex.voxy.common.config.Serialization;
import me.cortex.voxy.common.config.compressors.ZSTDCompressor;
import me.cortex.voxy.common.config.section.SectionSerializationStorage;
import me.cortex.voxy.common.config.section.SectionStorage;
import me.cortex.voxy.common.config.section.SectionStorageConfig;
import me.cortex.voxy.common.config.storage.other.CompressionStorageAdaptor;
import me.cortex.voxy.common.config.storage.rocksdb.RocksDBStorageBackend;
import me.cortex.voxy.commonImpl.ImportManager;
import me.cortex.voxy.commonImpl.VoxyInstance;
import me.cortex.voxy.commonImpl.WorldIdentifier;
import net.caffeinemc.mods.sodium.client.render.SodiumWorldRenderer;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.world.level.storage.LevelResource;
import java.nio.file.Path;

public class VoxyClientInstance extends VoxyInstance {
    // Set by MixinClientPacketListener before sessionStart() so resolveMultiplayerSaveSubdir()
    // can access the listener at handleLogin HEAD, before mc.player is created.
    public static ClientPacketListener pendingLoginListener;

    private final Config config;
    private final Path basePath;
    private final boolean noIngestOverride;
    public VoxyClientInstance() {
        super();
        var path = FlashbackCompat.getReplayStoragePath();
        this.noIngestOverride = path != null;
        if (path == null) {
            path = getBasePath();
        }
        this.basePath = path.normalize();
        this.config = StorageConfigUtil.getCreateStorageConfig(Config.class, c->c.version==1&&c.sectionStorageConfig!=null, ()->DEFAULT_STORAGE_CONFIG, this.basePath);
        this.updateDedicatedThreads();
    }

    @Override
    public void updateDedicatedThreads() {
        int target = VoxyConfig.CONFIG.serviceThreads;
        if (!VoxyConfig.CONFIG.dontUseSodiumBuilderThreads) {
            var swr = SodiumWorldRenderer.instanceNullable();
            if (swr != null) {
                var rsm = ((AccessorSodiumWorldRenderer) swr).getRenderSectionManager();
                if (rsm != null) {
                    this.setNumThreads(Math.max(1, target - rsm.getBuilder().getTotalThreadCount()));
                    return;
                }
            }
        }
        this.setNumThreads(target);
    }

    @Override
    protected ImportManager createImportManager() {
        return new ClientImportManager();
    }

    @Override
    protected SectionStorage createStorage(WorldIdentifier identifier) {
        var ctx = new ConfigBuildCtx();
        ctx.setProperty(ConfigBuildCtx.BASE_SAVE_PATH, this.basePath.toString());
        ctx.setProperty(ConfigBuildCtx.WORLD_IDENTIFIER, identifier.getWorldId());
        ctx.setProperty(ConfigBuildCtx.PLAYER_UUID, Minecraft.getInstance().getUser().getProfileId().toString().replace(':','-'));
        ctx.pushPath(ConfigBuildCtx.DEFAULT_STORAGE_PATH);
        return this.config.sectionStorageConfig.build(ctx);
    }

    public Path getStorageBasePath() {
        return this.basePath;
    }

    @Override
    public boolean isIngestEnabled(WorldIdentifier worldId) {
        return (!this.noIngestOverride) && VoxyConfig.CONFIG.ingestEnabled;
    }

    @Override
    public void shutdown() {
        super.shutdown();
        //Free the render resources cache since the entire instance is freed
        RenderResourceReuse.clearResources();
    }

    private static class Config {
        public int version = 1;
        public boolean disabled = false;
        public SectionStorageConfig sectionStorageConfig;
    }

    private static final Config DEFAULT_STORAGE_CONFIG;
    static {
        var config = new Config();
        config.sectionStorageConfig = StorageConfigUtil.createDefaultSerializer();
        DEFAULT_STORAGE_CONFIG = config;
    }

    private static Path getBasePath() {
        Path basePath = Minecraft.getInstance().gameDirectory.toPath().resolve(".voxy").resolve("saves");
        var iserver = Minecraft.getInstance().getSingleplayerServer();
        if (iserver != null) {
            basePath = iserver.getWorldPath(LevelResource.ROOT).resolve("voxy");
        } else {
            String sub = resolveMultiplayerSaveSubdir();
            basePath = basePath.resolve(sub);
        }
        return basePath.toAbsolutePath();
    }

    /** Folder name under {@code .voxy/saves/} for the connected remote server. */
    private static String resolveMultiplayerSaveSubdir() {
        Minecraft mc = Minecraft.getInstance();
        var fromMode = tryServerDataFromGameMode(mc);
        if (fromMode != null) {
            return fromMode;
        }
        var listener = pendingLoginListener != null ? pendingLoginListener : mc.getConnection();
        if (listener == null) {
            Logger.error("Client connection null — cannot resolve remote server for voxy storage");
            return "UNKNOWN";
        }
        var info = listener.getServerData();
        if (info != null) {
            return subdirFromServerData(info);
        }
        // Direct connect / early login: no ServerData (e.g. "localhost" from server list may still be missing)
        var conn = listener.getConnection();
        if (conn != null) {
            var remote = conn.getRemoteAddress();
            if (remote != null) {
                return remote.toString().replace(":", "_");
            }
        }
        Logger.error("Server info null");
        return "UNKNOWN";
    }

    private static String tryServerDataFromGameMode(Minecraft mc) {
        if (mc.gameMode == null) {
            return null;
        }
        var info = mc.gameMode.connection.getServerData();
        if (info == null) {
            return null;
        }
        return subdirFromServerData(info);
    }

    private static String subdirFromServerData(net.minecraft.client.multiplayer.ServerData info) {
        if (info.ip == null || info.ip.isEmpty()) {
            return "realms";
        }
        return info.ip.replace(":", "_");
    }
}
