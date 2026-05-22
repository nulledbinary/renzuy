package renzuy.youtube.format;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;
import renzuy.youtube.format.FormatSelector.AudioFormat;

class FormatSelectorTest {

    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void prefersOpusOverHigherBitrateAac() {
        ObjectNode streamingData = mapper.createObjectNode();
        ArrayNode formats = streamingData.putArray("adaptiveFormats");
        formats.add(audio("audio/mp4; codecs=\"mp4a.40.2\"", 256_000, "https://cdn/aac"));
        formats.add(audio("audio/webm; codecs=\"opus\"", 130_000, "https://cdn/opus"));

        AudioFormat chosen = FormatSelector.select(streamingData);

        assertNotNull(chosen);
        assertTrue(chosen.opus());
        assertEquals("https://cdn/opus", chosen.url());
    }

    @Test
    void skipsVideoFormats() {
        ObjectNode streamingData = mapper.createObjectNode();
        ArrayNode formats = streamingData.putArray("adaptiveFormats");
        formats.add(audio("video/mp4; codecs=\"avc1.640028\"", 1_000_000, "https://cdn/video"));
        formats.add(audio("audio/webm; codecs=\"opus\"", 128_000, "https://cdn/opus"));

        AudioFormat chosen = FormatSelector.select(streamingData);

        assertNotNull(chosen);
        assertEquals("https://cdn/opus", chosen.url());
    }

    @Test
    void skipsSignatureCipheredFormatsWithoutDirectUrl() {
        ObjectNode streamingData = mapper.createObjectNode();
        ArrayNode formats = streamingData.putArray("adaptiveFormats");
        ObjectNode ciphered = formats.addObject();
        ciphered.put("mimeType", "audio/webm; codecs=\"opus\"");
        ciphered.put("bitrate", 160_000);
        ciphered.put("signatureCipher", "s=scrambled&url=https%3A%2F%2Fcdn%2Fneeds-js");

        assertNull(FormatSelector.select(streamingData));
    }

    @Test
    void returnsNullWhenNoStreamingData() {
        assertNull(FormatSelector.select(null));
        assertNull(FormatSelector.select(mapper.createObjectNode()));
    }

    private ObjectNode audio(String mimeType, int bitrate, String url) {
        ObjectNode node = mapper.createObjectNode();
        node.put("mimeType", mimeType);
        node.put("bitrate", bitrate);
        node.put("url", url);
        node.put("contentLength", 1_234_567L);
        return node;
    }
}
