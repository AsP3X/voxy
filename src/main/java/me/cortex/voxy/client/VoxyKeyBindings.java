package me.cortex.voxy.client;

import me.cortex.voxy.client.config.SodiumVoxyConfigHelper;
import me.cortex.voxy.common.Logger;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.client.event.RegisterKeyMappingsEvent;
import org.lwjgl.glfw.GLFW;

/**
 * Discoverable entry to Sodium + Voxy options (ModMenu replacement on NeoForge).
 * Registered from {@link me.cortex.voxy.VoxyMod} on the mod bus and NeoForge game bus.
 */
public final class VoxyKeyBindings {
    public static final KeyMapping OPEN_SODIUM_VOXY_OPTIONS = new KeyMapping(
            "key.voxy.open_sodium_voxy_options",
            GLFW.GLFW_KEY_O,
            KeyMapping.CATEGORY_MISC
    );

    private VoxyKeyBindings() {
    }

    public static void registerKeys(RegisterKeyMappingsEvent event) {
        event.register(OPEN_SODIUM_VOXY_OPTIONS);
    }

    public static void onClientTick(ClientTickEvent.Post event) {
        while (OPEN_SODIUM_VOXY_OPTIONS.consumeClick()) {
            var mc = Minecraft.getInstance();
            var screen = SodiumVoxyConfigHelper.createSodiumVoxyConfigScreen(mc.screen);
            if (screen != null) {
                mc.setScreen(screen);
            } else {
                Logger.warn("Sodium + Voxy options unavailable (Sodium not loaded or Voxy disabled).");
            }
        }
    }
}
