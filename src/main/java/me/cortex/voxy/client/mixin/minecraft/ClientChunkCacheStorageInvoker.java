package me.cortex.voxy.client.mixin.minecraft;

import net.minecraft.client.multiplayer.ClientChunkCache;
import net.minecraft.world.level.chunk.LevelChunk;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Invoker;

@Mixin(ClientChunkCache.Storage.class)
public interface ClientChunkCacheStorageInvoker {

    @Invoker("getIndex")
    int voxy$invokeGetIndex(int chunkX, int chunkZ);

    @Invoker("getChunk")
    LevelChunk voxy$invokeGetChunk(int index);
}
