package renzuy.audio;

import java.io.IOException;
import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedQueue;
import renzuy.youtube.AudioReference;

/**
 * Per-guild playback: a single playing stream plus a FIFO queue of pending tracks.
 *
 * <p>Tracks arrive already resolved as {@link AudioReference}s — the stream URL is
 * playable immediately, so starting a track is just spawning ffmpeg, with no
 * resolution latency at the moment of playback.
 */
public final class GuildAudioPlayer {

    private final Object lock = new Object();
    private final Queue<AudioReference> pending = new ConcurrentLinkedQueue<>();
    private FfmpegStream currentStream;
    private String currentTitle;

    /** @return {@code true} if playback started now, {@code false} if the track was queued. */
    public boolean enqueue(AudioReference track) throws IOException {
        synchronized (lock) {
            if (currentStream == null) {
                startStream(track);
                return true;
            }
            pending.offer(track);
            return false;
        }
    }

    private void startStream(AudioReference track) throws IOException {
        currentStream = new FfmpegStream(track.streamUrl(), track.userAgent());
        currentTitle = track.title();
        System.out.println("[Audio] Starting stream: " + currentTitle);
    }

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
            System.out.println("[Audio] Finished: " + currentTitle);
            currentStream.close();
            currentStream = null;
            currentTitle = null;
            advance();
            return currentStream != null ? currentStream.pollFrame() : null;
        }
    }

    private void advance() {
        AudioReference next;
        while ((next = pending.poll()) != null) {
            try {
                startStream(next);
                return;
            } catch (IOException e) {
                System.err.println("[Audio] Failed to start " + next.title() + ": " + e.getMessage());
            }
        }
    }

    public void stop() {
        synchronized (lock) {
            if (currentStream != null) {
                currentStream.close();
                currentStream = null;
                currentTitle = null;
            }
            pending.clear();
        }
    }
}
