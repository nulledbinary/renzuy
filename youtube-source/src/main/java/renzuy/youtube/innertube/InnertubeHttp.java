package renzuy.youtube.innertube;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.net.Authenticator;
import java.net.InetSocketAddress;
import java.net.PasswordAuthentication;
import java.net.ProxySelector;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.concurrent.CompletableFuture;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import renzuy.youtube.ProxyUrl;
import renzuy.youtube.YoutubeSourceException;

/**
 * Warm HTTP/2 transport for the Innertube API.
 *
 * <p>A single {@link HttpClient} is shared for the whole process. After the first
 * request the TLS + HTTP/2 connection to YouTube stays pooled, so every subsequent
 * resolution skips the handshake — eliminating the largest fixed latency cost of
 * talking to a remote API. {@link #prewarm()} pays that cost once, up front, before
 * any user is waiting on it.
 */
final class InnertubeHttp {

    private static final Logger log = LoggerFactory.getLogger(InnertubeHttp.class);
    private static final String API_BASE = "https://www.youtube.com/youtubei/v1/";
    private static final String WARMUP_URL = "https://www.youtube.com/generate_204";

    private final HttpClient http;
    private final ObjectMapper mapper = new ObjectMapper();
    private final Duration requestTimeout;

    InnertubeHttp(Duration connectTimeout, Duration requestTimeout) {
        this(connectTimeout, requestTimeout, null);
    }

    /**
     * @param proxy optional forward proxy. When set, every Innertube call, the
     *              prewarm probe and the CDN stream probe tunnel through it via
     *              CONNECT — keeping the resolver on the same egress IP that
     *              googlevideo binds its stream URLs to.
     */
    InnertubeHttp(Duration connectTimeout, Duration requestTimeout, ProxyUrl proxy) {
        this.requestTimeout = requestTimeout;
        HttpClient.Builder builder = HttpClient.newBuilder()
                .version(HttpClient.Version.HTTP_2)
                .connectTimeout(connectTimeout)
                .followRedirects(HttpClient.Redirect.NORMAL);
        if (proxy != null) {
            builder.proxy(ProxySelector.of(InetSocketAddress.createUnresolved(proxy.host(), proxy.port())));
            if (proxy.hasCredentials()) {
                allowBasicAuthOverConnect();
                builder.authenticator(proxyAuthenticator(proxy));
            }
            log.info("Innertube transport routed through proxy {}", proxy);
        }
        this.http = builder.build();
    }

    /**
     * The JDK ships with Basic auth disabled for CONNECT tunnels
     * ({@code jdk.http.auth.tunneling.disabledSchemes=Basic} in
     * {@code java.security}), which silently drops the credentials commercial
     * HTTP proxies require — every request then dies with 407. The override is
     * read once when the JDK's auth filter class loads, so it must be cleared
     * before the first proxied client is built. An operator-set value wins.
     */
    private static void allowBasicAuthOverConnect() {
        if (System.getProperty("jdk.http.auth.tunneling.disabledSchemes") == null) {
            System.setProperty("jdk.http.auth.tunneling.disabledSchemes", "");
        }
    }

    private static Authenticator proxyAuthenticator(ProxyUrl proxy) {
        return new Authenticator() {
            @Override
            protected PasswordAuthentication getPasswordAuthentication() {
                if (getRequestorType() != RequestorType.PROXY) return null;
                return new PasswordAuthentication(proxy.username(), proxy.password().toCharArray());
            }
        };
    }

    ObjectMapper mapper() {
        return mapper;
    }

    /**
     * POSTs an Innertube request and returns the parsed JSON response.
     *
     * @param endpoint Innertube endpoint, e.g. {@code "player"} or {@code "search"}
     * @param client   the client identity for headers
     * @param body     the request body — already contains {@code context} and the
     *                 endpoint-specific fields
     * @throws YoutubeSourceException on any non-2xx response or transport failure
     */
    JsonNode post(String endpoint, InnertubeClient client, ObjectNode body) {
        HttpRequest request = HttpRequest.newBuilder(URI.create(API_BASE + endpoint + "?prettyPrint=false"))
                .timeout(requestTimeout)
                .header("Content-Type", "application/json")
                .header("Accept", "application/json")
                .header("User-Agent", client.userAgent())
                .header("X-YouTube-Client-Name", String.valueOf(client.clientId()))
                .header("X-YouTube-Client-Version", client.version())
                .header("Origin", "https://www.youtube.com")
                .POST(HttpRequest.BodyPublishers.ofString(body.toString()))
                .build();
        try {
            HttpResponse<byte[]> response = http.send(request, HttpResponse.BodyHandlers.ofByteArray());
            if (response.statusCode() / 100 != 2) {
                throw new YoutubeSourceException("Innertube " + endpoint + " returned HTTP "
                        + response.statusCode() + " for client " + client.name());
            }
            return mapper.readTree(response.body());
        } catch (YoutubeSourceException e) {
            throw e;
        } catch (Exception e) {
            throw new YoutubeSourceException("Innertube " + endpoint + " call failed for client "
                    + client.name() + ": " + e.getMessage(), e);
        }
    }

    /**
     * Liveness check for a CDN stream URL: a one-byte ranged GET. Cheap (a single
     * short round trip) and catches dead/403 URLs before they ever reach ffmpeg.
     *
     * @return {@code true} if the CDN served the range (HTTP 200/206)
     */
    boolean probeStream(String url, String userAgent) {
        try {
            HttpRequest request = HttpRequest.newBuilder(URI.create(url))
                    .timeout(requestTimeout)
                    .header("User-Agent", userAgent)
                    .header("Range", "bytes=0-1")
                    .GET()
                    .build();
            int status = http.send(request, HttpResponse.BodyHandlers.discarding()).statusCode();
            return status == 200 || status == 206;
        } catch (Exception e) {
            log.debug("Stream URL probe failed: {}", e.getMessage());
            return false;
        }
    }

    /** Opens the TLS + HTTP/2 connection to YouTube ahead of the first real request. */
    CompletableFuture<Void> prewarm() {
        HttpRequest probe = HttpRequest.newBuilder(URI.create(WARMUP_URL))
                .timeout(requestTimeout)
                .GET()
                .build();
        return http.sendAsync(probe, HttpResponse.BodyHandlers.discarding())
                .thenAccept(r -> log.debug("Innertube connection prewarmed (HTTP {})", r.statusCode()))
                .exceptionally(e -> {
                    log.debug("Innertube prewarm skipped: {}", e.getMessage());
                    return null;
                });
    }
}
