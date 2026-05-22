package renzuy.youtube;

import java.time.Duration;

/**
 * Tuning for a {@link YoutubeSource}. Build with {@link #builder()}; the defaults
 * are chosen for lowest latency and are fine for the bot as-is.
 *
 * @param connectTimeout  TCP/TLS connect timeout for Innertube calls
 * @param requestTimeout  per-request timeout for Innertube calls and stream probes
 * @param cacheTtl        how long a resolved track stays in the in-memory cache
 * @param cacheMaxEntries hard cap on cache size (a pure latency optimisation)
 * @param fallbackEnabled whether to fall back to yt-dlp when Innertube cannot resolve
 * @param ytDlpPath       path/command for the yt-dlp executable used by the fallback
 * @param verifyStreamUrl probe the chosen CDN URL with a 1-byte ranged GET before
 *                        returning it — costs one short round trip, but guarantees
 *                        the bot never starts ffmpeg against a dead URL
 * @param prewarmOnStart  open the HTTP/2 connection to YouTube at construction time
 */
public record YoutubeSourceOptions(
        Duration connectTimeout,
        Duration requestTimeout,
        Duration cacheTtl,
        int cacheMaxEntries,
        boolean fallbackEnabled,
        String ytDlpPath,
        boolean verifyStreamUrl,
        boolean prewarmOnStart) {

    public static Builder builder() {
        return new Builder();
    }

    public static YoutubeSourceOptions defaults() {
        return builder().build();
    }

    /** Mutable builder; every field has a low-latency default. */
    public static final class Builder {
        private Duration connectTimeout = Duration.ofSeconds(3);
        private Duration requestTimeout = Duration.ofSeconds(6);
        private Duration cacheTtl = Duration.ofMinutes(30);
        private int cacheMaxEntries = 512;
        private boolean fallbackEnabled = true;
        private String ytDlpPath = "yt-dlp";
        private boolean verifyStreamUrl = true;
        private boolean prewarmOnStart = true;

        public Builder connectTimeout(Duration v) { this.connectTimeout = v; return this; }
        public Builder requestTimeout(Duration v) { this.requestTimeout = v; return this; }
        public Builder cacheTtl(Duration v) { this.cacheTtl = v; return this; }
        public Builder cacheMaxEntries(int v) { this.cacheMaxEntries = v; return this; }
        public Builder fallbackEnabled(boolean v) { this.fallbackEnabled = v; return this; }
        public Builder ytDlpPath(String v) { this.ytDlpPath = v; return this; }
        public Builder verifyStreamUrl(boolean v) { this.verifyStreamUrl = v; return this; }
        public Builder prewarmOnStart(boolean v) { this.prewarmOnStart = v; return this; }

        public YoutubeSourceOptions build() {
            return new YoutubeSourceOptions(
                    connectTimeout, requestTimeout, cacheTtl, cacheMaxEntries,
                    fallbackEnabled, ytDlpPath, verifyStreamUrl, prewarmOnStart);
        }
    }
}
