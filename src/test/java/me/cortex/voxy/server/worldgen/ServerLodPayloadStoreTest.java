package me.cortex.voxy.server.worldgen;

import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import static org.junit.jupiter.api.Assertions.*;

class ServerLodPayloadStoreTest {

    private static ResourceKey<Level> overworld() {
        return ResourceKey.create(Registries.DIMENSION,
                ResourceLocation.fromNamespaceAndPath("minecraft", "overworld"));
    }

    private static ServerLodPayloadStore.VersionedColumn col(int x, int z, long version) {
        var payload = new VoxyWorldGenNetworking.LodColumnPayload(
                overworld(), new ChunkPos(x, z), 0, List.of());
        return new ServerLodPayloadStore.VersionedColumn(payload, version);
    }

    @Test
    void collectDelta_excludesColumnsAtOrBelowWatermark() {
        Map<Long, ServerLodPayloadStore.VersionedColumn> cache = new ConcurrentHashMap<>();
        cache.put(new ChunkPos(0, 0).toLong(), col(0, 0, 5L));
        cache.put(new ChunkPos(1, 0).toLong(), col(1, 0, 10L));
        cache.put(new ChunkPos(2, 0).toLong(), col(2, 0, 15L));

        var delta = ServerLodPayloadStore.collectDelta(cache, 10L, new ChunkPos(0, 0));

        assertEquals(1, delta.size());
        assertEquals(new ChunkPos(2, 0), delta.get(0).payload().pos());
    }

    @Test
    void collectDelta_sortedByDistanceNearestFirst() {
        Map<Long, ServerLodPayloadStore.VersionedColumn> cache = new ConcurrentHashMap<>();
        cache.put(new ChunkPos(10, 0).toLong(), col(10, 0, 1L));
        cache.put(new ChunkPos(1, 0).toLong(),  col(1, 0, 1L));
        cache.put(new ChunkPos(5, 0).toLong(),  col(5, 0, 1L));

        var delta = ServerLodPayloadStore.collectDelta(cache, 0L, new ChunkPos(0, 0));

        assertEquals(3, delta.size());
        assertEquals(new ChunkPos(1, 0),  delta.get(0).payload().pos());
        assertEquals(new ChunkPos(5, 0),  delta.get(1).payload().pos());
        assertEquals(new ChunkPos(10, 0), delta.get(2).payload().pos());
    }

    @Test
    void collectDelta_watermarkZero_returnsAll() {
        Map<Long, ServerLodPayloadStore.VersionedColumn> cache = new ConcurrentHashMap<>();
        cache.put(new ChunkPos(0, 0).toLong(), col(0, 0, 1L));
        cache.put(new ChunkPos(1, 0).toLong(), col(1, 0, 2L));

        var delta = ServerLodPayloadStore.collectDelta(cache, 0L, new ChunkPos(0, 0));

        assertEquals(2, delta.size());
    }
}
