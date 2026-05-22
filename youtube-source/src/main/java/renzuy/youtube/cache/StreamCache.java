package renzuy.youtube.cache;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import renzuy.youtube.AudioReference;

/**
 * A tiny TTL cache of resolved tracks, keyed by video id.
 *
 * <p>A cache hit makes a repeat {@code /play} of the same song effectively instant —
 * zero network round trips, zero subprocess. Entries are evicted once our own TTL
 * elapses <em>or</em> the underlying CDN URL is near its own expiry, whichever comes
 * first, so a hit always hands back a URL that still works.
 *
 * <p>Thread-safe; a single instance is shared across all guilds.
 */
public final class StreamCache {

    private record Entry(AudioReference reference, long deadlineEpochMillis) {}

    /** Discard a cached URL this long before its real expiry, to be safe. */
    private static final long EXPIRY_SAFETY_MARGIN_MILLIS = 60_000L;

    private final Map<String, Entry> entries = new ConcurrentHashMap<>();
    private final long ttlMillis;
    private final int maxEntries;

    public StreamCache(long ttlMillis, int maxEntries) {
        this.ttlMillis = ttlMillis;
        this.maxEntries = Math.max(1, maxEntries);
    }

    /** @return the cached reference if present and still fresh, else {@code null}. */
    public AudioReference get(String videoId) {
        if (videoId == null || videoId.isBlank()) {
            return null;
        }
        Entry entry = entries.get(videoId);
        if (entry == null) {
            return null;
        }
        long now = System.currentTimeMillis();
        if (now >= entry.deadlineEpochMillis
                || entry.reference.isExpired(now + EXPIRY_SAFETY_MARGIN_MILLIS)) {
            entries.remove(videoId, entry);
            return null;
        }
        return entry.reference;
    }

    /** Caches a resolved reference. No-op for references without a video id. */
    public void put(AudioReference reference) {
        if (reference == null || reference.videoId() == null || reference.videoId().isBlank()) {
            return;
        }
        // Simplest bounded-size policy: the cache is a pure latency optimisation, so
        // a full flush on overflow costs nothing but a few re-resolutions.
        if (entries.size() >= maxEntries) {
            entries.clear();
        }
        entries.put(reference.videoId(),
                new Entry(reference, System.currentTimeMillis() + ttlMillis));
    }

    public void clear() {
        entries.clear();
    }
}
