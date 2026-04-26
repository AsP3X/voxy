package me.cortex.voxy.commonImpl;

import me.cortex.voxy.common.Logger;
import me.cortex.voxy.common.config.Serialization;
import me.cortex.voxy.VoxyMod;
import net.neoforged.fml.ModContainer;
import net.neoforged.fml.loading.FMLEnvironment;
import net.neoforged.api.distmarker.Dist;
import org.jetbrains.annotations.Nullable;

import java.io.IOException;
import java.io.InputStream;
import java.util.Properties;

public class VoxyCommon {
    public static String MOD_VERSION = "<UNKNOWN>";
    public static boolean IS_DEDICATED_SERVER;
    public static boolean IS_IN_MINECRAFT;

    public static void initNeoForge(@Nullable ModContainer mod) {
        VoxyMod.INITIALISED = true;
        if (mod == null) {
            IS_IN_MINECRAFT = false;
            Logger.error("Running voxy without mod container");
            IS_DEDICATED_SERVER = FMLEnvironment.dist == Dist.DEDICATED_SERVER;
            return;
        }
        IS_IN_MINECRAFT = true;
        var ver = mod.getModInfo().getVersion();
        var verStr = ver != null ? ver.toString() : "0.0.0";
        var commit = readBuildCommit();
        MOD_VERSION = verStr + "-" + (commit != null && commit.length() >= 7 ? commit.substring(0, 7) : (commit != null ? commit : "?"));
        IS_DEDICATED_SERVER = FMLEnvironment.dist == Dist.DEDICATED_SERVER;
        Serialization.init();
    }

    private static @Nullable String readBuildCommit() {
        try (InputStream in = VoxyCommon.class.getResourceAsStream("/voxy.build.properties")) {
            if (in == null) return "dev";
            var p = new Properties();
            p.load(in);
            return p.getProperty("commit", "dev");
        } catch (IOException e) {
            return "dev";
        }
    }

    public static boolean isVerificationFlagOn(String name) {
        return isVerificationFlagOn(name, false);
    }

    public static boolean isVerificationFlagOn(String name, boolean defaultOn) {
        return "true".equalsIgnoreCase(System.getProperty("voxy." + name, defaultOn ? "true" : "false"));
    }

    public static void breakpoint() {
        int ignored = 0;
    }

    @SuppressWarnings("EmptyMethod")
    public void onInitialize() { }

    public interface IInstanceFactory { VoxyInstance create(); }
    private static VoxyInstance INSTANCE;
    private static IInstanceFactory FACTORY = null;

    public static void setInstanceFactory(IInstanceFactory factory) {
        if (FACTORY != null) {
            throw new IllegalStateException("Cannot set instance factory more than once");
        }
        FACTORY = factory;
    }

    public static VoxyInstance getInstance() {
        return INSTANCE;
    }

    public static void shutdownInstance() {
        if (INSTANCE != null) {
            var inst = INSTANCE;
            INSTANCE = null;
            inst.shutdown();
        }
    }

    public static void createInstance() {
        if (FACTORY == null) {
            return;
        }
        if (INSTANCE != null) {
            throw new IllegalStateException("Cannot create multiple instances");
        }
        VoxyInstance n = FACTORY.create();
        if (n != null) {
            INSTANCE = n;
        }
    }

    public static boolean isAvailable() {
        return FACTORY != null;
    }

    public static final boolean IS_MINE_IN_ABYSS = false;
}
