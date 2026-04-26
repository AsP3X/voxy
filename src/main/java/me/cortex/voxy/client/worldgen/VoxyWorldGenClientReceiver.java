package me.cortex.voxy.client.worldgen;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import me.cortex.voxy.common.Logger;
import me.cortex.voxy.common.world.service.VoxelIngestService;
import me.cortex.voxy.commonImpl.WorldIdentifier;
import me.cortex.voxy.server.worldgen.VoxyWorldGenNetworking;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.Holder;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.DataLayer;
import net.minecraft.world.level.chunk.LevelChunkSection;
import net.minecraft.world.level.chunk.PalettedContainer;

public final class VoxyWorldGenClientReceiver {
    private VoxyWorldGenClientReceiver() {}

    public static void onHandshake(VoxyWorldGenNetworking.HandshakePayload payload) {
        NetworkState.setServerConnected(payload.serverHasMod());
    }

    @SuppressWarnings("unchecked")
    public static void onLodColumn(VoxyWorldGenNetworking.LodColumnPayload payload) {
        ClientLevel level = Minecraft.getInstance().level;
        if (level == null) return;
        if (!level.dimension().equals(payload.dimension())) return;

        long bytes = 0;
        for (var sd : payload.sections()) {
            bytes += sd.states().length + sd.biomes().length;
            if (sd.blockLight() != null) bytes += sd.blockLight().length;
            if (sd.skyLight() != null) bytes += sd.skyLight().length;
        }
        NetworkState.incrementReceived(bytes);

        for (var sectionData : payload.sections()) {
            ByteBuf statesRaw = Unpooled.wrappedBuffer(sectionData.states());
            ByteBuf biomesRaw = Unpooled.wrappedBuffer(sectionData.biomes());
            try {
                LevelChunkSection section = new LevelChunkSection(level.registryAccess().registryOrThrow(Registries.BIOME));

                RegistryFriendlyByteBuf statesBuf = new RegistryFriendlyByteBuf(new FriendlyByteBuf(statesRaw), level.registryAccess());
                ((PalettedContainer<BlockState>) section.getStates()).read(statesBuf);

                RegistryFriendlyByteBuf biomesBuf = new RegistryFriendlyByteBuf(new FriendlyByteBuf(biomesRaw), level.registryAccess());
                ((PalettedContainer<Holder<Biome>>) section.getBiomes()).read(biomesBuf);

                DataLayer bl = sectionData.blockLight() != null ? new DataLayer(sectionData.blockLight()) : null;
                DataLayer sl = sectionData.skyLight() != null ? new DataLayer(sectionData.skyLight()) : null;

                VoxelIngestService.rawIngest(WorldIdentifier.of(level), section, payload.pos().x, sectionData.y(), payload.pos().z, bl, sl);
            } catch (Exception e) {
                Logger.error("Failed to apply server LOD column for chunk " + payload.pos(), e);
            } finally {
                statesRaw.release();
                biomesRaw.release();
            }
        }
    }
}
