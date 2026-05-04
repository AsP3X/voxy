package me.cortex.voxy.client.worldgen;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import me.cortex.voxy.common.Logger;
import me.cortex.voxy.common.world.service.VoxelIngestService;
import me.cortex.voxy.commonImpl.WorldIdentifier;
import me.cortex.voxy.server.worldgen.ServerPregenProgressPayload;
import me.cortex.voxy.server.worldgen.VoxyWorldGenNetworking;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.toasts.Toast;
import net.minecraft.client.gui.components.toasts.ToastComponent;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.Holder;
import net.minecraft.core.RegistryAccess;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.DataLayer;
import net.minecraft.world.level.chunk.LevelChunkSection;
import net.minecraft.world.level.chunk.PalettedContainer;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public final class VoxyWorldGenClientReceiver {

    /**
     * Dedicated pool for LOD section deserialization and ingest.
     * Keeps all PalettedContainer I/O off the client main thread.
     * Thread count: half the available processors, min 2.
     */
    private static final ExecutorService INGEST_POOL = Executors.newFixedThreadPool(
            Math.max(2, Runtime.getRuntime().availableProcessors() / 2),
            r -> {
                Thread t = new Thread(r, "Voxy-LOD-Ingest");
                t.setDaemon(true);
                return t;
            });

    private VoxyWorldGenClientReceiver() {}

    public static void onHandshake(VoxyWorldGenNetworking.HandshakePayload payload) {
        NetworkState.setServerConnected(payload.serverHasMod());
    }

    public static void onSyncTotal(VoxyWorldGenNetworking.SyncTotalPayload payload) {
        NetworkState.setTotalToSync(payload.total());
    }

    public static void onSyncComplete(VoxyWorldGenNetworking.SyncCompletePayload payload) {
        if (!NetworkState.isServerConnected()) return;
        Minecraft mc = Minecraft.getInstance();
        if (mc.player != null) {
            mc.player.sendSystemMessage(Component.literal(
                    "Voxy LOD sync complete — " + payload.syncedChunks() + " chunks loaded"));
        }
        mc.getToasts().addToast(new SyncCompleteToast(payload.syncedChunks()));
    }

    public static void onPregenProgress(ServerPregenProgressPayload payload) {
        ServerProgressState.update(
                payload.totalTarget(),
                payload.totalRemaining(),
                payload.chunksPerSecond(),
                payload.activeTaskCount(),
                payload.pregenMode(),
                payload.paused()
        );
    }

    @SuppressWarnings("unchecked")
    public static void onLodColumn(VoxyWorldGenNetworking.LodColumnPayload payload) {
        ClientLevel level = Minecraft.getInstance().level;
        if (level == null) return;
        if (!level.dimension().equals(payload.dimension())) return;

        // Accumulate byte stats immediately on main thread
        long bytes = 0;
        for (var sd : payload.sections()) {
            bytes += sd.states().length + sd.biomes().length;
            if (sd.blockLight() != null) bytes += sd.blockLight().length;
            if (sd.skyLight() != null) bytes += sd.skyLight().length;
        }
        NetworkState.incrementReceived(bytes);

        // Capture immutable context on main thread — safe to use from any thread
        RegistryAccess registry = level.registryAccess();
        WorldIdentifier worldId = WorldIdentifier.of(level);

        // Dispatch decode + ingest to background pool — keeps main thread free
        INGEST_POOL.execute(() -> processColumn(payload, registry, worldId));
    }

    @SuppressWarnings("unchecked")
    private static void processColumn(VoxyWorldGenNetworking.LodColumnPayload payload,
                                      RegistryAccess registry,
                                      WorldIdentifier worldId) {
        for (var sectionData : payload.sections()) {
            ByteBuf statesRaw = Unpooled.wrappedBuffer(sectionData.states());
            ByteBuf biomesRaw = Unpooled.wrappedBuffer(sectionData.biomes());
            try {
                LevelChunkSection section = new LevelChunkSection(
                        registry.registryOrThrow(Registries.BIOME));

                RegistryFriendlyByteBuf statesBuf = new RegistryFriendlyByteBuf(
                        new FriendlyByteBuf(statesRaw), registry);
                ((PalettedContainer<BlockState>) section.getStates()).read(statesBuf);

                RegistryFriendlyByteBuf biomesBuf = new RegistryFriendlyByteBuf(
                        new FriendlyByteBuf(biomesRaw), registry);
                ((PalettedContainer<Holder<Biome>>) section.getBiomes()).read(biomesBuf);

                DataLayer bl = sectionData.blockLight() != null
                        ? new DataLayer(sectionData.blockLight()) : null;
                DataLayer sl = sectionData.skyLight() != null
                        ? new DataLayer(sectionData.skyLight()) : null;

                VoxelIngestService.rawIngest(worldId, section,
                        payload.pos().x, sectionData.y(), payload.pos().z, bl, sl);
            } catch (Exception e) {
                Logger.error("Failed to apply server LOD column for chunk " + payload.pos(), e);
            } finally {
                statesRaw.release();
                biomesRaw.release();
            }
        }
    }

    private static final class SyncCompleteToast implements Toast {
        private static final long DISPLAY_TIME = 5000L;
        private final Component title;
        private final Component message;

        SyncCompleteToast(int chunkCount) {
            this.title = Component.literal("Voxy Sync Complete");
            this.message = Component.literal(chunkCount + " chunks loaded");
        }

        @Override
        public Visibility render(GuiGraphics graphics, ToastComponent toastComponent, long timeSinceLastVisible) {
            graphics.fill(0, 0, this.width(), this.height(), 0xF0161616);
            graphics.drawString(toastComponent.getMinecraft().font, this.title, 8, 7, 0xFFFFFF, false);
            graphics.drawString(toastComponent.getMinecraft().font, this.message, 8, 18, 0xAAAAAA, false);
            return timeSinceLastVisible >= DISPLAY_TIME ? Visibility.HIDE : Visibility.SHOW;
        }
    }
}
