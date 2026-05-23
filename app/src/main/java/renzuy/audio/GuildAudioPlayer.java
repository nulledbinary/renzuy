package renzuy.audio;

import java.io.IOException;
import java.util.LinkedList;
import java.util.List;
import renzuy.youtube.AudioReference;

/**
 * Per-guild playback: one currently-playing stream plus a FIFO queue of pending tracks.
 *
 * <p>Tracks arrive as {@link AudioReference}s — either fully resolved (single-track
 * {@code /play}) or {@linkplain AudioReference#isLazy() lazy} placeholders (playlist
 * entries waiting to be resolved). Lazy entries are materialized via the
 * {@link LazyResolver} passed at construction time, just before they are handed to
 * ffmpeg — so a 100-track playlist enqueues instantly and pays the per-track
 * resolution only as the queue actually advances.
 *
 * <p>Every method that touches mutable state synchronises on {@link #lock}, so callers
 * from the JDA event thread, the {@code play-resolver} worker, and the audio-send
 * thread all see a consistent view.
 */
public final class GuildAudioPlayer {

    /** Materializes a {@link AudioReference#isLazy() lazy} reference into a playable one. */
    @FunctionalInterface
    public interface LazyResolver {
        AudioReference resolve(AudioReference lazy);
    }

    private final Object lock = new Object();
    /** Queue of pending tracks. {@link LinkedList} so {@link #remove(int)} can index in. */
    private final LinkedList<AudioReference> pending = new LinkedList<>();
    private final LazyResolver lazyResolver;

    private FfmpegStream currentStream;
    private AudioReference currentTrack;

    public GuildAudioPlayer(LazyResolver lazyResolver) {
        this.lazyResolver = lazyResolver;
    }

    /** @return {@code true} if playback started now, {@code false} if the track was queued. */
    public boolean enqueue(AudioReference track) throws IOException {
        synchronized (lock) {
            if (currentStream == null) {
                startStream(track);
                return true;
            }
            pending.add(track);
            return false;
        }
    }

    /**
     * Adds every track in order. The first track starts playing if nothing is currently
     * playing; the rest go on the queue. Lazy entries past the head stay lazy until
     * {@link #advance()} reaches them.
     *
     * @return {@code true} if the first track started playing now, {@code false} if
     *         everything went on the queue behind an already-playing track
     */
    public boolean enqueueAll(List<AudioReference> tracks) throws IOException {
        if (tracks.isEmpty()) {
            return false;
        }
        synchronized (lock) {
            boolean startedNow = currentStream == null;
            int from = 0;
            if (startedNow) {
                startStream(materialize(tracks.get(0)));
                from = 1;
            }
            for (int i = from; i < tracks.size(); i++) {
                pending.add(tracks.get(i));
            }
            return startedNow;
        }
    }

    /**
     * Materialize a possibly-lazy reference. Caller paths into this on the audio-send
     * thread (via {@link #advance()}) for queued tracks — a brief block between tracks
     * is acceptable; a glitch mid-track is not, so streams are never resolved late.
     */
    private AudioReference materialize(AudioReference track) {
        if (!track.isLazy()) {
            return track;
        }
        return lazyResolver.resolve(track);
    }

    private void startStream(AudioReference track) throws IOException {
        currentStream = new FfmpegStream(track.streamUrl(), track.userAgent());
        currentTrack = track;
        System.out.println("[Audio] Starting stream: " + track.title());
    }

    /** Called by the audio-send thread once per 20 ms; null = nothing to send right now. */
    public byte[] pollFrame() {
        synchronized (lock) {
            if (currentStream == null) {
                return null;
            }
            byte[] frame = currentStream.pollFrame();
            if (frame != null) {
                return frame;
            }
            if (!currentStream.isExhausted()) {
                return null;
            }
            System.out.println("[Audio] Finished: "
                    + (currentTrack != null ? currentTrack.title() : "?"));
            currentStream.close();
            currentStream = null;
            currentTrack = null;
            advance();
            return currentStream != null ? currentStream.pollFrame() : null;
        }
    }

    /**
     * Best-effort: pulls the next pending track, materializes it if lazy, and starts
     * it, skipping any that fail to resolve or spawn. Runs on the audio-send thread
     * — only between tracks, so a brief block to re-resolve a playlist entry is fine.
     */
    private void advance() {
        AudioReference next;
        while ((next = pending.poll()) != null) {
            try {
                AudioReference resolved = materialize(next);
                startStream(resolved);
                return;
            } catch (IOException e) {
                System.err.println("[Audio] Failed to start " + next.title() + ": " + e.getMessage());
            } catch (RuntimeException e) {
                System.err.println("[Audio] Failed to resolve " + next.title() + ": " + e.getMessage());
            }
        }
    }

    // ------------------------------------------------------------------------
    // Read-only inspection — used by /queue
    // ------------------------------------------------------------------------

    /** @return the currently-playing track, or {@code null} if nothing is playing. */
    public AudioReference nowPlaying() {
        synchronized (lock) {
            return currentTrack;
        }
    }

    /** @return a snapshot of the pending queue, in play order. */
    public List<AudioReference> pendingTracks() {
        synchronized (lock) {
            return List.copyOf(pending);
        }
    }

    // ------------------------------------------------------------------------
    // Mutations — used by /skip, /remove, /stop
    // ------------------------------------------------------------------------

    /**
     * Stops the currently-playing track and starts the next pending one (if any).
     *
     * @return the track that was skipped, or {@code null} if nothing was playing
     */
    public AudioReference skip() {
        synchronized (lock) {
            if (currentStream == null) {
                return null;
            }
            AudioReference skipped = currentTrack;
            currentStream.close();
            currentStream = null;
            currentTrack = null;
            advance();
            return skipped;
        }
    }

    /**
     * Removes the track at the given 1-based position in the pending queue.
     *
     * @return the removed track, or {@code null} if the position is out of range
     */
    public AudioReference remove(int oneBasedPosition) {
        synchronized (lock) {
            int index = oneBasedPosition - 1;
            if (index < 0 || index >= pending.size()) {
                return null;
            }
            return pending.remove(index);
        }
    }

    /** Stops playback and clears the queue. */
    public void stop() {
        synchronized (lock) {
            if (currentStream != null) {
                currentStream.close();
                currentStream = null;
                currentTrack = null;
            }
            pending.clear();
        }
    }
}
