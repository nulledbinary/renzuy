package renzuy.audio;

import net.dv8tion.jda.api.audio.hooks.ConnectionListener;
import net.dv8tion.jda.api.audio.hooks.ConnectionStatus;

/**
 * Logs every voice-connection state transition so playback failures become diagnosable.
 *
 * <p>A healthy connection walks through the {@code CONNECTING_*} states and finishes on
 * {@link ConnectionStatus#CONNECTED}. If the log instead stops on a {@code CONNECTING_*}
 * state or reports an {@code ERROR_*} / {@code DISCONNECTED_*} status, that status is the
 * root cause of "the bot joins the channel but no audio plays". In particular
 * {@code ERROR_UDP_*} means outbound UDP (required for voice) is being blocked.
 */
public final class VoiceConnectionLogger implements ConnectionListener {

    private final String guildName;

    public VoiceConnectionLogger(String guildName) {
        this.guildName = guildName;
    }

    @Override
    public void onStatusChange(ConnectionStatus status) {
        String line = "[Voice] " + guildName + ": " + status;
        if (status.name().startsWith("ERROR") || status.name().startsWith("DISCONNECTED")) {
            System.err.println(line + "  <-- voice connection problem");
        } else {
            System.out.println(line);
        }
    }
}
