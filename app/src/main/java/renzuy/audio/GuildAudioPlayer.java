package renzuy.audio;

import java.io.IOException;
import java.util.LinkedList;
import java.util.List;
import renzuy.youtube.AudioReference;

/**
 * Per-guild playback: one active stream + a FIFO of pending tracks.
 *
 * <p>The audio-send thread calls {@link #pollFrame()} every 20 ms. That path is the
 * latency floor of the bot — it reads {@link #currentStream} through a {@code volatile}
 * field, with no lock contention on the steady state. Mutations (enqueue/skip/stop)
 * still take {@link #lock} so concurrent callers see a coherent queue, but the audio
 * thread only acquires the lock when a track ends and it needs to advance.
 */
public final class GuildAudioPlayer {

    @FunctionalInterface
    public interface LazyResolver {
        AudioReference resolve(AudioReference lazy);
    }

    private final Object lock = new Object();
    private final LinkedList<AudioReference> pending = new LinkedList<>();
    private final LazyResolver lazyResolver;

    private volatile FfmpegStream currentStream;
    private volatile AudioReference currentTrack;

    public GuildAudioPlayer(LazyResolver lazyResolver) {
        this.lazyResolver = lazyResolver;
    }

    /** @return {@code true} if playback started now; {@code false} if queued. */
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
     * Enqueues every track in order. Starts playback on the first one if nothing is
     * playing; the rest stay in the queue (lazy entries are materialized later, just
     * before they play).
     */
    public boolean enqueueAll(List<AudioReference> tracks) throws IOException {
        if (tracks.isEmpty()) return false;
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

    private AudioReference materialize(AudioReference track) {
        return track.isLazy() ? lazyResolver.resolve(track) : track;
    }

    private void startStream(AudioReference track) throws IOException {
        currentStream = new FfmpegStream(track.streamUrl(), track.userAgent());
        currentTrack = track;
        System.out.println("[Audio] Starting stream: " + track.title());
    }

    /**
     * Steady-state hot path — called every 20 ms by the audio-send thread.
     * Lock-free on the common case (frame ready); only synchronises when the current
     * stream ends and we need to advance to the next track.
     */
    public byte[] pollFrame() {
        FfmpegStream stream = currentStream;
        if (stream == null) return null;
        byte[] frame = stream.pollFrame();
        if (frame != null) return frame;
        if (!stream.isExhausted()) return null;
        return rotate(stream);
    }

    /** Slow path: this stream is done, close it under the lock and try the next pending entry. */
    private byte[] rotate(FfmpegStream finished) {
        synchronized (lock) {
            // Another caller may have rotated already.
            if (currentStream != finished) {
                FfmpegStream next = currentStream;
                return next != null ? next.pollFrame() : null;
            }
            System.out.println("[Audio] Finished: "
                    + (currentTrack != null ? currentTrack.title() : "?"));
            finished.close();
            currentStream = null;
            currentTrack = null;
            advance();
            return currentStream != null ? currentStream.pollFrame() : null;
        }
    }

    /** Caller must hold {@link #lock}. */
    private void advance() {
        AudioReference next;
        while ((next = pending.poll()) != null) {
            try {
                startStream(materialize(next));
                return;
            } catch (IOException e) {
                System.err.println("[Audio] Failed to start " + next.title() + ": " + e.getMessage());
            } catch (RuntimeException e) {
                System.err.println("[Audio] Failed to resolve " + next.title() + ": " + e.getMessage());
            }
        }
    }

    public AudioReference nowPlaying() {
        return currentTrack;
    }

    public List<AudioReference> pendingTracks() {
        synchronized (lock) {
            return List.copyOf(pending);
        }
    }

    public AudioReference skip() {
        synchronized (lock) {
            if (currentStream == null) return null;
            AudioReference skipped = currentTrack;
            currentStream.close();
            currentStream = null;
            currentTrack = null;
            advance();
            return skipped;
        }
    }

    public AudioReference remove(int oneBasedPosition) {
        synchronized (lock) {
            int index = oneBasedPosition - 1;
            if (index < 0 || index >= pending.size()) return null;
            return pending.remove(index);
        }
    }

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
