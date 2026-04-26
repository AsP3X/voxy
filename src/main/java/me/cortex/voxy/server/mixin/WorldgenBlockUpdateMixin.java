package me.cortex.voxy.server.mixin;

import me.cortex.voxy.server.worldgen.ChunkUpdateTracker;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(ServerLevel.class)
public abstract class WorldgenBlockUpdateMixin {
    @Inject(method = "sendBlockUpdated", at = @At("TAIL"))
    private void voxy$worldgenOnBlockUpdated(BlockPos pos, BlockState oldState, BlockState newState, int flags, CallbackInfo ci) {
        ServerLevel self = (ServerLevel) (Object) this;
        LevelChunk chunk = self.getChunkAt(pos);
        if (chunk != null) {
            ChunkUpdateTracker.getInstance().markDirty(chunk);
        }
    }
}
