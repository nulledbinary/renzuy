package renzuy.youtube.innertube;

import java.util.Map;

/**
 * One Innertube "client" identity — the disguise the request wears.
 *
 * <p>YouTube serves different stream data depending on which of its own apps the
 * request claims to be. Some clients hand back direct, ready-to-play CDN URLs;
 * others return signature-ciphered URLs that require running YouTube's player
 * JavaScript to unscramble. This library only ever uses the former.
 *
 * @param userAgent     the HTTP User-Agent that matches this client
 * @param clientId      the numeric {@code X-YouTube-Client-Name} header value
 * @param clientContext the full {@code context.client} JSON object, as a map
 */
public record InnertubeClient(
        String userAgent,
        int clientId,
        Map<String, Object> clientContext) {

    /** The Innertube {@code clientName}, e.g. {@code "ANDROID_VR"}. */
    public String name() {
        return String.valueOf(clientContext.get("clientName"));
    }

    /** The Innertube {@code clientVersion}. */
    public String version() {
        return String.valueOf(clientContext.get("clientVersion"));
    }
}
