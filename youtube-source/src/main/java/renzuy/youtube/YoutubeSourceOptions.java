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
 * @param ytDlpCookiesPath path to a Netscape-format cookies file passed to yt-dlp via
 *                        {@code --cookies}; blank/null disables. One of three
 *                        mitigations for the "Sign in to confirm you're not a bot"
 *                        wall on datacenter IPs.
 * @param ytDlpProxy      proxy URL passed to yt-dlp via {@code --proxy}; blank/null
 *                        disables. Typically a residential SOCKS5/HTTPS endpoint;
 *                        the silver bullet for the bot wall since it masks the
 *                        datacenter ASN entirely.
 * @param ytDlpPoToken    GVS PoToken passed via {@code --extractor-args
 *                        youtube:po_token=mweb.gvs+TOKEN}; blank/null disables.
 *                        Lasts roughly 24 h before YouTube rotates it.
 * @param ytDlpPotProviderUrl URL of a bgutil-ytdlp-pot-provider HTTP service.
 *                        When set, the yt-dlp plugin (installed in the image)
 *                        calls this endpoint to mint a fresh PoToken per call —
 *                        the AWS-native alternative to a static {@code po_token}
 *                        or a residential proxy. Bypasses YouTube's IP scoring
 *                        without a tracked session.
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
        String ytDlpCookiesPath,
        String ytDlpProxy,
        String ytDlpPoToken,
        String ytDlpPotProviderUrl,
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
        private String ytDlpCookiesPath = "";
        private String ytDlpProxy = "";
        private String ytDlpPoToken = "";
        private String ytDlpPotProviderUrl = "";
        private boolean verifyStreamUrl = true;
        private boolean prewarmOnStart = true;

        public Builder connectTimeout(Duration v) { this.connectTimeout = v; return this; }
        public Builder requestTimeout(Duration v) { this.requestTimeout = v; return this; }
        public Builder cacheTtl(Duration v) { this.cacheTtl = v; return this; }
        public Builder cacheMaxEntries(int v) { this.cacheMaxEntries = v; return this; }
        public Builder fallbackEnabled(boolean v) { this.fallbackEnabled = v; return this; }
        public Builder ytDlpPath(String v) { this.ytDlpPath = v; return this; }
        public Builder ytDlpCookiesPath(String v) { this.ytDlpCookiesPath = v == null ? "" : v; return this; }
        public Builder ytDlpProxy(String v) { this.ytDlpProxy = v == null ? "" : v; return this; }
        public Builder ytDlpPoToken(String v) { this.ytDlpPoToken = v == null ? "" : v; return this; }
        public Builder ytDlpPotProviderUrl(String v) { this.ytDlpPotProviderUrl = v == null ? "" : v; return this; }
        public Builder verifyStreamUrl(boolean v) { this.verifyStreamUrl = v; return this; }
        public Builder prewarmOnStart(boolean v) { this.prewarmOnStart = v; return this; }

        public YoutubeSourceOptions build() {
            return new YoutubeSourceOptions(
                    connectTimeout, requestTimeout, cacheTtl, cacheMaxEntries,
                    fallbackEnabled, ytDlpPath, ytDlpCookiesPath, ytDlpProxy, ytDlpPoToken,
                    ytDlpPotProviderUrl, verifyStreamUrl, prewarmOnStart);
        }
    }
}
