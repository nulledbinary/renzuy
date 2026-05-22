package renzuy.audio;

import java.io.IOException;
import java.io.InputStream;
import java.util.List;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.TimeUnit;

public final class FfmpegStream implements AutoCloseable {

    public static final int FRAME_BYTES = 3840;

    private static final int QUEUE_CAPACITY = 100;

    /** Used only if the resolved track did not carry a User-Agent of its own. */
    private static final String DEFAULT_USER_AGENT =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 "
                    + "(KHTML, like Gecko) Chrome/130.0.0.0 Safari/537.36";

    private final Process process;
    private final BlockingQueue<byte[]> frames = new ArrayBlockingQueue<>(QUEUE_CAPACITY);
    private final Thread reader;
    private final Thread stderrPump;
    private volatile boolean closed = false;
    private volatile boolean endOfStream = false;
    private long framesProduced = 0;

    public FfmpegStream(String streamUrl, String userAgent) throws IOException {
        String agent = (userAgent == null || userAgent.isBlank()) ? DEFAULT_USER_AGENT : userAgent;
        ProcessBuilder pb = new ProcessBuilder(List.of(
                Binaries.FFMPEG,
                "-nostdin",
                "-hide_banner",
                "-loglevel", "warning",
                // Low-latency startup: the input is plain audio of a known codec, so
                // skip ffmpeg's lengthy default probing before it emits the first PCM.
                "-fflags", "+nobuffer",
                "-probesize", "65536",
                "-analyzeduration", "0",
                // Send the User-Agent the CDN URL was issued for, then stay resilient
                // to transient network drops mid-stream.
                "-user_agent", agent,
                "-reconnect", "1",
                "-reconnect_streamed", "1",
                "-reconnect_delay_max", "5",
                "-i", streamUrl,
                "-vn",
                "-f", "s16be",
                "-ar", "48000",
                "-ac", "2",
                "-flush_packets", "1",
                "pipe:1"
        ));
        pb.redirectErrorStream(false);
        try {
            this.process = pb.start();
        } catch (IOException e) {
            throw new IOException("ffmpeg not found on PATH. Install ffmpeg and ensure it is on PATH.", e);
        }

        this.reader = new Thread(this::readLoop, "ffmpeg-reader");
        this.reader.setDaemon(true);
        this.reader.start();

        this.stderrPump = new Thread(this::pumpStderr, "ffmpeg-stderr");
        this.stderrPump.setDaemon(true);
        this.stderrPump.start();
    }

    private void readLoop() {
        try (InputStream in = process.getInputStream()) {
            byte[] buf = new byte[FRAME_BYTES];
            while (!closed) {
                int read = 0;
                while (read < FRAME_BYTES) {
                    int n = in.read(buf, read, FRAME_BYTES - read);
                    if (n == -1) break;
                    read += n;
                }
                if (read == 0) break;
                if (read < FRAME_BYTES) {
                    for (int i = read; i < FRAME_BYTES; i++) buf[i] = 0;
                }
                byte[] frame = new byte[FRAME_BYTES];
                System.arraycopy(buf, 0, frame, 0, FRAME_BYTES);
                while (!closed) {
                    if (frames.offer(frame, 250, TimeUnit.MILLISECONDS)) break;
                }
                framesProduced++;
            }
        } catch (IOException e) {
            System.err.println("[ffmpeg] error reading audio stream: " + e.getMessage());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } finally {
            endOfStream = true;
            reportOutcome();
        }
    }

    private void reportOutcome() {
        if (closed) {
            return;
        }
        int exit = -1;
        try {
            exit = process.waitFor();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        if (framesProduced == 0) {
            System.err.println("[ffmpeg] produced 0 audio frames (exit code " + exit
                    + "). ffmpeg could not read the stream URL - check the ffmpeg output above.");
        } else {
            System.out.println("[ffmpeg] decoded " + framesProduced + " frames (~"
                    + (framesProduced / 50) + "s of audio), exit code " + exit);
        }
    }

    private void pumpStderr() {
        try (InputStream err = process.getErrorStream()) {
            byte[] buf = new byte[1024];
            int n;
            while ((n = err.read(buf)) != -1) {
                System.err.write(buf, 0, n);
                System.err.flush();
            }
        } catch (IOException ignored) {
        }
    }

    public byte[] pollFrame() {
        return frames.poll();
    }

    public boolean isExhausted() {
        return endOfStream && frames.isEmpty();
    }

    @Override
    public void close() {
        closed = true;
        if (process.isAlive()) {
            process.destroy();
            try {
                if (!process.waitFor(2, TimeUnit.SECONDS)) {
                    process.destroyForcibly();
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
        reader.interrupt();
        stderrPump.interrupt();
        frames.clear();
    }
}
