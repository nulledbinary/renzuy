package renzuy.config;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Per-guild prefix storage. One single-character prefix per guild; defaults to "!".
 *
 * <p>The store keeps state in memory and writes it atomically to a small text file
 * (one {@code guildId=prefix} per line) so it survives container restarts. The file
 * path is {@code $RENZUY_CONFIG_DIR/prefixes.properties} (falls back to
 * {@code ./config/prefixes.properties} when the env var is unset).
 */
public final class PrefixStore {

    public static final String DEFAULT_PREFIX = "!";

    /** Allowed prefix characters — single ASCII special character. */
    private static final String ALLOWED = "!@#$%^&*?.,;:~+-=<>|/\\";

    private final Path file;
    private final Map<Long, String> prefixes = new ConcurrentHashMap<>();

    public PrefixStore(Path file) {
        this.file = file;
        load();
    }

    public static PrefixStore defaultLocation() {
        String dir = System.getenv("RENZUY_CONFIG_DIR");
        Path base = (dir == null || dir.isBlank()) ? Path.of("config") : Path.of(dir);
        return new PrefixStore(base.resolve("prefixes.properties"));
    }

    public String get(long guildId) {
        return prefixes.getOrDefault(guildId, DEFAULT_PREFIX);
    }

    /** @return {@code true} iff {@code candidate} is exactly one of the allowed characters. */
    public static boolean isValid(String candidate) {
        return candidate != null
                && candidate.length() == 1
                && ALLOWED.indexOf(candidate.charAt(0)) >= 0;
    }

    /**
     * Sets and persists a guild's prefix. Returns {@code false} (and changes nothing) if
     * the candidate is not a valid single special character.
     */
    public boolean set(long guildId, String candidate) {
        if (!isValid(candidate)) {
            return false;
        }
        prefixes.put(guildId, candidate);
        save();
        return true;
    }

    private void load() {
        if (!Files.isRegularFile(file)) {
            return;
        }
        try {
            for (String line : Files.readAllLines(file, StandardCharsets.UTF_8)) {
                String stripped = line.strip();
                if (stripped.isEmpty() || stripped.startsWith("#")) continue;
                int eq = stripped.indexOf('=');
                if (eq <= 0 || eq == stripped.length() - 1) continue;
                long guildId;
                try {
                    guildId = Long.parseLong(stripped.substring(0, eq).strip());
                } catch (NumberFormatException ignored) {
                    continue;
                }
                String value = stripped.substring(eq + 1).strip();
                if (isValid(value)) {
                    prefixes.put(guildId, value);
                }
            }
        } catch (IOException e) {
            System.err.println("[PrefixStore] Could not read " + file + ": " + e.getMessage());
        }
    }

    private synchronized void save() {
        try {
            Path parent = file.getParent();
            if (parent != null) {
                Files.createDirectories(parent);
            }
            Path tmp = file.resolveSibling(file.getFileName() + ".tmp");
            Map<Long, String> snapshot = new HashMap<>(prefixes);
            StringBuilder body = new StringBuilder(snapshot.size() * 24);
            for (Map.Entry<Long, String> entry : snapshot.entrySet()) {
                body.append(entry.getKey()).append('=').append(entry.getValue()).append('\n');
            }
            Files.writeString(tmp, body.toString(), StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
            Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (IOException e) {
            System.err.println("[PrefixStore] Could not persist " + file + ": " + e.getMessage());
        }
    }
}
