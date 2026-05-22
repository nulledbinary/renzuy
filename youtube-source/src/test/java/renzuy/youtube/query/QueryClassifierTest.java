package renzuy.youtube.query;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;
import renzuy.youtube.query.QueryClassifier.Kind;
import renzuy.youtube.query.QueryClassifier.Result;

class QueryClassifierTest {

    @Test
    void watchUrlIsAVideo() {
        Result r = QueryClassifier.classify("https://www.youtube.com/watch?v=dQw4w9WgXcQ");
        assertEquals(Kind.VIDEO, r.kind());
        assertEquals("dQw4w9WgXcQ", r.value());
    }

    @Test
    void shortUrlIsAVideo() {
        Result r = QueryClassifier.classify("https://youtu.be/dQw4w9WgXcQ");
        assertEquals(Kind.VIDEO, r.kind());
        assertEquals("dQw4w9WgXcQ", r.value());
    }

    @Test
    void shortsUrlIsAVideo() {
        Result r = QueryClassifier.classify("https://www.youtube.com/shorts/abcdefghijk");
        assertEquals(Kind.VIDEO, r.kind());
        assertEquals("abcdefghijk", r.value());
    }

    @Test
    void schemelessYouTubeUrlIsAVideo() {
        Result r = QueryClassifier.classify("youtube.com/watch?v=dQw4w9WgXcQ");
        assertEquals(Kind.VIDEO, r.kind());
        assertEquals("dQw4w9WgXcQ", r.value());
    }

    @Test
    void watchUrlWithListParamStillPlaysTheVideo() {
        Result r = QueryClassifier.classify(
                "https://www.youtube.com/watch?v=dQw4w9WgXcQ&list=PLxyz");
        assertEquals(Kind.VIDEO, r.kind());
        assertEquals("dQw4w9WgXcQ", r.value());
    }

    @Test
    void playlistOnlyUrlIsAPlaylist() {
        Result r = QueryClassifier.classify("https://www.youtube.com/playlist?list=PL123abc");
        assertEquals(Kind.PLAYLIST, r.kind());
        assertEquals("PL123abc", r.value());
    }

    @Test
    void musicYouTubeUrlIsAVideo() {
        Result r = QueryClassifier.classify("https://music.youtube.com/watch?v=dQw4w9WgXcQ");
        assertEquals(Kind.VIDEO, r.kind());
        assertEquals("dQw4w9WgXcQ", r.value());
    }

    @Test
    void plainTextIsASearch() {
        Result r = QueryClassifier.classify("never gonna give you up");
        assertEquals(Kind.SEARCH, r.kind());
        assertEquals("never gonna give you up", r.value());
    }

    @Test
    void singleWordIsASearch() {
        Result r = QueryClassifier.classify("lofi");
        assertEquals(Kind.SEARCH, r.kind());
    }

    @Test
    void nonYouTubeUrlIsForeign() {
        Result r = QueryClassifier.classify("https://open.spotify.com/track/abc123");
        assertEquals(Kind.FOREIGN_URL, r.kind());
    }

    @Test
    void blankQueryIsRejected() {
        assertThrows(IllegalArgumentException.class, () -> QueryClassifier.classify("   "));
    }
}
