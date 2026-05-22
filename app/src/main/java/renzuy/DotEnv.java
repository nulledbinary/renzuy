package renzuy;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;

/**
 * Minimal {@code .env} file loader.
 *
 * <p>Java cannot mutate its own process environment, so this reads a {@code .env}
 * file into a map at startup. {@link #get(String)} prefers a real environment
 * variable when one is present and falls back to the {@code .env} value otherwise.
 *
 * <p>Supported syntax: {@code KEY=value}, {@code KEY = value}, an optional
 * {@code export} prefix, single- or double-quoted values, and {@code #} comment
 * or blank lines.
 */
public final class DotEnv {

    private static final Map<String, String> VALUES = load();

    private DotEnv() {}

    /** Real environment variable if set, otherwise the {@code .env} value, otherwise {@code null}. */
    public static String get(String key) {
        String fromEnv = System.getenv(key);
        if (fromEnv != null && !fromEnv.isBlank()) {
            return fromEnv;
        }
        return VALUES.get(key);
    }

    private static Map<String, String> load() {
        Map<String, String> values = new HashMap<>();
        Path file = locate();
        if (file == null) {
            return values;
        }
        try {
            for (String raw : Files.readAllLines(file)) {
                parseLine(raw, values);
            }
            System.out.println("[Config] Loaded " + file.getFileName() + " (" + values.size()
                    + (values.size() == 1 ? " entry)" : " entries)"));
        } catch (IOException e) {
            System.err.println("[Config] Could not read " + file + ": " + e.getMessage());
        }
        return values;
    }

    private static void parseLine(String raw, Map<String, String> values) {
        String line = raw.strip();
        if (line.isEmpty() || line.startsWith("#")) {
            return;
        }
        if (line.startsWith("export ")) {
            line = line.substring(7).strip();
        }
        int eq = line.indexOf('=');
        if (eq <= 0) {
            return;
        }
        String key = line.substring(0, eq).strip();
        String value = stripQuotes(line.substring(eq + 1).strip());
        if (!key.isEmpty()) {
            values.put(key, value);
        }
    }

    private static String stripQuotes(String value) {
        if (value.length() >= 2
                && ((value.startsWith("\"") && value.endsWith("\""))
                || (value.startsWith("'") && value.endsWith("'")))) {
            return value.substring(1, value.length() - 1);
        }
        return value;
    }

    private static Path locate() {
        Path dir = Path.of("").toAbsolutePath();
        for (int i = 0; i < 5 && dir != null; i++) {
            Path candidate = dir.resolve(".env");
            if (Files.isRegularFile(candidate)) {
                return candidate;
            }
            dir = dir.getParent();
        }
        return null;
    }
}
