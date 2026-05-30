package renzuy.youtube;

/**
 * A fully resolved, directly-playable track.
 *
 * <p>{@link #streamUrl()} is a CDN URL that ffmpeg (or any HTTP client) can read
 * immediately — there is no further resolution step. Everything the bot needs to
 * start playback and to render a "now playing" message is here.
 *
 * @param title              human-readable track title
 * @param author             uploader / channel / artist
 * @param durationMillis     track length, or {@code 0} if unknown (e.g. live)
 * @param videoId            YouTube video id, or {@code ""} for non-YouTube sources
 * @param webpageUrl         canonical page URL (for "now playing" links)
 * @param streamUrl          direct, playable audio URL (or HLS manifest if {@link #live})
 * @param codec              best-effort codec label ("opus", "hls", a mime type, ...)
 * @param opus               {@code true} if the stream is Opus — Discord's native codec
 * @param live               {@code true} if this is a live broadcast (HLS, no duration)
 * @param userAgent          User-Agent the downloader must send so the CDN URL is accepted
 * @param origin             which resolution path produced this reference
 * @param clientName         the Innertube client / tool that produced it (for logs)
 * @param expiresAtEpochMillis wall-clock time the CDN URL stops working
 */
public record AudioReference(
        String title,
        String author,
        long durationMillis,
        String videoId,
        String webpageUrl,
        String streamUrl,
        String codec,
        boolean opus,
        boolean live,
        String userAgent,
        Origin origin,
        String clientName,
        long expiresAtEpochMillis) {

    /** Which arm of the hybrid resolver produced a reference. */
    public enum Origin {
        /** In-process Innertube API call — the fast path. */
        INNERTUBE,
        /** The yt-dlp subprocess fallback. */
        YT_DLP
    }

    /** @return {@code true} if the CDN URL is at/after expiry by the given wall-clock time. */
    public boolean isExpired(long atEpochMillis) {
        return expiresAtEpochMillis > 0 && atEpochMillis >= expiresAtEpochMillis;
    }

    /**
     * @return {@code true} if this reference is a placeholder from a playlist enumeration —
     *         it has metadata for the queue ("now playing" / `/queue` listing) but no
     *         stream URL yet, and must be materialized via {@code YoutubeSource.resolveLazy}
     *         before ffmpeg can consume it.
     */
    public boolean isLazy() {
        return streamUrl == null || streamUrl.isBlank();
    }

    /**
     * @return the YouTube video thumbnail URL, or null if not a YouTube video.
     */
    public String thumbnailUrl() {
        if (videoId != null && !videoId.isBlank()) {
            return "https://img.youtube.com/vi/" + videoId + "/hqdefault.jpg";
        }
        return null;
    }

    /**
     * Builds a lazy placeholder for a playlist entry. The stream URL is empty; the
     * downstream resolver re-resolves by {@code webpageUrl} (or {@code videoId} for
     * YouTube entries) right before playback.
     */
    public static AudioReference lazy(
            String title, String author, long durationMillis,
            String videoId, String webpageUrl) {
        return new AudioReference(
                title, author, durationMillis, videoId, webpageUrl,
                "", "lazy", false, false, "",
                Origin.YT_DLP, "yt-dlp:flat-playlist", 0L);
    }
}
