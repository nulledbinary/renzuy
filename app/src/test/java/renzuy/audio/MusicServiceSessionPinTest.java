package renzuy.audio;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;
import renzuy.youtube.ProxyUrl;

class MusicServiceSessionPinTest {

    @Test
    void pinsASessionOntoBrightDataUrls() {
        ProxyUrl proxy = ProxyUrl.parse(
                "http://brd-customer-abc-zone-isp:pw@brd.superproxy.io:33335").orElseThrow();
        String pinned = MusicService.pinBrightDataSession(proxy);

        ProxyUrl result = ProxyUrl.parse(pinned).orElseThrow();
        assertTrue(result.username().startsWith("brd-customer-abc-zone-isp-session-"));
        assertEquals("pw", result.password());
        assertEquals("brd.superproxy.io", result.host());
        assertEquals(33335, result.port());
    }

    @Test
    void twoPinsAreDistinctSessions() {
        ProxyUrl proxy = ProxyUrl.parse(
                "http://brd-customer-abc-zone-isp:pw@brd.superproxy.io:33335").orElseThrow();
        assertNotEquals(MusicService.pinBrightDataSession(proxy),
                MusicService.pinBrightDataSession(proxy));
    }

    @Test
    void respectsAnOperatorChosenSession() {
        String raw = "http://brd-customer-abc-zone-isp-session-mine:pw@brd.superproxy.io:33335";
        ProxyUrl proxy = ProxyUrl.parse(raw).orElseThrow();
        assertEquals(raw, MusicService.pinBrightDataSession(proxy));
    }

    @Test
    void leavesNonBrightDataProxiesAlone() {
        String raw = "http://user:pass@proxy.example:8080";
        ProxyUrl proxy = ProxyUrl.parse(raw).orElseThrow();
        assertEquals(raw, MusicService.pinBrightDataSession(proxy));
    }
}
