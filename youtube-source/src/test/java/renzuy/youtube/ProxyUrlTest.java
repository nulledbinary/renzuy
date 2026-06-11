package renzuy.youtube;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Optional;
import org.junit.jupiter.api.Test;

class ProxyUrlTest {

    @Test
    void parsesCredentialedUrl() {
        Optional<ProxyUrl> parsed = ProxyUrl.parse(
                "http://brd-customer-abc-zone-isp:s3cr3t@brd.superproxy.io:33335");
        assertTrue(parsed.isPresent());
        ProxyUrl proxy = parsed.get();
        assertEquals("brd.superproxy.io", proxy.host());
        assertEquals(33335, proxy.port());
        assertEquals("brd-customer-abc-zone-isp", proxy.username());
        assertEquals("s3cr3t", proxy.password());
        assertTrue(proxy.hasCredentials());
    }

    @Test
    void parsesCredentialFreeUrl() {
        Optional<ProxyUrl> parsed = ProxyUrl.parse("http://10.0.0.5:3128");
        assertTrue(parsed.isPresent());
        ProxyUrl proxy = parsed.get();
        assertEquals("10.0.0.5", proxy.host());
        assertEquals(3128, proxy.port());
        assertEquals("", proxy.username());
        assertEquals("", proxy.password());
        assertFalse(proxy.hasCredentials());
    }

    @Test
    void keepsTheRawUrlForSubprocessConsumers() {
        String raw = "http://user:pass@proxy.example:8080";
        assertEquals(raw, ProxyUrl.parse("  " + raw + " ").orElseThrow().url());
    }

    @Test
    void rejectsSocks5Scheme() {
        // Bright Data's SOCKS5 port refuses raw-IP targets, which breaks
        // googlevideo CDN fetches — only http:// CONNECT proxies are usable.
        assertTrue(ProxyUrl.parse("socks5://user:pass@brd.superproxy.io:22228").isEmpty());
    }

    @Test
    void rejectsHttpsScheme() {
        assertTrue(ProxyUrl.parse("https://user:pass@proxy.example:443").isEmpty());
    }

    @Test
    void rejectsMissingPort() {
        assertTrue(ProxyUrl.parse("http://user:pass@brd.superproxy.io").isEmpty());
    }

    @Test
    void rejectsNullBlankAndGarbage() {
        assertTrue(ProxyUrl.parse(null).isEmpty());
        assertTrue(ProxyUrl.parse("").isEmpty());
        assertTrue(ProxyUrl.parse("   ").isEmpty());
        assertTrue(ProxyUrl.parse("not a url").isEmpty());
        assertTrue(ProxyUrl.parse("PLACEHOLDER-set-real-brightdata-url").isEmpty());
    }

    @Test
    void toStringNeverLeaksCredentials() {
        ProxyUrl proxy = ProxyUrl.parse(
                "http://brd-customer-abc-zone-isp:hunter2@brd.superproxy.io:33335").orElseThrow();
        String shown = proxy.toString();
        assertFalse(shown.contains("hunter2"));
        assertFalse(shown.contains("brd-customer-abc-zone-isp"));
        assertEquals("http://***@brd.superproxy.io:33335", shown);
    }

    @Test
    void toStringWithoutCredentialsShowsHostAndPort() {
        ProxyUrl proxy = ProxyUrl.parse("http://10.0.0.5:3128").orElseThrow();
        assertEquals("http://10.0.0.5:3128", proxy.toString());
    }
}
