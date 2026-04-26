package me.cortex.voxy.common.voxelization;

import net.minecraft.util.BitStorage;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.Palette;

/**
 * {@link net.minecraft.world.level.chunk.PalettedContainer.Data} is not a public type; read palette and storage
 * from the Mixin {@link me.cortex.voxy.commonImpl.mixin.minecraft.PalettedContainerDataAccessor} return value.
 */
public final class PalettedContainerDataBridge {
    private static final java.lang.reflect.Method PALETTE;
    private static final java.lang.reflect.Method STORAGE;

    static {
        try {
            var data = Class.forName("net.minecraft.world.level.chunk.PalettedContainer$Data");
            PALETTE = data.getMethod("palette");
            STORAGE = data.getMethod("storage");
        } catch (ReflectiveOperationException e) {
            throw new ExceptionInInitializerError(e);
        }
    }

    @SuppressWarnings("unchecked")
    public static Palette<BlockState> palette(Object data) {
        try {
            return (Palette<BlockState>) PALETTE.invoke(data);
        } catch (ReflectiveOperationException e) {
            throw new RuntimeException(e);
        }
    }

    public static BitStorage storage(Object data) {
        try {
            return (BitStorage) STORAGE.invoke(data);
        } catch (ReflectiveOperationException e) {
            throw new RuntimeException(e);
        }
    }

    private PalettedContainerDataBridge() {
    }
}
