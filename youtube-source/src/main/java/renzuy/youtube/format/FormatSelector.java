package renzuy.youtube.format;

import com.fasterxml.jackson.databind.JsonNode;

/**
 * Picks the best audio-only stream out of an Innertube {@code player} response's
 * {@code streamingData.adaptiveFormats}.
 *
 * <p>Two rules drive the choice, both in service of fast, clean playback:
 * <ol>
 *   <li><strong>Direct URLs only.</strong> A format carrying {@code signatureCipher}
 *       instead of a plain {@code url} is skipped — this library never runs YouTube's
 *       player JavaScript to decipher signatures.</li>
 *   <li><strong>Opus, around 160 kbps.</strong> Opus is Discord's native codec, so it
 *       makes for the cheapest transcode; ~160 kbps is high enough to sound clean and
 *       low enough that ffmpeg starts emitting PCM quickly.</li>
 * </ol>
 */
public final class FormatSelector {

    /**
     * A chosen, directly-playable audio format.
     *
     * @param url           the direct CDN URL
     * @param mimeType      the raw {@code mimeType} string from YouTube
     * @param bitrate       average bitrate in bits per second
     * @param contentLength total byte length, or {@code 0} if YouTube did not report it
     * @param opus          {@code true} if the codec is Opus
     */
    public record AudioFormat(String url, String mimeType, int bitrate,
                              long contentLength, boolean opus) {}

    private static final int TARGET_BITRATE = 160_000;
    private static final int OPUS_PREFERENCE = 1_000_000;

    private FormatSelector() {}

    /**
     * @param streamingData the {@code streamingData} node of a player response
     * @return the best audio format, or {@code null} if this response has no usable
     *         direct-URL audio format (e.g. every audio format is signature-ciphered)
     */
    public static AudioFormat select(JsonNode streamingData) {
        if (streamingData == null || streamingData.isMissingNode()) {
            return null;
        }
        AudioFormat best = null;
        int bestScore = Integer.MIN_VALUE;
        for (JsonNode format : streamingData.path("adaptiveFormats")) {
            String mime = format.path("mimeType").asText("");
            if (!mime.startsWith("audio/")) {
                continue;
            }
            // No direct `url` means the URL is signature-ciphered — skip it.
            JsonNode urlNode = format.get("url");
            if (urlNode == null || urlNode.asText("").isBlank()) {
                continue;
            }
            int bitrate = format.path("bitrate").asInt(format.path("averageBitrate").asInt(0));
            boolean opus = mime.toLowerCase().contains("opus");
            int score = (opus ? OPUS_PREFERENCE : 0) - Math.abs(bitrate - TARGET_BITRATE);
            if (score > bestScore) {
                bestScore = score;
                best = new AudioFormat(
                        urlNode.asText(),
                        mime,
                        bitrate,
                        format.path("contentLength").asLong(0L),
                        opus);
            }
        }
        return best;
    }
}
