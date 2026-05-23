package renzuy.audio;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Resolves the {@code ffmpeg} / {@code yt-dlp} binaries.
 *
 * <p>Order of resolution:
 * <ol>
 *   <li>Walk up to 5 parents looking for a {@code tools/} dir (local dev convenience —
 *       Windows {@code .exe}s live there, gitignored).</li>
 *   <li>Walk {@code PATH} for the executable (production / Docker case — apt-installed
 *       {@code ffmpeg} and downloaded {@code yt-dlp} sit on PATH inside the container).</li>
 *   <li>Fall back to the bare name and let {@link ProcessBuilder} surface a clear error.</li>
 * </ol>
 */
public final class Binaries {

    public static final String YT_DLP = resolve("yt-dlp");
    public static final String FFMPEG = resolve("ffmpeg");

    private Binaries() {}

    private static String resolve(String name) {
        String exe = isWindows() ? name + ".exe" : name;

        Path fromTools = searchToolsDir(exe);
        if (fromTools != null) {
            System.out.println("[Binaries] " + name + " -> " + fromTools);
            return fromTools.toString();
        }

        Path fromPath = searchPath(exe);
        if (fromPath != null) {
            System.out.println("[Binaries] " + name + " -> " + fromPath + " (PATH)");
            return fromPath.toString();
        }

        System.err.println("[Binaries] " + name + " not found in ./tools or on PATH — "
                + "falling back to bare command, expect ProcessBuilder to fail.");
        return name;
    }

    private static Path searchToolsDir(String exe) {
        Path candidate = Path.of("").toAbsolutePath();
        for (int i = 0; i < 5; i++) {
            Path probe = candidate.resolve("tools").resolve(exe);
            if (Files.isExecutable(probe)) {
                return probe;
            }
            Path parent = candidate.getParent();
            if (parent == null) {
                break;
            }
            candidate = parent;
        }
        return null;
    }

    private static Path searchPath(String exe) {
        String path = System.getenv("PATH");
        if (path == null || path.isBlank()) {
            return null;
        }
        for (String entry : path.split(File.pathSeparator)) {
            if (entry.isBlank()) continue;
            try {
                Path probe = Path.of(entry, exe);
                if (Files.isExecutable(probe)) {
                    return probe;
                }
            } catch (Exception ignored) {
                // some PATH entries are malformed on Windows; just skip
            }
        }
        return null;
    }

    private static boolean isWindows() {
        return System.getProperty("os.name", "").toLowerCase().contains("win");
    }
}
