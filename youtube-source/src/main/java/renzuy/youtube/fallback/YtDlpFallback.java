package renzuy.youtube.fallback;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.TimeUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import renzuy.youtube.AudioReference;
import renzuy.youtube.YoutubeSourceException;

/**
 * The yt-dlp fallback path.
 *
 * <p>Slower than the Innertube fast path — it spawns a process and pays Python's
 * cold-start — but it is extremely reliable and constantly maintained upstream. It
 * is the safety net that keeps the bot playing audio when YouTube changes break
 * in-process extraction, and it is also the path for non-YouTube links (Spotify,
 * SoundCloud, and anything else yt-dlp supports).
 */
public final class YtDlpFallback {

    private static final Logger log = LoggerFactory.getLogger(YtDlpFallback.class);

    /** A plausible desktop User-Agent for ffmpeg to send when fetching the URL. */
    private static final String STREAM_USER_AGENT =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 "
                    + "(KHTML, like Gecko) Chrome/130.0.0.0 Safari/537.36";

    private static final long PROCESS_TIMEOUT_SECONDS = 25L;
    private static final long FALLBACK_URL_TTL_MILLIS = 30L * 60L * 1000L;

    /**
     * Tells yt-dlp which Innertube clients to ask. The {@code web} and {@code android}
     * clients have started demanding a GVS PoToken (and the bot-detection wall) when
     * the request comes from a datacenter IP (AWS, GCP, ...). The clients here have
     * historically replied to anonymous requests from cloud IPs without demanding
     * cookies or a PoToken; {@code tv} (the Smart TV YouTube app) has been the most
     * resilient through 2025–2026, with {@code tv_embedded} and {@code android_vr}
     * (Oculus) as backups. Order matters — yt-dlp tries them left-to-right.
     *
     * <p>If even these trip the wall (it happens for some videos / from some egress
     * IPs), set {@code YT_DLP_COOKIES} so yt-dlp can present an authenticated session.
     */
    private static final String YOUTUBE_EXTRACTOR_ARGS =
            "youtube:player_client=tv,tv_embedded,android_vr,mweb";

    private final String ytDlpPath;
    private final String cookiesPath;

    public YtDlpFallback(String ytDlpPath) {
        this(ytDlpPath, "");
    }

    public YtDlpFallback(String ytDlpPath, String cookiesPath) {
        this.ytDlpPath = ytDlpPath;
        this.cookiesPath = cookiesPath == null ? "" : cookiesPath;
    }

    /**
     * Enumerates the entries of a playlist URL (YouTube, YouTube Music, SoundCloud
     * set, ...) using yt-dlp's {@code --flat-playlist} mode — no per-entry stream
     * resolution, just metadata. The returned list contains
     * {@linkplain AudioReference#isLazy() lazy} references that must be re-resolved
     * before playback.
     *
     * @throws YoutubeSourceException if yt-dlp finds no entries or fails
     */
    public List<AudioReference> resolvePlaylist(String playlistUrl) {
        String stdout = runYtDlp(playlistCommand(playlistUrl));

        // --flat-playlist emits one block per entry; the --print flags below produce
        // exactly 5 lines per entry, in order: id, title, duration, uploader, webpage_url.
        String[] lines = stdout.split("\\r?\\n");
        List<AudioReference> entries = new ArrayList<>();
        for (int i = 0; i + 4 < lines.length; i += 5) {
            String id          = lines[i].strip();
            String title       = lines[i + 1].strip();
            String duration    = lines[i + 2].strip();
            String uploader    = lines[i + 3].strip();
            String webpageUrl  = lines[i + 4].strip();

            if (title.isEmpty() || title.equals("NA")) {
                title = "Untitled track";
            }
            if (uploader.isEmpty() || uploader.equals("NA")) {
                uploader = "Unknown";
            }
            long durationMillis = parseDurationMillis(duration);
            String pageUrl = webpageUrl.isEmpty() || webpageUrl.equals("NA")
                    ? (id.isEmpty() ? playlistUrl : "https://www.youtube.com/watch?v=" + id)
                    : webpageUrl;
            // For non-YouTube entries the id is the source-native id; isLazy()/resolveLazy()
            // re-resolves by webpageUrl in that case.
            String videoId = id.equals("NA") ? "" : id;

            entries.add(AudioReference.lazy(title, uploader, durationMillis, videoId, pageUrl));
        }
        if (entries.isEmpty()) {
            throw new YoutubeSourceException("Playlist yielded no entries: " + playlistUrl);
        }
        log.info("[yt-dlp] enumerated {} entries from {}", entries.size(), playlistUrl);
        return entries;
    }

    /**
     * Resolves a URL or — for a bare term — a {@code ytsearch1:} search.
     *
     * @throws YoutubeSourceException if yt-dlp is missing, times out, or finds nothing
     */
    public AudioReference resolve(String query) {
        String target = looksLikeUrl(query) ? query : "ytsearch1:" + query;
        return parse(runYtDlp(command(target)), query);
    }

