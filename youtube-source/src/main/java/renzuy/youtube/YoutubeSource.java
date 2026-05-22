package renzuy.youtube;

import java.util.concurrent.CompletableFuture;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import renzuy.youtube.cache.StreamCache;
import renzuy.youtube.fallback.YtDlpFallback;
import renzuy.youtube.innertube.InnertubeResolver;
import renzuy.youtube.query.QueryClassifier;

/**
 * Standalone, low-latency YouTube audio source for the renzuy bot.
 *
 * <p>This class is the only entry point a consumer needs. Give it a {@code /play}
 * argument; get back a directly-playable {@link AudioReference}.
 *
 * <h2>Hybrid resolution — fastest path first</h2>
 * <ol>
 *   <li><b>Cache.</b> A repeat play of the same video returns instantly — zero
 *       network, zero subprocess.</li>
 *   <li><b>Innertube.</b> In-process calls to YouTube's internal API over a warm,
 *       pooled HTTP/2 connection. No subprocess and no Python cold-start, which is
 *       where almost all of the old latency went. This is the fast path.</li>
 *   <li><b>yt-dlp fallback.</b> Used only when Innertube cannot resolve a YouTube
 *       link, and as the path for non-YouTube links. Slower, but maximally reliable
 *       — it guarantees the bot keeps playing even when YouTube changes break
 *       in-process extraction.</li>
 * </ol>
 *
 * <p>Latency techniques layered on top: a connection prewarmed at startup
 * ({@link #prewarm()}), a single reused HTTP/2 client, and a TTL cache of resolved
 * streams. A typical warm cache-miss resolves in well under half a second; a cache
 * hit is immediate.
 *
 * <p>Thread-safe: construct one instance and share it across all guilds.
 */
public final class YoutubeSource {

    private static final Logger log = LoggerFactory.getLogger(YoutubeSource.class);

    private final YoutubeSourceOptions options;
    private final InnertubeResolver innertube;
    private final YtDlpFallback fallback;
    private final StreamCache cache;

    /** Creates a source with {@link YoutubeSourceOptions#defaults() default options}. */
    public YoutubeSource() {
        this(YoutubeSourceOptions.defaults());
    }

    public YoutubeSource(YoutubeSourceOptions options) {
        this.options = options;
        this.innertube = new InnertubeResolver(options);
        this.fallback = new YtDlpFallback(options.ytDlpPath());
        this.cache = new StreamCache(options.cacheTtl().toMillis(), options.cacheMaxEntries());
        if (options.prewarmOnStart()) {
            prewarm();
        }
    }

    /**
     * Opens the HTTP/2 connection to YouTube ahead of the first {@code /play}, so the
     * first real resolution does not pay the TLS + HTTP/2 handshake. Non-blocking;
     * safe to ignore the returned future.
     */
    public CompletableFuture<Void> prewarm() {
        return innertube.prewarm();
    }

    /**
     * Resolves a {@code /play} argument — a YouTube URL, a non-YouTube URL, or a
     * free-text search term — into a single, directly-playable {@link AudioReference}.
     *
     * <p>Blocking: call this from a worker thread, not from an event-loop thread.
     *
     * @throws YoutubeSourceException if nothing playable could be produced
     */
    public AudioReference resolve(String query) {
        long startNanos = System.nanoTime();
        QueryClassifier.Result classified = QueryClassifier.classify(query);

        AudioReference reference = switch (classified.kind()) {
            case VIDEO -> resolveVideo(classified.value());
            case SEARCH -> resolveSearch(classified.value());
            case PLAYLIST, FOREIGN_URL -> resolveViaFallback(query, classified.kind().name());
        };

        log.info("Resolved [{}] in {} ms via {}",
                classified.kind(),
                (System.nanoTime() - startNanos) / 1_000_000L,
                reference.origin());
        return reference;
    }

    // ------------------------------------------------------------------------

    private AudioReference resolveVideo(String videoId) {
        AudioReference cached = cache.get(videoId);
        if (cached != null) {
            log.debug("Cache hit for video {}", videoId);
            return cached;
        }
        try {
            AudioReference reference = innertube.resolveVideo(videoId);
            cache.put(reference);
            return reference;
        } catch (YoutubeSourceException e) {
            log.warn("Innertube could not resolve {} ({}); falling back to yt-dlp",
                    videoId, e.getMessage());
            return resolveViaFallback("https://www.youtube.com/watch?v=" + videoId, "VIDEO");
        }
    }

    private AudioReference resolveSearch(String term) {
        try {
            String videoId = innertube.searchFirstVideoId(term);
            return resolveVideo(videoId);
        } catch (YoutubeSourceException e) {
            log.warn("Innertube search failed for '{}' ({}); falling back to yt-dlp",
                    term, e.getMessage());
            return resolveViaFallback(term, "SEARCH");
        }
    }

    private AudioReference resolveViaFallback(String query, String what) {
        if (!options.fallbackEnabled()) {
            throw new YoutubeSourceException(
                    "Could not resolve " + what + " and the yt-dlp fallback is disabled");
        }
        AudioReference reference = fallback.resolve(query);
        cache.put(reference);
        return reference;
    }
}
