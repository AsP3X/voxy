package me.cortex.voxy.client.config;

import me.cortex.voxy.client.mixin.sodium.AccessorSodiumOptionsGUI;
import me.cortex.voxy.common.Logger;
import me.cortex.voxy.commonImpl.VoxyCommon;
import net.neoforged.fml.ModList;
import net.caffeinemc.mods.sodium.client.SodiumClientMod;
import net.caffeinemc.mods.sodium.client.gui.SodiumOptionsGUI;
import net.caffeinemc.mods.sodium.client.gui.screen.ConfigCorruptedScreen;
import net.minecraft.client.gui.screens.Screen;
import org.jetbrains.annotations.Nullable;

/**
 * Replaces Fabric ModMenu integration: open Sodium options with the Voxy page pre-selected.
 * Call from a keybind or your own entry (NeoForge has no single “mod list config” API like ModMenu).
 */
public final class SodiumVoxyConfigHelper {
    private SodiumVoxyConfigHelper() {}

    public static @Nullable Screen createSodiumVoxyConfigScreen(Screen parent) {
        if (!VoxyCommon.isAvailable() || ModList.get() == null || !ModList.get().isLoaded("sodium")) {
            return null;
        }
        Screen screen = SodiumClientMod.options().isReadOnly()
                ? new ConfigCorruptedScreen(parent, AccessorSodiumOptionsGUI::newScreen)
                : AccessorSodiumOptionsGUI.newScreen(parent);
        try {
            var field = SodiumOptionsGUI.class.getDeclaredField("currentPage");
            field.setAccessible(true);
            field.set(screen, VoxyConfigScreenPages.voxyOptionPage);
            field.setAccessible(false);
        } catch (Exception e) {
            Logger.error("Failed to set the current page to voxy", e);
        }
        return screen;
    }
}
