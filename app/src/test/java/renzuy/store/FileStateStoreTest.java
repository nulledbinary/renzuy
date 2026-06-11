package renzuy.store;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class FileStateStoreTest {

    @TempDir
    Path dir;

    @Test
    void putGetRoundTrip() {
        FileStateStore store = new FileStateStore(dir.resolve("state.properties"));
        store.put("guild#1", "counting", Map.of("channelId", "42", "count", "7"));

        Optional<Map<String, String>> read = store.get("guild#1", "counting");
        assertTrue(read.isPresent());
        assertEquals("42", read.get().get("channelId"));
        assertEquals("7", read.get().get("count"));
    }

    @Test
    void survivesReload() {
        Path file = dir.resolve("state.properties");
        new FileStateStore(file).put("guild#1", "bind#confession",
                Map.of("channelId", "123456789"));

        FileStateStore reloaded = new FileStateStore(file);
        assertEquals("123456789",
                reloaded.get("guild#1", "bind#confession").orElseThrow().get("channelId"));
    }

    @Test
    void queryReturnsOnlyMatchingPartition() {
        FileStateStore store = new FileStateStore(dir.resolve("state.properties"));
        store.put("guild#1", "bind#confession", Map.of("channelId", "1"));
        store.put("guild#1", "bind#nickname", Map.of("channelId", "2"));
        store.put("guild#2", "bind#confession", Map.of("channelId", "3"));

        Map<String, Map<String, String>> items = store.query("guild#1");
        assertEquals(2, items.size());
        assertEquals("1", items.get("bind#confession").get("channelId"));
        assertEquals("2", items.get("bind#nickname").get("channelId"));
    }

    @Test
    void deleteRemovesItem() {
        FileStateStore store = new FileStateStore(dir.resolve("state.properties"));
        store.put("guild#1", "lockdown#9", Map.of("reason", "raid in progress"));
        store.delete("guild#1", "lockdown#9");
        assertTrue(store.get("guild#1", "lockdown#9").isEmpty());
    }

    @Test
    void valuesWithSeparatorsSurvive() {
        FileStateStore store = new FileStateStore(dir.resolve("state.properties"));
        String tricky = "multi word = value & more\nnewline";
        store.put("guild#1", "lockdown#1", Map.of("reason", tricky));
        assertEquals(tricky, store.get("guild#1", "lockdown#1").orElseThrow().get("reason"));
    }
}
