package renzuy.moderation;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.time.Duration;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Per-guild "hatewarn" policy: after N warnings the offender gets auto-punished.
 *
 * <p>Persisted to {@code $RENZUY_CONFIG_DIR/hatewarn.properties} so the policy
 * survives container restarts; entries are written one per line as
 * {@code guildId=count|type|seconds}. {@code type} is {@code mute} or {@code ban}.
 */
public final class HateWarnConfig {

    public enum PunishmentType { TEMPMUTE, TEMPBAN }

    public record Policy(int threshold, PunishmentType type, Duration duration) {
        public Policy {
            if (threshold < 1) threshold = 1;
            if (duration == null) duration = Duration.ofMinutes(10);
        }
    }

    private final Path file;
    private final Map<Long, Policy> byGuild = new ConcurrentHashMap<>();

    public HateWarnConfig(Path file) {
        this.file = file;
        load();
    }

    public static HateWarnConfig defaultLocation() {
        String dir = System.getenv("RENZUY_CONFIG_DIR");
        Path base = (dir == null || dir.isBlank()) ? Path.of("config") : Path.of(dir);
        return new HateWarnConfig(base.resolve("hatewarn.properties"));
    }

    public Policy get(long guildId) {
        return byGuild.getOrDefault(guildId,
                new Policy(3, PunishmentType.TEMPMUTE, Duration.ofMinutes(10)));
    }

    public boolean isConfigured(long guildId) {
        return byGuild.containsKey(guildId);
    }

    public void set(long guildId, Policy policy) {
        byGuild.put(guildId, policy);
        save();
    }

    private void load() {
        if (!Files.isRegularFile(file)) return;
        try {
            for (String line : Files.readAllLines(file, StandardCharsets.UTF_8)) {
                String s = line.strip();
                if (s.isEmpty() || s.startsWith("#")) continue;
                int eq = s.indexOf('=');
                if (eq <= 0) continue;
                long guildId;
                try {
                    guildId = Long.parseLong(s.substring(0, eq).strip());
                } catch (NumberFormatException ignored) { continue; }
                String[] parts = s.substring(eq + 1).split("\\|");
                if (parts.length != 3) continue;
                try {
                    int threshold = Integer.parseInt(parts[0].strip());
                    PunishmentType type = PunishmentType.valueOf(parts[1].strip().toUpperCase());
                    long sec = Long.parseLong(parts[2].strip());
                    byGuild.put(guildId, new Policy(threshold, type, Duration.ofSeconds(sec)));
                } catch (RuntimeException ignored) {}
            }
        } catch (IOException e) {
            System.err.println("[HateWarnConfig] read failed: " + e.getMessage());
        }
    }

    private synchronized void save() {
        try {
            Path parent = file.getParent();
            if (parent != null) Files.createDirectories(parent);
            Path tmp = file.resolveSibling(file.getFileName() + ".tmp");
            Map<Long, Policy> snapshot = new HashMap<>(byGuild);
            StringBuilder body = new StringBuilder(snapshot.size() * 32);
            for (Map.Entry<Long, Policy> e : snapshot.entrySet()) {
                Policy p = e.getValue();
                body.append(e.getKey()).append('=')
                        .append(p.threshold()).append('|')
                        .append(p.type().name().toLowerCase()).append('|')
                        .append(p.duration().getSeconds()).append('\n');
            }
            Files.writeString(tmp, body.toString(), StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
            Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (IOException e) {
            System.err.println("[HateWarnConfig] write failed: " + e.getMessage());
        }
    }
}
