package renzuy.counting;

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
 * Per-guild counting-game state: which channel hosts the game, the current
 * count, and who counted last (to enforce the no-double-counting rule).
 *
 * <p>Mirrors {@link renzuy.config.PrefixStore}: state lives in memory and is
 * written atomically to {@code $RENZUY_CONFIG_DIR/counting.properties}
 * (default {@code ./config/counting.properties}) as one
 * {@code guildId=channelId|count|lastUserId} line per guild, so the count
 * survives container restarts.
 */
public final class CountingStore {

    /** Immutable snapshot of one guild's game. {@code lastUserId} is 0 after a reset. */
    public record State(long channelId, long count, long lastUserId) {}

    private final Path file;
    private final Map<Long, State> states = new ConcurrentHashMap<>();

    public CountingStore(Path file) {
        this.file = file;
        load();
    }

    public static CountingStore defaultLocation() {
        String dir = System.getenv("RENZUY_CONFIG_DIR");
        Path base = (dir == null || dir.isBlank()) ? Path.of("config") : Path.of(dir);
        return new CountingStore(base.resolve("counting.properties"));
    }

    /** @return the guild's game state, or {@code null} when no channel is bound. */
    public State get(long guildId) {
        return states.get(guildId);
    }

    /**
     * Binds the game to a channel. Re-binding the same channel keeps the run
     * going; moving to a different channel starts a fresh run from 0.
     *
     * @return the previous state, or {@code null} if the game was not set up yet
     */
    public State bind(long guildId, long channelId) {
        State previous = states.get(guildId);
        if (previous == null || previous.channelId() != channelId) {
            states.put(guildId, new State(channelId, 0, 0));
            save();
        }
        return previous;
    }

    /** Records a correct count. */
    public void advance(long guildId, long count, long userId) {
        State state = states.get(guildId);
        if (state == null) return;
        states.put(guildId, new State(state.channelId(), count, userId));
        save();
    }

    /** Resets the run to 0 (wrong number, broken rule). The channel stays bound. */
    public void reset(long guildId) {
        State state = states.get(guildId);
        if (state == null) return;
        states.put(guildId, new State(state.channelId(), 0, 0));
        save();
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
                String[] parts = stripped.substring(eq + 1).split("\\|");
                if (parts.length != 3) continue;
                try {
                    long guildId = Long.parseLong(stripped.substring(0, eq).strip());
                    states.put(guildId, new State(
                            Long.parseLong(parts[0].strip()),
                            Long.parseLong(parts[1].strip()),
                            Long.parseLong(parts[2].strip())));
                } catch (NumberFormatException ignored) {
                    // skip malformed line
                }
            }
        } catch (IOException e) {
            System.err.println("[CountingStore] Could not read " + file + ": " + e.getMessage());
        }
    }

    private synchronized void save() {
        try {
            Path parent = file.getParent();
            if (parent != null) {
                Files.createDirectories(parent);
            }
            Path tmp = file.resolveSibling(file.getFileName() + ".tmp");
            Map<Long, State> snapshot = new HashMap<>(states);
            StringBuilder body = new StringBuilder(snapshot.size() * 64);
            for (Map.Entry<Long, State> entry : snapshot.entrySet()) {
                State s = entry.getValue();
                body.append(entry.getKey()).append('=')
                        .append(s.channelId()).append('|')
                        .append(s.count()).append('|')
                        .append(s.lastUserId()).append('\n');
            }
            Files.writeString(tmp, body.toString(), StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
            Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (IOException e) {
            System.err.println("[CountingStore] Could not persist " + file + ": " + e.getMessage());
        }
    }
}
