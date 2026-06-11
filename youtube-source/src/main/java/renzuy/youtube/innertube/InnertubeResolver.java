package renzuy.youtube.innertube;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.concurrent.CompletableFuture;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import renzuy.youtube.AudioReference;
import renzuy.youtube.ProxyUrl;
import renzuy.youtube.YoutubeSourceException;
import renzuy.youtube.YoutubeSourceOptions;
import renzuy.youtube.format.FormatSelector;

/**
 * The Innertube fast path: in-process calls to YouTube's internal API.
 *
 * <p>No subprocess, no Python cold-start. A video resolves in one HTTP/2 request
 * over an already-warm connection; a search is two. Player requests rotate through
 * {@link InnertubeClients#PLAYER_ROTATION} — the first client that returns a
 * direct-URL audio format wins, the rest are skipped.
 *
 * <p>Every public method throws {@link YoutubeSourceException} on failure; the
 * caller ({@code YoutubeSource}) decides whether to fall back to yt-dlp.
 */
public final class InnertubeResolver {

    private static final Logger log = LoggerFactory.getLogger(InnertubeResolver.class);

    /** Innertube `search` filter param restricting results to videos only. */
    private static final String SEARCH_FILTER_VIDEOS = "EgIQAQ==";
    private static final long DEFAULT_URL_TTL_MILLIS = 30L * 60L * 1000L;

    private final InnertubeHttp http;
    private final YoutubeSourceOptions options;

    public InnertubeResolver(YoutubeSourceOptions options) {
        this.options = options;
        this.http = new InnertubeHttp(options.connectTimeout(), options.requestTimeout(),
                ProxyUrl.parse(options.proxy()).orElse(null));
    }

    /** Opens the HTTP/2 connection to YouTube ahead of the first resolution. */
    public CompletableFuture<Void> prewarm() {
        return http.prewarm();
    }

    /**
     * Resolves a known video id to a playable reference, rotating clients on failure.
     *
     * @throws YoutubeSourceException if no client could resolve it
     */
    public AudioReference resolveVideo(String videoId) {
        YoutubeSourceException lastError = null;
        for (InnertubeClient client : InnertubeClients.PLAYER_ROTATION) {
            try {
                return resolveWith(client, videoId);
            } catch (YoutubeSourceException e) {
                lastError = e;
                log.debug("Innertube client {} could not resolve {}: {}",
                        client.name(), videoId, e.getMessage());
            }
        }
        throw lastError != null ? lastError
                : new YoutubeSourceException("No Innertube client resolved video " + videoId);
    }

    /**
     * Runs an Innertube search and returns the first matching video id.
     *
     * @throws YoutubeSourceException if the search returned no usable video
     */
    public String searchFirstVideoId(String term) {
        InnertubeClient client = InnertubeClients.SEARCH_CLIENT;
        ObjectMapper m = http.mapper();
        ObjectNode body = m.createObjectNode();
        body.set("context", contextFor(m, client));
        body.put("query", term);
        body.put("params", SEARCH_FILTER_VIDEOS);

        JsonNode response = http.post("search", client, body);
        JsonNode videoRenderer = findFirst(response, "videoRenderer");
        if (videoRenderer == null) {
            throw new YoutubeSourceException("No search results for '" + term + "'");
        }
        String id = videoRenderer.path("videoId").asText("");
        if (id.isBlank()) {
            throw new YoutubeSourceException("Top search result for '" + term + "' had no video id");
        }
        return id;
    }

    // ------------------------------------------------------------------------

    private AudioReference resolveWith(InnertubeClient client, String videoId) {
        ObjectMapper m = http.mapper();
        ObjectNode body = m.createObjectNode();
        body.set("context", contextFor(m, client));
        body.put("videoId", videoId);
        body.put("contentCheckOk", true);
        body.put("racyCheckOk", true);

        JsonNode response = http.post("player", client, body);

        String status = response.path("playabilityStatus").path("status").asText("");
        if (!status.equalsIgnoreCase("OK")) {
            String reason = response.path("playabilityStatus").path("reason").asText(status);
            throw new YoutubeSourceException("Not playable via " + client.name() + ": " + reason);
        }

        JsonNode details = response.path("videoDetails");
        JsonNode streamingData = response.path("streamingData");
        boolean live = details.path("isLiveContent").asBoolean(false)
                || details.path("isLive").asBoolean(false);

        String streamUrl;
        String codec;
        boolean opus;
        if (live) {
            streamUrl = streamingData.path("hlsManifestUrl").asText("");
            if (streamUrl.isBlank()) {
                throw new YoutubeSourceException(
                        "Live broadcast has no HLS manifest via " + client.name());
            }
            codec = "hls";
            opus = false;
        } else {
            FormatSelector.AudioFormat format = FormatSelector.select(streamingData);
            if (format == null) {
                throw new YoutubeSourceException(
                        "No direct-URL audio format from " + client.name());
            }
            streamUrl = format.url();
            codec = format.opus() ? "opus" : format.mimeType();
            opus = format.opus();
        }

        // Verify the CDN actually serves the URL before committing to it.
        if (options.verifyStreamUrl() && !live
                && !http.probeStream(streamUrl, client.userAgent())) {
            throw new YoutubeSourceException(
                    "CDN rejected the stream URL from " + client.name());
        }

        String id = details.path("videoId").asText(videoId);
        String title = details.path("title").asText("Unknown title");
        String author = details.path("author").asText("Unknown");
        long durationMillis = parseLong(details.path("lengthSeconds").asText("0")) * 1000L;

        log.info("[innertube] resolved '{}' via {} ({}{})",
                title, client.name(), codec, live ? ", live" : "");

        return new AudioReference(
                title, author, durationMillis, id,
                "https://www.youtube.com/watch?v=" + id,
                streamUrl, codec, opus, live,
                client.userAgent(),
                AudioReference.Origin.INNERTUBE, client.name(),
                expiryOf(streamUrl));
    }

    private static ObjectNode contextFor(ObjectMapper mapper, InnertubeClient client) {
        ObjectNode context = mapper.createObjectNode();
        context.set("client", mapper.valueToTree(client.clientContext()));
        return context;
    }

    /** Depth-first search for the first node carrying {@code field} as a key. */
    private static JsonNode findFirst(JsonNode node, String field) {
        if (node == null) {
            return null;
        }
        if (node.isObject()) {
            JsonNode direct = node.get(field);
            if (direct != null) {
                return direct;
            }
        }
        if (node.isContainerNode()) {
            for (JsonNode child : node) {
                JsonNode found = findFirst(child, field);
                if (found != null) {
                    return found;
                }
            }
        }
        return null;
    }

    /** googlevideo CDN URLs carry {@code &expire=<unix seconds>}; default to 30 min. */
    private static long expiryOf(String url) {
        int marker = url.indexOf("expire=");
        if (marker < 0) {
            return System.currentTimeMillis() + DEFAULT_URL_TTL_MILLIS;
        }
        int start = marker + "expire=".length();
        int end = start;
        while (end < url.length() && Character.isDigit(url.charAt(end))) {
            end++;
        }
        try {
            return Long.parseLong(url.substring(start, end)) * 1000L;
        } catch (RuntimeException e) {
            return System.currentTimeMillis() + DEFAULT_URL_TTL_MILLIS;
        }
    }

    private static long parseLong(String s) {
        try {
            return Long.parseLong(s.trim());
        } catch (RuntimeException e) {
            return 0L;
        }
    }
}
