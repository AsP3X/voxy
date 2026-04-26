package me.cortex.voxy.client;

import me.cortex.voxy.client.core.gl.Capabilities;
import me.cortex.voxy.client.core.rendering.util.SharedIndexBuffer;
import me.cortex.voxy.client.core.util.ExpansionUtil;
import me.cortex.voxy.client.config.VoxyConfig;
import me.cortex.voxy.common.Logger;
import me.cortex.voxy.commonImpl.VoxyCommon;
import net.minecraft.client.Minecraft;

import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.channels.FileLock;
import java.nio.channels.NonWritableChannelException;
import java.util.HashSet;
import java.util.function.Consumer;
import java.util.function.Function;

public final class VoxyClient {
    private static final HashSet<String> FREX = new HashSet<>();
    private static FileLock EXCLUSIVE_LOCK;
    private static boolean INSTANCE_FACTORY_SET;
    private static boolean RENDER_BACKEND_READY;
    private static boolean RENDER_BACKEND_INITIALIZED;

    private static VoxyClientInstance createInstance() {
        if (!RENDER_BACKEND_READY) {
            Logger.error("Voxy render backend is not initialized");
            return null;
        }
        return new VoxyClientInstance();
    }

    private static void setInstanceFactory() {
        if (!INSTANCE_FACTORY_SET) {
            VoxyCommon.setInstanceFactory(VoxyClient::createInstance);
            INSTANCE_FACTORY_SET = true;
        }
        VoxyConfig.reloadAfterVoxyAvailable();
    }

    public static void initVoxyClient() {
        if (RENDER_BACKEND_INITIALIZED) {
            return;
        }
        RENDER_BACKEND_INITIALIZED = true;
        setInstanceFactory();

        Capabilities.init();

        if (Capabilities.INSTANCE.hasBrokenDepthSampler) {
            Logger.error("AMD broken depth sampler detected, voxy does not work correctly and has been disabled, this will hopefully be fixed in the future");
        }

        boolean systemSupported = Capabilities.INSTANCE.compute && Capabilities.INSTANCE.indirectParameters && !Capabilities.INSTANCE.hasBrokenDepthSampler;
        if (!systemSupported) {
            Logger.error("Voxy is unsupported on your system.");
        }

        if (systemSupported && "true".equalsIgnoreCase(System.getProperty("voxy.exclusiveLock", "false"))) {
            var vf = Minecraft.getInstance().gameDirectory.toPath().resolve(".voxy");
            if (!vf.toFile().isDirectory()) {
                //noinspection ResultOfMethodCallIgnored
                vf.toFile().mkdirs();
            }
            try {
                FileOutputStream fis = new FileOutputStream(vf.resolve("voxy.lock").toFile());
                EXCLUSIVE_LOCK = fis.getChannel().lock(0, Long.MAX_VALUE, false);
            } catch (NonWritableChannelException | IOException e) {
                Logger.error("Failed to acquire exclusive voxy lock file, mod will be disabled");
                systemSupported = false;
            }
        }

        if (systemSupported) {
            SharedIndexBuffer.INSTANCE.id();
            RENDER_BACKEND_READY = true;
            if (!Capabilities.INSTANCE.subgroup) {
                Logger.warn("GPU does not support subgroup operations, expect some performance degradation");
            }
        }

        if (!ExpansionUtil.isJava21()) {
            Logger.warn("Cannot use native Integer/Long compression. Using fallback...");
        }
    }

    /**
     * NeoForge: called from FMLClientSetupEvent (replaces Fabric ClientModInitializer + command callback).
     */
    public static void onNeoForgeClientInit() {
        setInstanceFactory();
        // Frex: Fabric entrypoints (frex_flawless_frames) — not available on NeoForge; keep stub set empty.
    }

    public static boolean isFrexActive() {
        return !FREX.isEmpty();
    }

    public static int getOcclusionDebugState() {
        return 0;
    }

    public static boolean disableSodiumChunkRender() {
        return false;
    }
}
