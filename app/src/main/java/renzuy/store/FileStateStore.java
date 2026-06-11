package renzuy.store;

import java.io.IOException;
import java.net.URLDecoder;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentHashMap;

/**
 * File-backed {@link StateStore} for local development (no AWS credentials
 * required). State lives in memory and is rewritten atomically to
 * {@code $RENZUY_CONFIG_DIR/state.properties} on every mutation — the same
 * tmp-file + atomic-move pattern the old per-feature stores used.
 *
 * <p>Line format (one item per line, every token URL-encoded so separators
 * can never collide with content):
 * <pre>pk sk key=value&amp;key=value</pre>
 */
public final class FileStateStore implements StateStore {

    private final Path file;
    /** pk + '\u0001' + sk → attributes. */
    private final Map<String, Map<String, String>> items = new ConcurrentHashMap<>();

    public FileStateStore(Path file) {
        this.file = file;
        load();
    }

    public static FileStateStore defaultLocation() {
        String dir = System.getenv("RENZUY_CONFIG_DIR");
        Path base = (dir == null || dir.isBlank()) ? Path.of("config") : Path.of(dir);
        return new FileStateStore(base.resolve("state.properties"));
    }

    @Override
    public Optional<Map<String, String>> get(String pk, String sk) {
        Map<String, String> attrs = items.get(key(pk, sk));
        return attrs == null ? Optional.empty() : Optional.of(Map.copyOf(attrs));
    }

    @Override
    public Map<String, Map<String, String>> query(String pk) {
        String prefix = pk + '\u0001';
        Map<String, Map<String, String>> out = new LinkedHashMap<>();
        for (Map.Entry<String, Map<String, String>> e : items.entrySet()) {
            if (e.getKey().startsWith(prefix)) {
                out.put(e.getKey().substring(prefix.length()), Map.copyOf(e.getValue()));
            }
        }
        return out;
    }

    @Override
    public void put(String pk, String sk, Map<String, String> attributes) {
        items.put(key(pk, sk), Map.copyOf(attributes));
        save();
    }

    @Override
    public void delete(String pk, String sk) {
        if (items.remove(key(pk, sk)) != null) {
            save();
        }
    }

    private static String key(String pk, String sk) {
        return pk + '\u0001' + sk;
    }

    private void load() {
        if (!Files.isRegularFile(file)) {
            return;
        }
        try {
            for (String line : Files.readAllLines(file, StandardCharsets.UTF_8)) {
                String stripped = line.strip();
                if (stripped.isEmpty() || stripped.startsWith("#")) continue;
                String[] parts = stripped.split(" ", 3);
                if (parts.length < 2) continue;
                String pk = dec(parts[0]);
                String sk = dec(parts[1]);
                Map<String, String> attrs = new LinkedHashMap<>();
                if (parts.length == 3 && !parts[2].isEmpty()) {
                    for (String pair : parts[2].split("&")) {
                        int eq = pair.indexOf('=');
                        if (eq <= 0) continue;
                        attrs.put(dec(pair.substring(0, eq)), dec(pair.substring(eq + 1)));
                    }
                }
                items.put(key(pk, sk), attrs);
            }
        } catch (IOException e) {
            System.err.println("[FileStateStore] Could not read " + file + ": " + e.getMessage());
        }
    }

    private synchronized void save() {
        try {
            Path parent = file.getParent();
            if (parent != null) {
                Files.createDirectories(parent);
            }
            StringBuilder body = new StringBuilder(items.size() * 96);
            for (Map.Entry<String, Map<String, String>> e : new TreeMap<>(items).entrySet()) {
                int sep = e.getKey().indexOf('\u0001');
                body.append(enc(e.getKey().substring(0, sep))).append(' ')
                        .append(enc(e.getKey().substring(sep + 1))).append(' ');
                boolean first = true;
                for (Map.Entry<String, String> attr : e.getValue().entrySet()) {
                    if (!first) body.append('&');
                    body.append(enc(attr.getKey())).append('=').append(enc(attr.getValue()));
                    first = false;
                }
                body.append('\n');
            }
            Path tmp = file.resolveSibling(file.getFileName() + ".tmp");
            Files.writeString(tmp, body.toString(), StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
            Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (IOException e) {
            System.err.println("[FileStateStore] Could not persist " + file + ": " + e.getMessage());
        }
    }

    private static String enc(String s) {
        return URLEncoder.encode(s, StandardCharsets.UTF_8);
    }

    private static String dec(String s) {
        return URLDecoder.decode(s, StandardCharsets.UTF_8);
    }
}
