package renzuy.youtube;

import java.util.List;
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
     * Resolves a {@code /play} argument into one or more playable tracks.
     *
     * <p>For a single video, search term, or non-playlist URL, the result contains a
     * single fully-resolved {@link AudioReference}. For a playlist URL (YouTube,
     * YouTube Music, SoundCloud set, ...) it contains the first entry fully resolved
     * and the rest as {@linkplain AudioReference#isLazy() lazy} placeholders — call
     * {@link #resolveLazy(AudioReference)} on those before handing them to ffmpeg.
     *
     * <p>Blocking: call this from a worker thread, not from an event-loop thread.
     *
     * @throws YoutubeSourceException if nothing playable could be produced
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
     * Materializes a {@linkplain AudioReference#isLazy() lazy} reference into one
     * with a real stream URL. Use this just before playback for tracks that came in
     * a playlist enumeration.
     *
     * @throws YoutubeSourceException if the entry can no longer be resolved
     */
    public AudioReference resolveLazy(AudioReference lazy) {
        if (!lazy.isLazy()) {
            return lazy;
        }
        if (!lazy.videoId().isBlank() && lazy.videoId().length() == 11) {
            return resolveVideo(lazy.videoId());
        }
        // Non-YouTube entry (SoundCloud track in a set, ...) — re-run yt-dlp on its page URL.
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

    /**
     * For a playlist URL: enumerate entries (lazy stubs) and eagerly resolve only the
     * first one so playback can start without waiting on the rest.
     */
    private ResolveResult resolvePlaylist(String playlistUrl) {
        if (!options.fallbackEnabled()) {
            throw new YoutubeSourceException(
                    "Playlist support requires the yt-dlp fallback, which is disabled");
        }
        List<AudioReference> lazy = fallback.resolvePlaylist(playlistUrl);
        AudioReference firstResolved;
        try {
            firstResolved = resolveLazy(lazy.get(0));
        } catch (YoutubeSourceException e) {
            // First entry unplayable — try the next ones until one succeeds.
            firstResolved = null;
            int skipped = 0;
            for (int i = 1; i < lazy.size(); i++) {
                try {
                    firstResolved = resolveLazy(lazy.get(i));
                    skipped = i;
                    break;
                } catch (YoutubeSourceException ignored) {
                    // keep looking
                }
            }
            if (firstResolved == null) {
                throw new YoutubeSourceException(
                        "No playable entries in playlist (" + lazy.size() + " skipped)", e);
            }
            lazy = lazy.subList(skipped, lazy.size());
        }
        List<AudioReference> out = new java.util.ArrayList<>(lazy.size());
        out.add(firstResolved);
        for (int i = 1; i < lazy.size(); i++) {
            out.add(lazy.get(i));
        }
        return new ResolveResult(out, playlistTitleFromUrl(playlistUrl));
    }

    /**
     * For a non-YouTube URL: ask yt-dlp. If it yields a single track we return it
     * directly; if it yields a multi-entry playlist (e.g. a SoundCloud set, a Bandcamp
     * album) we promote to the playlist path so every entry is queued.
     */
    private ResolveResult resolveForeign(String url) {
        try {
            return ResolveResult.single(resolveViaFallback(url, "FOREIGN_URL"));
        } catch (YoutubeSourceException single) {
            // yt-dlp with --no-playlist often fails on a set/album URL — retry as a playlist.
            try {
                return resolvePlaylist(url);
            } catch (YoutubeSourceException playlist) {
                // Surface the original single-track error; it is usually the more helpful one.
                throw single;
            }
        }
    }

    /** Best-effort: pull a readable label out of the playlist URL for the UI. */
    private static String playlistTitleFromUrl(String url) {
        int listMarker = url.indexOf("list=");
        if (listMarker >= 0) {
            int start = listMarker + "list=".length();
            int end = url.indexOf('&', start);
            String id = end < 0 ? url.substring(start) : url.substring(start, end);
            return "Playlist " + id;
        }
        return "Playlist";
    }
}
