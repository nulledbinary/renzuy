package renzuy.audio;

import java.nio.file.Files;
import java.nio.file.Path;

public final class Binaries {

    public static final String YT_DLP = resolve("yt-dlp");
    public static final String FFMPEG = resolve("ffmpeg");

    private Binaries() {}

    private static String resolve(String name) {
        String exe = isWindows() ? name + ".exe" : name;
        Path candidate = Path.of("").toAbsolutePath();
        for (int i = 0; i < 5; i++) {
            Path probe = candidate.resolve("tools").resolve(exe);
            if (Files.isExecutable(probe)) {
                return probe.toString();
            }
            Path parent = candidate.getParent();
            if (parent == null) {
                break;
            }
            candidate = parent;
        }
        return name;
    }

    private static boolean isWindows() {
        return System.getProperty("os.name", "").toLowerCase().contains("win");
    }
}
