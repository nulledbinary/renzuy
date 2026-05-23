package renzuy.youtube;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import renzuy.youtube.cache.StreamCache;
import renzuy.youtube.fallback.YtDlpFallback;
import renzuy.youtube.innertube.InnertubeResolver;
import renzuy.youtube.query.QueryClassifier;

/**
 * Standalone, low-latency YouTube audio source.
 *
 * <h2>Resolution path</h2>
 * <ol>
 *   <li><b>Cache.</b> A repeat play returns instantly — no network, no subprocess.</li>
 *   <li><b>Innertube.</b> In-process HTTP/2 to YouTube's internal API. Fast path.</li>
 *   <li><b>yt-dlp fallback.</b> Subprocess; slower but maximally compatible. Used when
 *       Innertube cannot resolve a YouTube link, and as the path for non-YouTube links
 *       and any playlist.</li>
 * </ol>
 *
 * <p>Thread-safe; construct one instance and share it across all guilds.
 */
public final class YoutubeSource {

    private static final Logger log = LoggerFactory.getLogger(YoutubeSource.class);

    private final YoutubeSourceOptions options;
    private final InnertubeResolver innertube;
    private final YtDlpFallback fallback;
    private final StreamCache cache;

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

    /** Opens the HTTP/2 connection to YouTube ahead of the first {@code /play}. */
    public CompletableFuture<Void> prewarm() {
        return innertube.prewarm();
    }

    /**
     * Resolves a {@code /play} argument into one or more playable tracks. For a
     * playlist URL, only the first entry is fully resolved — the rest stay lazy and
     * must be materialized via {@link #resolveLazy(AudioReference)} before playback.
     *
     * <p>Blocking: call from a worker thread, not an event-loop thread.
     */
    public ResolveResult resolve(String query) {
        long startNanos = System.nanoTime();
        QueryClassifier.Result classified = QueryClassifier.classify(query);
        ResolveResult result = switch (classified.kind()) {
            case VIDEO -> ResolveResult.single(resolveVideo(classified.value()));
            case SEARCH -> ResolveResult.single(resolveSearch(classified.value()));
            case PLAYLIST -> resolvePlaylist(query);
            case FOREIGN_URL -> resolveForeign(query);
        };
        log.info("Resolved [{}] in {} ms ({} track{})",
                classified.kind(),
                (System.nanoTime() - startNanos) / 1_000_000L,
                result.tracks().size(),
                result.tracks().size() == 1 ? "" : "s");
        return result;
    }

    /**
     * Materializes a lazy reference into a playable one. Idempotent for non-lazy input.
     */
    public AudioReference resolveLazy(AudioReference lazy) {
        if (!lazy.isLazy()) return lazy;
        if (!lazy.videoId().isBlank() && lazy.videoId().length() == 11) {
            return resolveVideo(lazy.videoId());
        }
        return resolveViaFallback(lazy.webpageUrl(), "LAZY");
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
            log.warn("Innertube could not resolve {} ({}); falling back to yt-dlp", videoId, e.getMessage());
            return resolveViaFallback("https://www.youtube.com/watch?v=" + videoId, "VIDEO");
        }
    }

    private AudioReference resolveSearch(String term) {
        try {
            return resolveVideo(innertube.searchFirstVideoId(term));
        } catch (YoutubeSourceException e) {
            log.warn("Innertube search failed for '{}' ({}); falling back to yt-dlp", term, e.getMessage());
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

    private ResolveResult resolvePlaylist(String playlistUrl) {
        if (!options.fallbackEnabled()) {
            throw new YoutubeSourceException(
                    "Playlist support requires the yt-dlp fallback, which is disabled");
        }
        List<AudioReference> entries = fallback.resolvePlaylist(playlistUrl);
        // Find the first entry we can actually resolve. Anything before it is unplayable; drop them.
        int firstPlayable = -1;
        AudioReference resolved = null;
        YoutubeSourceException lastError = null;
        for (int i = 0; i < entries.size(); i++) {
            try {
                resolved = resolveLazy(entries.get(i));
                firstPlayable = i;
                break;
            } catch (YoutubeSourceException e) {
                lastError = e;
            }
        }
        if (firstPlayable < 0) {
            throw new YoutubeSourceException(
                    "No playable entries in playlist (" + entries.size() + " tried)", lastError);
        }
        List<AudioReference> out = new ArrayList<>(entries.size() - firstPlayable);
        out.add(resolved);
        for (int i = firstPlayable + 1; i < entries.size(); i++) {
            out.add(entries.get(i));
        }
        return new ResolveResult(out, playlistTitleFromUrl(playlistUrl));
    }

    private ResolveResult resolveForeign(String url) {
        try {
            return ResolveResult.single(resolveViaFallback(url, "FOREIGN_URL"));
        } catch (YoutubeSourceException single) {
            try {
                return resolvePlaylist(url);
            } catch (YoutubeSourceException ignored) {
                throw single;
            }
        }
    }

    private static String playlistTitleFromUrl(String url) {
        int marker = url.indexOf("list=");
        if (marker < 0) return "Playlist";
        int start = marker + "list=".length();
        int end = url.indexOf('&', start);
        return "Playlist " + (end < 0 ? url.substring(start) : url.substring(start, end));
    }
}
