package me.cortex.voxy.client.compat;

import net.neoforged.fml.ModList;

import java.nio.file.Path;

/**
 * Flashback mod integration (optional). Install Flashback for NeoForge and extend this if you need replay path wiring.
 */
public class FlashbackCompat {
    public static final boolean FLASHBACK_INSTALLED = ModList.get() != null && ModList.get().isLoaded("flashback");

    public static Path getReplayStoragePath() {
        if (!FLASHBACK_INSTALLED) {
            return null;
        }
        // Mixin-based path injection (MixinFlashback*) was removed for the NeoForge port until optional deps are pinned.
        return null;
    }
}
