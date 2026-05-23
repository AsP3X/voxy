package me.cortex.voxy.client.config;

import me.cortex.voxy.commonImpl.VoxyCommon;
import net.minecraft.client.gui.screens.Screen;
import net.neoforged.fml.ModList;
import org.jetbrains.annotations.Nullable;

import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.util.List;
import java.util.function.Function;

/**
 * Opens Sodium video settings with the Voxy page selected. Supports Sodium 0.6 ({@code SodiumOptionsGUI})
 * and Sodium 0.8+ ({@code VideoSettingsScreen} + config API). The 0.8 path uses reflection so this class
 * loads when only Sodium 0.6 is installed.
 */
public final class SodiumVoxyConfigHelper {
    private SodiumVoxyConfigHelper() {}

    private static final String C_VIDEO_SETTINGS =
            "net.caffeinemc.mods.sodium.client.gui.VideoSettingsScreen";
    private static final String C_CONFIG_CORRUPTED =
            "net.caffeinemc.mods.sodium.client.gui.screen.ConfigCorruptedScreen";
    private static final String C_SODIUM_CLIENT_MOD = "net.caffeinemc.mods.sodium.client.SodiumClientMod";
    private static final String C_CONFIG_MANAGER =
            "net.caffeinemc.mods.sodium.client.config.ConfigManager";
    private static final String C_MOD_OPTIONS =
            "net.caffeinemc.mods.sodium.client.config.structure.ModOptions";
    private static final String C_OPTION_PAGE =
            "net.caffeinemc.mods.sodium.client.config.structure.OptionPage";

    private static final String LEGACY_SCREENS = "me.cortex.voxy.client.config.sodium06.Sodium06VoxyScreens";

    public static @Nullable Screen createSodiumVoxyConfigScreen(Screen parent) {
        if (!VoxyCommon.isAvailable() || ModList.get() == null || !ModList.get().isLoaded("sodium")) {
            return null;
        }
        ClassLoader cl = SodiumVoxyConfigHelper.class.getClassLoader();
        return switch (SodiumVoxyGuiApi.detect(cl)) {
            case MODERN_08 -> createModern08Screen(parent, cl);
            case LEGACY_06 -> createLegacy06Screen(parent, cl);
            case NONE -> null;
        };
    }

    private static @Nullable Screen createLegacy06Screen(Screen parent, ClassLoader cl) {
        try {
            Class<?> bridge = Class.forName(LEGACY_SCREENS, true, cl);
            Method m = bridge.getMethod("openWithVoxyPage", Screen.class);
            return (Screen) m.invoke(null, parent);
        } catch (ReflectiveOperationException | LinkageError e) {
            return null;
        }
    }

    private static @Nullable Screen createModern08Screen(Screen parent, ClassLoader cl) {
        try {
            Class<?> videoSettings = Class.forName(C_VIDEO_SETTINGS, true, cl);
            Class<?> optionPageClass = Class.forName(C_OPTION_PAGE, true, cl);
            Class<?> corrupted = Class.forName(C_CONFIG_CORRUPTED, true, cl);
            Class<?> sodiumClientMod = Class.forName(C_SODIUM_CLIENT_MOD, true, cl);

            Object voxyPage = findModernVoxyPage(cl, optionPageClass);
            Object gameOptions = sodiumClientMod.getMethod("options").invoke(null);
            boolean readOnly = (boolean) gameOptions.getClass().getMethod("isReadOnly").invoke(gameOptions);

            Method create1 = videoSettings.getMethod("createScreen", Screen.class);
            Method create2 = videoSettings.getMethod("createScreen", Screen.class, optionPageClass);

            if (readOnly) {
                return openCorrupted(parent, corrupted, videoSettings, create1, create2, voxyPage);
            }
            if (voxyPage != null) {
                return (Screen) create2.invoke(null, parent, voxyPage);
            }
            return (Screen) create1.invoke(null, parent);
        } catch (ReflectiveOperationException | LinkageError | ClassCastException e) {
            return null;
        }
    }

    private static Screen openCorrupted(
            Screen parent,
            Class<?> corruptedClass,
            Class<?> videoSettingsClass,
            Method create1,
            Method create2,
            @Nullable Object voxyPage
    ) throws ReflectiveOperationException {
        @SuppressWarnings("unchecked")
        Constructor<?> ctor = corruptedClass.getConstructor(Screen.class, Function.class);
        Function<Screen, Screen> next;
        if (voxyPage != null) {
            next = p -> {
                try {
                    return (Screen) create2.invoke(null, p, voxyPage);
                } catch (ReflectiveOperationException e) {
                    throw new RuntimeException(e);
                }
            };
        } else {
            next = p -> {
                try {
                    return (Screen) create1.invoke(null, p);
                } catch (ReflectiveOperationException e) {
                    throw new RuntimeException(e);
                }
            };
        }
        return (Screen) ctor.newInstance(parent, next);
    }

    private static @Nullable Object findModernVoxyPage(ClassLoader cl, Class<?> optionPageClass) {
        try {
            Class<?> configManager = Class.forName(C_CONFIG_MANAGER, true, cl);
            Object config = configManager.getField("CONFIG").get(null);
            if (config == null) {
                return null;
            }
            Class<?> modOptionsClass = Class.forName(C_MOD_OPTIONS, true, cl);
            Method getModOptions = config.getClass().getMethod("getModOptions");
            @SuppressWarnings("unchecked")
            List<Object> modOptionsList = (List<Object>) getModOptions.invoke(config);
            Method configId = modOptionsClass.getMethod("configId");
            Method pages = modOptionsClass.getMethod("pages");
            for (Object mo : modOptionsList) {
                if (!"voxy".equals(configId.invoke(mo))) {
                    continue;
                }
                Iterable<?> pageList = (Iterable<?>) pages.invoke(mo);
                for (Object p : pageList) {
                    if (optionPageClass.isInstance(p)) {
                        return p;
                    }
                }
            }
        } catch (ReflectiveOperationException | LinkageError ignored) {
        }
        return null;
    }
}
