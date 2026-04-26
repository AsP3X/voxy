package me.cortex.voxy.commonImpl.mixin.minecraft;

import net.minecraft.world.level.chunk.PalettedContainer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

@Mixin(PalettedContainer.class)
public interface PalettedContainerDataAccessor {

    /**
     * Descriptor must match {@code PalettedContainer$Data}, not {@link Object}, for Mixin to locate the field.
     * {@link PalettedContainer.Data} is widened to public via {@code META-INF/accesstransformer.cfg} (record + ctor).
     */
    @Accessor("data")
    PalettedContainer.Data<?> voxy$getData();
}
