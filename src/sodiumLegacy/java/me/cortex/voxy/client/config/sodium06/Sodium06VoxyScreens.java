package me.cortex.voxy.client.config.sodium06;

import net.caffeinemc.mods.sodium.client.gui.SodiumOptionsGUI;
import net.minecraft.client.gui.screens.Screen;

/** Sodium 0.6 entry: opens {@link SodiumOptionsGUI} with the Voxy tab selected. */
public final class Sodium06VoxyScreens {
    private Sodium06VoxyScreens() {}

    public static Screen openWithVoxyPage(Screen parent) {
        Screen screen = SodiumOptionsGUI.createScreen(parent);
        if (screen instanceof SodiumOptionsGUI gui) {
            gui.setPage(VoxySodium06OptionPages.voxyPage());
        }
        return screen;
    }
}
