package me.cortex.voxy.server.worldgen;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import me.cortex.voxy.common.Logger;
import net.neoforged.fml.loading.FMLPaths;

import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.lang.reflect.Modifier;
import java.nio.file.Files;
import java.nio.file.Path;

public final class VoxyWorldGenConfig {
    private static final Path CONFIG_PATH = FMLPaths.CONFIGDIR.get().resolve("voxy-worldgen.json");
    private static final Gson GSON = new GsonBuilder()
            .setPrettyPrinting()
            .excludeFieldsWithModifiers(Modifier.PRIVATE)
            .create();

    public static ConfigData DATA = new ConfigData();

    public static void load() {
        if (!Files.exists(CONFIG_PATH)) {
            DATA = new ConfigData();
            save();
            return;
        }
        try (Reader reader = Files.newBufferedReader(CONFIG_PATH)) {
            ConfigData parsed = GSON.fromJson(reader, ConfigData.class);
            if (parsed != null) {
                DATA = parsed;
            }
        } catch (IOException e) {
            Logger.error("Failed to load voxy-worldgen.json", e);
        }
    }

    public static void save() {
        try {
            Files.createDirectories(CONFIG_PATH.getParent());
            try (Writer writer = Files.newBufferedWriter(CONFIG_PATH)) {
                GSON.toJson(DATA, writer);
            }
        } catch (IOException e) {
            Logger.error("Failed to save voxy-worldgen.json", e);
        }
    }

    public static final class ConfigData {
        public boolean enabled = true;
        public boolean ingestEnabled = true;
        public int serviceThreads = 3;
        public boolean showF3MenuStats = true;
        public int generationRadius = 128;
        public int maxQueueSize = 20000;
        public int maxActiveTasks = 20;
        /** How often to log pregen progress on the server (in ticks, 20 ticks = 1 second). 0 = disabled. */
        public int pregenLogIntervalTicks = 600;
    }
}
