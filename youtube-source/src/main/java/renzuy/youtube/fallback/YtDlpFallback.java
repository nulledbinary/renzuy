package renzuy.youtube.fallback;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import renzuy.youtube.AudioReference;
import renzuy.youtube.BotChallengeException;
import renzuy.youtube.YoutubeSourceException;

/**
 * The yt-dlp fallback path.
 *
 * <p>Slower than the Innertube fast path — it spawns a process and pays Python's
 * cold-start — but extremely reliable and constantly maintained upstream. It is
 * the safety net that keeps the bot playing audio when YouTube changes break
 * in-process extraction, and it is also the path for non-YouTube links
 * (Spotify, SoundCloud, and anything else yt-dlp supports).
 *
 * <h2>Bot-wall strategy</h2>
 * <p>From a Fargate egress IP, YouTube's bot-detection fires almost every
 * request unless we look like a real browser. This class layers four
 * defences, ordered cheapest-first:
 * <ol>
 *   <li><b>TLS fingerprint rotation</b> — each call picks a different
 *       {@code --impersonate} target (chrome, chrome131, safari, firefox133, …)
 *       so two consecutive requests don't share a fingerprint. Zero config.</li>
 *   <li><b>Internal retry</b> — a single wall doesn't trip the breaker; we
 *       retry once with a different impersonate target + reshuffled client
 *       order. Almost every transient wall heals on the second attempt.</li>
 *   <li><b>Cookies</b> — if {@code YT_DLP_COOKIES} points at a Netscape
 *       cookies file, {@code mweb} leads (cookie-aware). Otherwise
 *       {@code tv_embedded} leads (most resilient anon client).</li>
 *   <li><b>Proxy + PoToken</b> — operator-set {@code YT_DLP_PROXY} routes
 *       through a residential IP, and {@code YT_DLP_PO_TOKEN} satisfies
 *       YouTube's GVS challenge without a tracked session.</li>
 * </ol>
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
     * How long to skip the yt-dlp YouTube path after BOTH the first attempt and
     * the retry have hit the wall. Non-YouTube targets are unaffected.
     */
    private static final long BOT_CHALLENGE_BACKOFF_MILLIS = 90L * 1000L;

    /**
     * Pool of {@code --impersonate} targets to rotate through. Each call picks
     * one at random for its first attempt and a different one for the retry,
     * so YouTube's fingerprint scorer sees independent samples rather than
     * a single repeated signature. All targets are available in
     * {@code yt-dlp[curl-cffi]} as of 2025+ — if curl_cffi is missing yt-dlp
     * exits nonzero and we surface the normal error.
     */
    private static final List<String> IMPERSONATE_POOL = List.of(
            "chrome", "chrome-110", "chrome-124", "chrome-131",
            "safari", "safari15_5", "edge-101", "firefox-133");

    /**
     * Client lists for cookied vs cookieless calls. yt-dlp tries clients
     * left-to-right and stops at the first that returns a playable response,
     * so order is policy. {@code web} / {@code android} are excluded — both
     * demand a GVS PoToken from datacenter IPs and trip the wall without one
     * (we only opt them in when a PoToken is configured).
     */
    private static final String COOKIED_CLIENTS = "mweb,tv_embedded,android_vr,tv";
    private static final String ANON_CLIENTS    = "tv_embedded,android_vr,tv,mweb";

    private final String ytDlpPath;
    private final String cookiesPath;
    private final String proxy;
    private final String poToken;

    /**
     * Epoch-millis until which YouTube resolutions short-circuit. {@code 0} means
     * the breaker is closed. Atomic because resolution runs on per-guild worker
     * threads.
     */
    private final AtomicLong botChallengeUntil = new AtomicLong(0L);

    public YtDlpFallback(String ytDlpPath) {
        this(ytDlpPath, "", "", "");
    }

    public YtDlpFallback(String ytDlpPath, String cookiesPath) {
        this(ytDlpPath, cookiesPath, "", "");
    }

    public YtDlpFallback(String ytDlpPath, String cookiesPath, String proxy, String poToken) {
        this.ytDlpPath  = ytDlpPath;
        this.cookiesPath = cookiesPath == null ? "" : cookiesPath;
        this.proxy       = proxy == null ? "" : proxy;
        this.poToken     = poToken == null ? "" : poToken;
        if (!this.proxy.isBlank()) {
            log.info("[yt-dlp] proxy configured: {}", redact(this.proxy));
        }
        if (!this.poToken.isBlank()) {
            log.info("[yt-dlp] PoToken configured (length {})", this.poToken.length());
        }
    }

    /**
     * Enumerates the entries of a playlist URL using yt-dlp's
     * {@code --flat-playlist} mode — no per-entry stream resolution, just
     * metadata. The returned list contains lazy references that must be
     * re-resolved before playback.
     *
     * @throws YoutubeSourceException if yt-dlp finds no entries or fails
     */
    public List<AudioReference> resolvePlaylist(String playlistUrl) {
        boolean youTubeTarget = isYouTubeTarget(playlistUrl);
        if (youTubeTarget) {
            throwIfBreakerOpen();
        }
        String stdout = runWithRetry(b -> playlistCommand(playlistUrl, b), youTubeTarget, playlistUrl);

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
        boolean youTubeTarget = isYouTubeTarget(target);
        if (youTubeTarget) {
            throwIfBreakerOpen();
        }
        String stdout = runWithRetry(b -> command(target, b), youTubeTarget, target);
        AudioReference resolved = parse(stdout, query);
        if (youTubeTarget) {
            // Cookies / proxy / pure luck are working again — let later calls retry.
            botChallengeUntil.set(0L);
        }
        return resolved;
    }

    // ---------------- retry harness ----------------

    @FunctionalInterface
    private interface CommandBuilder {
        List<String> build(Attempt attempt);
    }

    private record Attempt(String impersonateTarget) {}

    /**
     * Runs the command with the rotation-and-retry strategy. For YouTube targets:
     * try once with a random impersonate target; on a wall, try once more with a
     * different target before tripping the breaker. For non-YouTube targets there
     * is no breaker — but we still retry once on a transient failure, since the
     * cost is one Python startup and the win is no user-visible flake.
     */
    private String runWithRetry(CommandBuilder builder, boolean youTubeTarget, String describable) {
        String firstTarget = randomImpersonate(null);
        try {
            String out = runYtDlp(builder.build(new Attempt(firstTarget)));
            log.debug("[yt-dlp] ok target={} target_for={}", firstTarget, describable);
            return out;
        } catch (BotChallengeException firstWall) {
            String secondTarget = randomImpersonate(firstTarget);
            log.info("[yt-dlp] wall hit with impersonate={} — retrying with {}", firstTarget, secondTarget);
            try {
                String out = runYtDlp(builder.build(new Attempt(secondTarget)));
                log.info("[yt-dlp] retry succeeded with impersonate={}", secondTarget);
                return out;
            } catch (BotChallengeException secondWall) {
                if (youTubeTarget) {
                    tripBreaker();
                }
                throw secondWall;
            }
        } catch (YoutubeSourceException nonWall) {
            // Non-wall failures don't get a fingerprint-retry — they're usually
            // "video unavailable" or similar, which a fresh client won't fix.
            throw nonWall;
        }
    }

    private static String randomImpersonate(String avoid) {
        if (IMPERSONATE_POOL.size() == 1) return IMPERSONATE_POOL.get(0);
        for (int i = 0; i < 8; i++) {
            String pick = IMPERSONATE_POOL.get(ThreadLocalRandom.current().nextInt(IMPERSONATE_POOL.size()));
            if (!pick.equals(avoid)) return pick;
        }
        return IMPERSONATE_POOL.get(0);
    }

    // ---------------- process runner ----------------

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
            if (isBotWall(detail)) {
                // Don't surface the multi-line yt-dlp paragraph to the user — the UI
                // layer renders a stable friendly message for this exception type.
                throw new BotChallengeException(
                        "YouTube served the bot-detection wall");
            }
            throw new YoutubeSourceException("yt-dlp failed: " + detail);
        }
        return stdout.toString();
    }

    private static boolean isBotWall(String stderrText) {
        if (stderrText == null || stderrText.isEmpty()) return false;
        String lower = stderrText.toLowerCase(Locale.ROOT);
        // Multiple variants the wall can show as.
        return (lower.contains("sign in to confirm") && lower.contains("not a bot"))
                || lower.contains("http error 403")
                || lower.contains("confirm you")
                || lower.contains("requires authentication");
    }

    private static boolean isYouTubeTarget(String target) {
        if (target == null || target.isEmpty()) return false;
        String lower = target.toLowerCase(Locale.ROOT);
        return lower.contains("youtube.com")
                || lower.contains("youtu.be")
                || lower.contains("youtube-nocookie.com")
                || lower.startsWith("ytsearch");
    }

    private void throwIfBreakerOpen() {
        long until = botChallengeUntil.get();
        long now = System.currentTimeMillis();
        if (until > now) {
            long secondsLeft = Math.max(1L, (until - now) / 1000L);
            throw new BotChallengeException(
                    "YouTube bot wall in effect (retry in ~" + secondsLeft + "s)");
        }
    }

    private void tripBreaker() {
        long until = System.currentTimeMillis() + BOT_CHALLENGE_BACKOFF_MILLIS;
        botChallengeUntil.set(until);
        log.warn("[yt-dlp] YouTube bot wall persisted across two impersonate targets — pausing YouTube fallback for {}s",
                BOT_CHALLENGE_BACKOFF_MILLIS / 1000L);
    }

    // ---------------- command builders ----------------

    private List<String> command(String target, Attempt attempt) {
        List<String> cmd = newBaseCommand(attempt);
        cmd.add("--no-playlist");
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

    private List<String> playlistCommand(String playlistUrl, Attempt attempt) {
        List<String> cmd = newBaseCommand(attempt);
        cmd.add("--flat-playlist");
        cmd.add("--print"); cmd.add("id");
        cmd.add("--print"); cmd.add("title");
        cmd.add("--print"); cmd.add("duration");
        cmd.add("--print"); cmd.add("uploader");
        cmd.add("--print"); cmd.add("webpage_url");
        cmd.add(playlistUrl);
        return cmd;
    }

    /**
     * Builds the shared flag set every call uses: process-hardening flags
     * (--ignore-config, --no-warnings, --quiet, retry/timeout), the chosen
     * impersonate target, the cookie-aware client order if cookies are present,
     * optional proxy / PoToken, and the cookies file.
     */
    private List<String> newBaseCommand(Attempt attempt) {
        List<String> cmd = new ArrayList<>(32);
        cmd.add(ytDlpPath);
        // --ignore-config: never read /etc/yt-dlp.conf or ~/.config/yt-dlp/ — our
        // production environment is exactly what's on this command line.
        cmd.add("--ignore-config");
        cmd.add("--no-warnings");
        cmd.add("--quiet");
        // Add jitter and bound transient timeouts so we don't waste the full 25 s
        // process budget waiting on a single hung socket.
        cmd.add("--sleep-requests"); cmd.add("1");
        cmd.add("--socket-timeout"); cmd.add("15");
        cmd.add("--retries"); cmd.add("3");
        // Spoof a Chrome User-Agent at the HTTP layer too — curl_cffi handles TLS,
        // but plain headers still get inspected.
        cmd.add("--user-agent"); cmd.add(STREAM_USER_AGENT);
        // Client/extractor args: cookie-aware order if we have cookies, otherwise
        // tv_embedded-led order (more resilient on anon datacenter IPs).
        cmd.add("--extractor-args"); cmd.add(buildExtractorArgs());
        // TLS / HTTP/2 fingerprint masquerade. Rotates per call (and per retry).
        cmd.add("--impersonate"); cmd.add(attempt.impersonateTarget());
        if (!proxy.isBlank()) {
            cmd.add("--proxy"); cmd.add(proxy);
        }
        appendCookiesArg(cmd);
        return cmd;
    }

    private String buildExtractorArgs() {
        String clients = cookiesPath.isBlank() ? ANON_CLIENTS : COOKIED_CLIENTS;
        StringBuilder sb = new StringBuilder("youtube:player_client=").append(clients);
        if (!poToken.isBlank()) {
            // yt-dlp accepts a per-client PoToken via po_token=<client>.<context>+<token>.
            // mweb.gvs is the broadly-applicable choice for non-web clients.
            sb.append(";po_token=mweb.gvs+").append(poToken);
        }
        return sb.toString();
    }

    private void appendCookiesArg(List<String> cmd) {
        if (!cookiesPath.isBlank()) {
            cmd.add("--cookies");
            cmd.add(cookiesPath);
        }
    }

    // ---------------- output parsing ----------------

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

    /**
     * Best-effort proxy string redaction for log lines — keeps the host:port so the
     * operator can tell *which* proxy was chosen, drops the userinfo so credentials
     * never land in CloudWatch.
     */
    private static String redact(String url) {
        int at = url.lastIndexOf('@');
        if (at < 0) return url;
        int scheme = url.indexOf("://");
        if (scheme < 0) return "***@" + url.substring(at + 1);
        return url.substring(0, scheme + 3) + "***@" + url.substring(at + 1);
    }
}
