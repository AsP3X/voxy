package me.cortex.voxy.client.config;

/**
 * Detects which Sodium options GUI stack is present at runtime (0.6 vs 0.8+).
 * Uses {@link ClassLoader#getResource(String)} on {@code *.class} paths only — never loads the type,
 * which avoids breaking other mods' mixins (e.g. Iris) that must prepare before Sodium GUI classes load.
 */
public enum SodiumVoxyGuiApi {
    /** Sodium not present or unknown layout */
    NONE,
    /** Sodium 0.6: {@code SodiumOptionsGUI} without Caffeine {@code VideoSettingsScreen} */
    LEGACY_06,
    /** Sodium 0.8+: {@code net.caffeinemc...gui.VideoSettingsScreen} + config API */
    MODERN_08;

    private static final String MODERN_VIDEO_SETTINGS =
            "net.caffeinemc.mods.sodium.client.gui.VideoSettingsScreen";
    private static final String LEGACY_OPTIONS_GUI =
            "net.caffeinemc.mods.sodium.client.gui.SodiumOptionsGUI";

    public static SodiumVoxyGuiApi detect(ClassLoader loader) {
        if (classExists(MODERN_VIDEO_SETTINGS, loader)) {
            return MODERN_08;
        }
        if (classExists(LEGACY_OPTIONS_GUI, loader)) {
            return LEGACY_06;
        }
        return NONE;
    }

    private static boolean classExists(String binaryName, ClassLoader loader) {
        String path = binaryName.replace('.', '/') + ".class";
        return loader.getResource(path) != null;
    }
}
