package renzuy.youtube;

import java.net.URI;
import java.net.URISyntaxException;
import java.util.Optional;

/**
 * A validated forward-proxy endpoint for the YouTube pipeline.
 *
 * <p>Every hop that touches YouTube must share one egress IP: googlevideo CDN
 * URLs are minted for the IP that requested them, so a URL resolved through the
 * proxy 403s when fetched from anywhere else. A single proxy value therefore
 * routes all three hops — the Innertube client (including stream probes), the
 * yt-dlp subprocess, and ffmpeg's media download.
 *
 * <p>Only {@code http://} proxies with an explicit port are accepted, on
 * purpose: java.net.http and ffmpeg both tunnel HTTPS through an HTTP proxy via
 * CONNECT, while provider SOCKS5 endpoints (e.g. Bright Data port 22228) reject
 * raw-IP CDN targets outright — use the provider's HTTP port instead (Bright
 * Data: 33335).
 *
 * <p>{@link #toString()} redacts credentials. Never log {@link #url()}.
 */
public record ProxyUrl(String url, String host, int port, String username, String password) {

    /**
     * Parses and validates a proxy URL of the form
     * {@code http://[user:pass@]host:port}.
     *
     * @return empty for null/blank input and for anything that is not an
     *         {@code http://} URL with an explicit host and port
     */
    public static Optional<ProxyUrl> parse(String raw) {
        if (raw == null || raw.isBlank()) return Optional.empty();
        String trimmed = raw.trim();
        URI uri;
        try {
            uri = new URI(trimmed);
        } catch (URISyntaxException e) {
            return Optional.empty();
        }
        if (!"http".equalsIgnoreCase(uri.getScheme())
                || uri.getHost() == null || uri.getHost().isBlank()
                || uri.getPort() == -1) {
            return Optional.empty();
        }
        String user = "";
        String pass = "";
        String userInfo = uri.getUserInfo();
        if (userInfo != null && !userInfo.isEmpty()) {
            int colon = userInfo.indexOf(':');
            user = colon < 0 ? userInfo : userInfo.substring(0, colon);
            pass = colon < 0 ? "" : userInfo.substring(colon + 1);
        }
        return Optional.of(new ProxyUrl(trimmed, uri.getHost(), uri.getPort(), user, pass));
    }

    /** @return {@code true} if the proxy requires authentication. */
    public boolean hasCredentials() {
        return !username.isEmpty();
    }

    /** Credential-free rendering, safe for logs: {@code http://***@host:port}. */
    @Override
    public String toString() {
        return hasCredentials()
                ? "http://***@" + host + ":" + port
                : "http://" + host + ":" + port;
    }
}
