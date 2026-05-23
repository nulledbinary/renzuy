package renzuy.youtube;

import java.util.List;

/**
 * The result of resolving a {@code /play} argument.
 *
 * <p>For a single video, search term, or non-playlist URL, {@link #tracks()} contains
 * exactly one fully-resolved {@link AudioReference} and {@link #playlistTitle()} is
 * {@code null}.
 *
 * <p>For a playlist, the first element of {@link #tracks()} is <em>fully resolved</em>
 * (so playback can start without further work) and the remaining elements are
 * <em>lazy</em> ({@link AudioReference#isLazy()}) — title and id only — and must be
 * materialized via {@code YoutubeSource.resolveLazy} just before they play.
 *
 * @param tracks        at least one element; index 0 is always fully resolved
 * @param playlistTitle a human-readable playlist title, or {@code null} for single tracks
 */
public record ResolveResult(List<AudioReference> tracks, String playlistTitle) {

    public ResolveResult {
        if (tracks == null || tracks.isEmpty()) {
            throw new IllegalArgumentException("tracks must contain at least one entry");
        }
        tracks = List.copyOf(tracks);
    }

    /** @return convenience: a single-track result. */
    public static ResolveResult single(AudioReference reference) {
        return new ResolveResult(List.of(reference), null);
    }

    /** @return {@code true} if this result represents a multi-track playlist. */
    public boolean isPlaylist() {
        return tracks.size() > 1;
    }

    /** @return the first (fully resolved) track. */
    public AudioReference first() {
        return tracks.get(0);
    }
}