    private String runYtDlp(List<String> command) {
        Process process;
        try {
            process = new ProcessBuilder(command).start();
        } catch (IOException e) {
            throw new YoutubeSourceException(
                    "yt-dlp is not available at '" + ytDlpPath + "'", e);
        }

        // Drain both streams concurrently so a full stderr buffer can never
        // deadlock the process while we wait on stdout.
        StringBuilder stdout = new StringBuilder();
        StringBuilder stderr = new StringBuilder();
        Thread outPump = drain(process.getInputStream(), stdout, "yt-dlp-stdout");
        Thread errPump = drain(process.getErrorStream(), stderr, "yt-dlp-stderr");

        boolean finished;
        try {
            finished = process.waitFor(PROCESS_TIMEOUT_SECONDS, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            process.destroyForcibly();
            throw new YoutubeSourceException("yt-dlp resolution was interrupted", e);
        }
        if (!finished) {
            process.destroyForcibly();
            throw new YoutubeSourceException(
                    "yt-dlp timed out after " + PROCESS_TIMEOUT_SECONDS + "s");
        }
        joinQuietly(outPump);
        joinQuietly(errPump);

        if (process.exitValue() != 0) {
            String detail = stderr.isEmpty() ? "exit " + process.exitValue() : stderr.toString().strip();
            throw new YoutubeSourceException("yt-dlp failed: " + detail);
        }
        return stdout.toString();
    }

    private List<String> command(String target) {
        // One --print per field; yt-dlp emits them as lines in this exact order.
        List<String> cmd = new ArrayList<>(20);
        cmd.add(ytDlpPath);
        cmd.add("--no-playlist");
        cmd.add("--no-warnings");
        cmd.add("--quiet");
        cmd.add("--extractor-args"); cmd.add(YOUTUBE_EXTRACTOR_ARGS);
        appendCookiesArg(cmd);
        cmd.add("--print"); cmd.add("title");
        cmd.add("--print"); cmd.add("url");
        cmd.add("--print"); cmd.add("duration");
        cmd.add("--print"); cmd.add("uploader");
        cmd.add("--print"); cmd.add("webpage_url");
        cmd.add("--print"); cmd.add("id");
        cmd.add("--format"); cmd.add("bestaudio/best");
        cmd.add(target);
        return cmd;
    }

    private List<String> playlistCommand(String playlistUrl) {
        // --flat-playlist makes yt-dlp list entries without resolving each one — fast
        // even for huge playlists. Five --print fields per entry, in fixed order.
        List<String> cmd = new ArrayList<>(16);
        cmd.add(ytDlpPath);
        cmd.add("--flat-playlist");
        cmd.add("--no-warnings");
        cmd.add("--quiet");
        cmd.add("--extractor-args"); cmd.add(YOUTUBE_EXTRACTOR_ARGS);
        appendCookiesArg(cmd);
        cmd.add("--print"); cmd.add("id");
        cmd.add("--print"); cmd.add("title");
        cmd.add("--print"); cmd.add("duration");
        cmd.add("--print"); cmd.add("uploader");
        cmd.add("--print"); cmd.add("webpage_url");
        cmd.add(playlistUrl);
        return cmd;
    }

    private void appendCookiesArg(List<String> cmd) {
        if (!cookiesPath.isBlank()) {
            cmd.add("--cookies");
            cmd.add(cookiesPath);
        }
    }

    private AudioReference parse(String stdout, String originalQuery) {
        String[] line = stdout.strip().split("\\r?\\n");
        if (line.length < 2 || line[0].isBlank() || line[1].isBlank()) {
            throw new YoutubeSourceException("yt-dlp returned no playable stream");
        }
        String title = line[0];
        String streamUrl = line[1];
        long durationMillis = line.length > 2 ? parseDurationMillis(line[2]) : 0L;
        String author = field(line, 3, "Unknown");
        String webpageUrl = field(line, 4, originalQuery);
        String id = field(line, 5, "");

        log.info("[yt-dlp] resolved '{}'", title);
        return new AudioReference(
                title, author, durationMillis, id, webpageUrl,
                streamUrl, "unknown", false, false, STREAM_USER_AGENT,
                AudioReference.Origin.YT_DLP, "yt-dlp",
                System.currentTimeMillis() + FALLBACK_URL_TTL_MILLIS);
    }

    private static String field(String[] lines, int index, String fallback) {
        if (index >= lines.length) {
            return fallback;
        }
        String value = lines[index].strip();
        return value.isEmpty() || value.equals("NA") ? fallback : value;
    }

    private static long parseDurationMillis(String raw) {
        try {
            // yt-dlp prints duration as a float number of seconds (or "NA").
            return (long) (Double.parseDouble(raw.strip()) * 1000.0);
        } catch (RuntimeException e) {
            return 0L;
        }
    }

    private static Thread drain(InputStream stream, StringBuilder sink, String name) {
        Thread thread = new Thread(() -> {
            try (stream) {
                sink.append(new String(stream.readAllBytes(), StandardCharsets.UTF_8));
            } catch (IOException ignored) {
                // A closed/forcibly-destroyed process is expected; nothing to add.
            }
        }, name);
        thread.setDaemon(true);
        thread.start();
        return thread;
    }

    private static void joinQuietly(Thread thread) {
        try {
            thread.join(1000L);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private static boolean looksLikeUrl(String s) {
        String lower = s.strip().toLowerCase(Locale.ROOT);
        return lower.startsWith("http://") || lower.startsWith("https://");
    }
}
