package me.cortex.voxy.server.worldgen;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class PlayerSyncStateStoreTest {

    @Test
    void roundTrip_emptyMap(@TempDir Path dir) throws IOException {
        Path file = dir.resolve("test.bin");
        PlayerSyncStateStore.writeFile(file, Map.of());
        Map<String, Long> result = PlayerSyncStateStore.readFile(file);
        assertTrue(result.isEmpty());
    }

    @Test
    void roundTrip_singleEntry(@TempDir Path dir) throws IOException {
        Path file = dir.resolve("test.bin");
        PlayerSyncStateStore.writeFile(file, Map.of("minecraft:overworld", 12345L));
        Map<String, Long> result = PlayerSyncStateStore.readFile(file);
        assertEquals(1, result.size());
        assertEquals(12345L, result.get("minecraft:overworld"));
    }

    @Test
    void roundTrip_multipleDimensions(@TempDir Path dir) throws IOException {
        Path file = dir.resolve("test.bin");
        Map<String, Long> data = new HashMap<>();
        data.put("minecraft:overworld", 100L);
        data.put("minecraft:the_nether", 200L);
        data.put("minecraft:the_end", 300L);
        PlayerSyncStateStore.writeFile(file, data);
        Map<String, Long> result = PlayerSyncStateStore.readFile(file);
        assertEquals(3, result.size());
        assertEquals(100L, result.get("minecraft:overworld"));
        assertEquals(200L, result.get("minecraft:the_nether"));
        assertEquals(300L, result.get("minecraft:the_end"));
    }

    @Test
    void wrongMagic_returnsEmpty(@TempDir Path dir) throws IOException {
        Path file = dir.resolve("bad.bin");
        try (DataOutputStream out = new DataOutputStream(Files.newOutputStream(file))) {
            out.writeInt(0xDEADBEEF);
            out.writeInt(1);
            out.writeInt(0);
        }
        Map<String, Long> result = PlayerSyncStateStore.readFile(file);
        assertTrue(result.isEmpty());
    }

    @Test
    void wrongVersion_returnsEmpty(@TempDir Path dir) throws IOException {
        Path file = dir.resolve("bad_version.bin");
        try (DataOutputStream out = new DataOutputStream(Files.newOutputStream(file))) {
            out.writeInt(0x564F5953); // correct MAGIC
            out.writeInt(99);         // wrong VERSION
            out.writeInt(0);
        }
        Map<String, Long> result = PlayerSyncStateStore.readFile(file);
        assertTrue(result.isEmpty());
    }

    @Test
    void missingFile_throws(@TempDir Path dir) {
        Path file = dir.resolve("nonexistent.bin");
        assertThrows(java.nio.file.NoSuchFileException.class,
                () -> PlayerSyncStateStore.readFile(file));
    }
}
