package renzuy.audio;

import java.nio.ByteBuffer;
import net.dv8tion.jda.api.audio.AudioSendHandler;

/**
 * Bridges {@link GuildAudioPlayer} (PCM frames) to JDA's audio-send thread.
 *
 * <p>{@code canProvide} fires every 20 ms. It pulls one frame and stashes it for the
 * immediately-following {@link #provide20MsAudio()} — JDA always calls them paired,
 * so a single shared field is enough and we avoid double-polling the player.
 */
public final class PcmAudioSendHandler implements AudioSendHandler {

    private final GuildAudioPlayer player;
    private byte[] pendingFrame;
    private boolean firstFrameLogged;

    public PcmAudioSendHandler(GuildAudioPlayer player) {
        this.player = player;
    }

    @Override
    public boolean canProvide() {
        pendingFrame = player.pollFrame();
        if (pendingFrame == null) return false;
        if (!firstFrameLogged) {
            firstFrameLogged = true;
            System.out.println("[Audio] Discord started pulling audio frames — transmission is live.");
        }
        return true;
    }

    @Override
    public ByteBuffer provide20MsAudio() {
        return ByteBuffer.wrap(pendingFrame);
    }

    @Override
    public boolean isOpus() {
        return false;
    }
}
