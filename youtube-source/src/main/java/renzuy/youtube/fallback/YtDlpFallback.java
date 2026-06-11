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
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import renzuy.youtube.AudioReference;
import renzuy.youtube.BotChallengeException;
import renzuy.youtube.YoutubeSourceException;
import renzuy.youtube.Ipv6Block;

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
 *       {@code --impersonate} target from the set curl_cffi actually has
 *       built into this runtime (detected once at construction).</li>
 *   <li><b>Internal retry</b> — a single wall doesn't trip the breaker; we
 *       retry once with a different impersonate target. Almost every
 *       transient wall heals on the second attempt.</li>
 *   <li><b>Cookies</b> — if {@code YT_DLP_COOKIES} points at a Netscape
 *       cookies file, {@code mweb} leads. Otherwise {@code tv_embedded}
 *       leads (most resilient anon client).</li>
 *   <li><b>PoToken / proxy / IPv6</b> — operator-set {@code YT_DLP_PROXY} routes
 *       through a residential IP; {@code YT_DLP_PO_TOKEN} pins a static
 *       PoToken; {@code YT_DLP_POT_PROVIDER_URL} points the bgutil yt-dlp
 *       plugin at an ECS-sidecar token provider for per-call minting. IPv6
 *       rotation binds each call to a random address from an IPv6 block.</li>
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
     * Bare-{@code chrome} is an alias for the latest target curl_cffi has built,
     * so it cannot fail to resolve. Used as the always-safe fallback whenever
     * runtime detection comes back empty.
     */
    private static final List<String> SAFE_IMPERSONATE_FALLBACK = List.of("chrome");

    /**
     * Regex that matches a {@code --list-impersonate-targets} client column.
     * Tolerant of both yt-dlp's dash-prefixed format ({@code chrome-110},
     * {@code safari-15.5}) and curl_cffi's bare/underscored format
     * ({@code chrome131}, {@code safari15_5}, {@code chrome131_android}).
     */
    private static final Pattern TARGET_LINE = Pattern.compile(
            "^(chrome|safari|firefox|edge|chromium)[A-Za-z0-9._-]*$",
            Pattern.CASE_INSENSITIVE);

    /**
     * Client lists for cookied vs cookieless vs token-equipped calls. yt-dlp
     * tries clients left-to-right and stops at the first that returns a playable
     * response, so order is policy. {@code web} / {@code android} demand a GVS
     * PoToken; we lead with {@code web} only when a token is in play.
     */
    private static final String COOKIED_CLIENTS = "mweb,tv_embedded,android_vr,tv";
    private static final String ANON_CLIENTS    = "tv_embedded,android_vr,tv,mweb";
    private static final String TOKEN_CLIENTS   = "web,mweb,tv_embedded,android_vr";

    private final String ytDlpPath;
    private final String cookiesPath;
    private final String proxy;
    private final String poToken;
    private final String potProviderUrl;
    private final Ipv6Block ipv6Block;
    /**
     * Impersonate targets that this yt-dlp/curl_cffi actually has built — never
     * trust a hard-coded list, since target availability moves with each
     * curl_cffi release and asking for a missing one is a hard YoutubeDLError
     * (no fallback inside yt-dlp). Detected once at construction.
     */
    private final List<String> impersonatePool;

    /**
     * Epoch-millis until which YouTube resolutions short-circuit. {@code 0} means
     * the breaker is closed. Atomic because resolution runs on per-guild worker
     * threads.
     */
    private final AtomicLong botChallengeUntil = new AtomicLong(0L);

    public YtDlpFallback(String ytDlpPath) {
        this(ytDlpPath, "", "", "", "", "");
    }

    public YtDlpFallback(String ytDlpPath, String cookiesPath) {
        this(ytDlpPath, cookiesPath, "", "", "", "");
    }

    public YtDlpFallback(String ytDlpPath, String cookiesPath, String proxy, String poToken) {
        this(ytDlpPath, cookiesPath, proxy, poToken, "", "");
    }

    public YtDlpFallback(
            String ytDlpPath, String cookiesPath,
            String proxy, String poToken, String potProviderUrl) {
        this(ytDlpPath, cookiesPath, proxy, poToken, potProviderUrl, "");
    }

    public YtDlpFallback(
            String ytDlpPath, String cookiesPath,
            String proxy, String poToken, String potProviderUrl, String ipv6BlockCidr) {
        this.ytDlpPath  = ytDlpPath;
        this.cookiesPath    = cookiesPath == null ? "" : cookiesPath;
        this.proxy          = proxy == null ? "" : proxy;
        this.poToken        = poToken == null ? "" : poToken;
        this.potProviderUrl = potProviderUrl == null ? "" : potProviderUrl;
        this.ipv6Block      = ipv6BlockCidr == null || ipv6BlockCidr.isBlank() ? null : new Ipv6Block(ipv6BlockCidr);
        this.impersonatePool = detectImpersonateTargets(ytDlpPath);
        log.info("[yt-dlp] {} impersonate target(s) available: {}",
                impersonatePool.size(), impersonatePool);
        if (!this.proxy.isBlank()) {
            log.info("[yt-dlp] proxy configured: {}", redact(this.proxy));
        }
        if (!this.poToken.isBlank()) {
            log.info("[yt-dlp] PoToken (static) configured (length {})", this.poToken.length());
        }
        if (!this.potProviderUrl.isBlank()) {
            log.info("[yt-dlp] PoToken provider URL configured: {}", this.potProviderUrl);
        }
        if (this.ipv6Block != null) {
            log.info("[yt-dlp] IPv6 block rotation configured");
        }
    }

    /**
     * Asks yt-dlp which impersonate targets curl_cffi has built into this
     * runtime, so the rotation pool only contains names that resolve. Picking
     * a non-existent target is a hard {@code YoutubeDLError} from yt-dlp with
     * no internal fallback, so guessing here permanently kills the call.
     *
     * <p>On any failure (binary missing, exec error, empty list, ancient
     * yt-dlp without the flag) we fall back to bare {@code chrome}, which is
     * curl_cffi's alias for the latest target it has and therefore always
     * resolves.
     */
    private static List<String> detectImpersonateTargets(String ytDlpPath) {
        try {
            Process p = new ProcessBuilder(ytDlpPath, "--list-impersonate-targets")
                    .redirectErrorStream(true)
                    .start();
            if (!p.waitFor(10, TimeUnit.SECONDS)) {
                p.destroyForcibly();
                return SAFE_IMPERSONATE_FALLBACK;
            }
            String output = new String(p.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
            if (p.exitValue() != 0) {
                return SAFE_IMPERSONATE_FALLBACK;
            }
            List<String> detected = new ArrayList<>();
            for (String raw : output.split("\\r?\\n")) {
                String line = raw.strip();
                if (line.isEmpty()) continue;
                String first = line.split("\\s+")[0];
                if (TARGET_LINE.matcher(first).matches() && !detected.contains(first)) {
                    detected.add(first);
                }
            }
            return detected.isEmpty() ? SAFE_IMPERSONATE_FALLBACK : List.copyOf(detected);
        } catch (Exception e) {
            log.warn("[yt-dlp] could not detect impersonate targets ({}); falling back to {}",
                    e.getMessage(), SAFE_IMPERSONATE_FALLBACK);
            return SAFE_IMPERSONATE_FALLBACK;
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
            throw nonWall;
        }
    }

    private String randomImpersonate(String avoid) {
        if (impersonatePool.size() == 1) return impersonatePool.get(0);
        for (int i = 0; i < 8; i++) {
            String pick = impersonatePool.get(ThreadLocalRandom.current().nextInt(impersonatePool.size()));
            if (!pick.equals(avoid)) return pick;
        }
        return impersonatePool.get(0);
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
                log.warn("[yt-dlp] Bot wall hit with error detail: {}", detail);
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
        cmd.add("--format"); cmd.add("bestaudio/bestvideo+bestaudio/best/ba/b");
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

    private List<String> newBaseCommand(Attempt attempt) {
        List<String> cmd = new ArrayList<>(32);
        cmd.add(ytDlpPath);
        cmd.add("--ignore-config");
        cmd.add("--no-warnings");
        cmd.add("--quiet");
        cmd.add("--sleep-requests"); cmd.add("1");
        cmd.add("--socket-timeout"); cmd.add("15");
        cmd.add("--retries"); cmd.add("3");
        cmd.add("--user-agent"); cmd.add(STREAM_USER_AGENT);
        cmd.add("--extractor-args"); cmd.add(buildExtractorArgs());
        if (!potProviderUrl.isBlank()) {
            // bgutil-ytdlp-pot-provider ≥ 1.0 reads its endpoint from its own
            // extractor-args namespace (the pre-1.0 getpot_bgutil_baseurl key
            // under youtube: is silently ignored by current plugin builds —
            // observed as the POT sidecar receiving zero mint requests).
            cmd.add("--extractor-args");
            cmd.add("youtubepot-bgutilhttp:base_url=" + potProviderUrl);
        }
        cmd.add("--impersonate"); cmd.add(attempt.impersonateTarget());
        if (!proxy.isBlank()) {
            cmd.add("--proxy"); cmd.add(proxy);
        }
        if (ipv6Block != null) {
            cmd.add("--source-address"); cmd.add(ipv6Block.generateRandomString());
        }
        appendCookiesArg(cmd);
        return cmd;
    }

    private String buildExtractorArgs() {
        // When a PoToken (static OR provider-minted) is in play, web becomes
        // the strongest playable client — full quality, lower wall risk. Without
        // a token, leading with web trips the wall instantly; we use cookied or
        // anon client orders instead.
        boolean haveToken = !poToken.isBlank() || !potProviderUrl.isBlank();
        String clients;
        if (haveToken) {
            clients = TOKEN_CLIENTS;
        } else if (cookiesPath.isBlank()) {
            clients = ANON_CLIENTS;
        } else {
            clients = COOKIED_CLIENTS;
        }
        StringBuilder sb = new StringBuilder("youtube:player_client=").append(clients);
        if (!poToken.isBlank()) {
            // yt-dlp accepts a per-client PoToken via po_token=<client>.<context>+<token>.
            // mweb.gvs is the broadly-applicable choice for non-web clients.
            sb.append(";po_token=mweb.gvs+").append(poToken);
        }
        // The provider endpoint is passed in its own namespace
        // (youtubepot-bgutilhttp:base_url=…) by newBaseCommand — modern
        // bgutil plugin builds no longer read it from the youtube: namespace.
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

    private static String redact(String url) {
        int at = url.lastIndexOf('@');
        if (at < 0) return url;
        int scheme = url.indexOf("://");
        if (scheme < 0) return "***@" + url.substring(at + 1);
        return url.substring(0, scheme + 3) + "***@" + url.substring(at + 1);
    }
}
