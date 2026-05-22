package renzuy.audio;

import java.nio.ByteBuffer;
import net.dv8tion.jda.api.audio.AudioSendHandler;

public final class PcmAudioSendHandler implements AudioSendHandler {

    private final GuildAudioPlayer player;
    private byte[] pendingFrame;
    private long framesSent = 0;

    public PcmAudioSendHandler(GuildAudioPlayer player) {
        this.player = player;
    }

    @Override
    public boolean canProvide() {
        pendingFrame = player.pollFrame();
        if (pendingFrame != null && framesSent++ == 0) {
            System.out.println("[Audio] Discord started pulling audio frames - transmission is live.");
        }
        return pendingFrame != null;
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
